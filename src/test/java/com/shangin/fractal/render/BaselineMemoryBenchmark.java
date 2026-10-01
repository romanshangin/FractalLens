package com.shangin.fractal.render;

import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.application.Platform;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.BufferPoolMXBean;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static com.shangin.fractal.render.BaselineFxBenchmark.fx;
import static com.shangin.fractal.render.BaselineInputBenchmark.*;

/** Diagnostic soak, never an uninstrumented performance decision run. */
public final class BaselineMemoryBenchmark {
    private BaselineMemoryBenchmark() {}

    public static void main(String[] args) throws Exception {
        int seconds = Integer.getInteger("baseline.memory.seconds", 600);
        int cycles = Integer.getInteger("baseline.memory.cycles", 3);
        if (seconds < 1 || cycles < 1) throw new IllegalArgumentException("Positive duration/cycles required");
        var fixtures = BaselineBenchmark.selectedFixtures();
        var modes = Arrays.stream(System.getProperty("baseline.fx.modes", "FAST,REFINED").split(","))
                .map(InteractiveRenderMode::valueOf).toList();
        if (new HashSet<>(modes).size() != modes.size()) throw new IllegalArgumentException("Duplicate mode");
        Path output = Path.of(System.getProperty("baseline.output"));
        Files.createDirectory(output);
        System.setProperty("fractal.gpu.enabled", "false");
        System.setProperty("fractal.gpu.mandelbrot.enabled", "false");
        var lockPath = Path.of(System.getProperty("java.io.tmpdir"), "fractallens-baseline-benchmark.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another baseline benchmark is running");
            try (var manifest = writer(output, "manifest.csv")) {
                manifest.println(BaselineFixtures.HEADER);
                for (var fixture : fixtures) for (var step : fixture.steps()) manifest.println(step.csv(fixture.id()));
            }
            Files.writeString(output.resolve("environment.txt"), "matrix=" + BaselineFixtures.VERSION
                    + "\nmemory_matrix=9.1-memory-v1\njava=" + System.getProperty("java.runtime.version")
                    + "\nrevision=" + System.getProperty("baseline.revision", "unspecified")
                    + "\nvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                    + "\nseconds=" + seconds + "\ncycles=" + cycles + "\nmodes=" + String.join(",", modes.stream().map(Enum::name).toList())
                    + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
                    + "\nmax_heap_bytes=" + Runtime.getRuntime().maxMemory()
                    + "\nrender_diagnostics=" + RenderDiagnostics.enabled()
                    + "\ngpu_calculation=false\nphysical_scanout=unmeasured\n", StandardOpenOption.CREATE_NEW);
            var started = new CompletableFuture<Void>();
            Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
            started.get(15, TimeUnit.SECONDS);
            try (var ui = fx(Ui::new); var scopes = writer(output, "scopes.csv");
                 var samples = writer(output, "samples.csv"); var actual = writer(output, "actual-manifest.csv");
                 var events = writer(output, "events.csv")) {
                Files.writeString(output.resolve("fx-environment.txt"), "javafx=" + System.getProperty("javafx.runtime.version")
                        + "\noutput_scale=" + fx(() -> ui.stage.getOutputScaleX() + "x" + ui.stage.getOutputScaleY()) + "\n");
                scopes.println("round,fixture,mode,cycle,trial,scope,start,end,elapsed_ms,heap_before,heap_after,nonheap_after,direct_bytes,mapped_bytes,gc_count_delta,gc_ms_delta");
                samples.println(HEADER); actual.println("trial,role," + BaselineFixtures.HEADER); events.println(EVENT_HEADER);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds), trial = 0;
                int round = 0;
                do {
                    for (var fixture : fixtures) for (var mode : modes) {
                        trial = exercise(ui, fixture, mode, cycles, round, trial, scopes, samples, actual, events);
                    }
                    // The completed exercise method owns all Result locals; none are roots in this loop.
                    fx(() -> { ui.pending.clear(); ui.view.close(); ui.root.getChildren().clear(); ui.view = null; return null; });
                    try (var ignored = new Scope(scopes, round, "all", modes.getFirst(), -1, 0, "detached_gc_checkpoint")) {
                        System.gc(); // Deliberate diagnostic checkpoint, excluded from navigation timings.
                        Thread.sleep(200);
                    }
                    System.out.println("Verified memory round " + round + ", trials=" + trial);
                    round++;
                } while (System.nanoTime() < deadline || round < 2);
                if (scopes.checkError() || samples.checkError() || actual.checkError() || events.checkError()) {
                    throw new IllegalStateException("CSV write failure");
                }
                Files.writeString(output.resolve("SUCCESS"), "rounds=" + round + "\ntrials=" + trial
                        + "\nAll seed, forward and reverse frames passed exact sample/ARGB controls.\n", StandardOpenOption.CREATE_NEW);
                if (Boolean.getBoolean("baseline.memory.externalMonitor")) {
                    Files.writeString(output.resolve("MONITOR_READY"), "Workload complete; process held for final NMT.\n");
                    long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                    while (!Files.exists(output.resolve("MONITOR_RELEASE"))) {
                        if (System.nanoTime() > timeout) throw new IllegalStateException("External monitor did not release process");
                        Thread.sleep(100);
                    }
                }
            } finally { Platform.exit(); }
        }
    }

    private static long exercise(Ui ui, BaselineFixtures.Fixture fixture, InteractiveRenderMode mode,
                                 int cycles, int round, long trial, PrintWriter scopes, PrintWriter samples,
                                 PrintWriter actual, PrintWriter events) throws Exception {
        try (var ignored = new Scope(scopes, round, fixture.id(), mode, -1, 0, "seed_and_control")) {
            ui.seed(fixture.steps().getFirst(), mode);
        }
        var source = fx(() -> actualStep(ui, fixture.steps().getFirst(), "source"));
        for (int cycle = 0; cycle < cycles; cycle++) {
            var steps = fixture.steps().size() > 1 ? fixture.steps().subList(1, fixture.steps().size())
                    : List.of(fixture.steps().getFirst());
            for (var step : steps) {
                String gesture = fixture.steps().size() > 1 ? "sequence"
                        : fixture.id().startsWith("cancel-") ? "replacement" : "pinch";
                Result r;
                try (var ignored = new Scope(scopes, round, fixture.id(), mode, cycle, ++trial, "navigation")) {
                    r = measure(ui, fixture, step, mode, gesture, trial);
                }
                save(r, source, "sample", round * cycles + cycle, samples, events, actual);
                try (var ignored = new Scope(scopes, round, fixture.id(), mode, cycle, trial, "control")) { verify(r); }
                source = r.actual;
            }
            // Return using installed input handlers; preserve the same view and its caches.
            Result back;
            try (var ignored = new Scope(scopes, round, fixture.id(), mode, cycle, ++trial, "navigation")) {
                back = measure(ui, fixture, fixture.steps().getFirst(), mode, "sequence", trial);
            }
            save(back, source, "sample", round * cycles + cycle, samples, events, actual);
            try (var ignored = new Scope(scopes, round, fixture.id(), mode, cycle, trial, "control")) { verify(back); }
            source = back.actual;
        }
        return trial;
    }

    private static PrintWriter writer(Path output, String name) throws Exception {
        return new PrintWriter(Files.newBufferedWriter(output.resolve(name), StandardOpenOption.CREATE_NEW));
    }

    static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> b.getCollectionCount()).filter(n -> n >= 0).sum(); }
    static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> b.getCollectionTime()).filter(n -> n >= 0).sum(); }
    static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    static long buffer(String name) {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream().filter(b -> b.getName().equals(name))
                .mapToLong(BufferPoolMXBean::getMemoryUsed).findFirst().orElse(-1);
    }
    static final class Scope implements AutoCloseable {
        final PrintWriter writer;
        final String prefix;
        final long before = heap(), count = gcCount(), gc = gcMillis(), nanos = System.nanoTime();
        final Instant start = Instant.now();
        Scope(PrintWriter writer, int round, String fixture, InteractiveRenderMode mode, int cycle, long trial, String scope) {
            this.writer = writer;
            prefix = round + "," + fixture + "," + mode.name() + "," + cycle + "," + trial + "," + scope;
        }
        @Override public void close() {
            Instant end = Instant.now();
            writer.printf(Locale.ROOT, "%s,%s,%s,%.6f,%d,%d,%d,%d,%d,%d,%d%n", prefix, start, end,
                    (System.nanoTime() - nanos) / 1e6, before, heap(),
                    ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(), buffer("direct"), buffer("mapped"),
                    gcCount() - count, gcMillis() - gc);
            writer.flush();
        }
    }
}
