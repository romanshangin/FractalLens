package com.shangin.fractal.render;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Base-render scheduling attribution on the shared matrix; AA and FX are separate scopes. */
public final class BaselineSchedulingBenchmark {
    private BaselineSchedulingBenchmark() {}
    public static void main(String[] args) throws Exception {
        if (!RenderDiagnostics.enabled()) throw new IllegalArgumentException("Enable " + RenderDiagnostics.PROPERTY + " at JVM startup");
        System.setProperty("fractal.gpu.enabled", "false");
        var fixtures = BaselineBenchmark.selectedFixtures();
        int runs = Integer.getInteger("baseline.runs", 3), warmups = Integer.getInteger("baseline.warmups", 1);
        if (runs < 1 || warmups < 0) throw new IllegalArgumentException("Invalid sample counts");
        Path output = Path.of(System.getProperty("baseline.output", "target/baseline-scheduling-" + System.currentTimeMillis()));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output);
        Files.writeString(output.resolve("environment.txt"), "matrix=" + BaselineFixtures.VERSION
                + "\nscheduling=9.1-scheduling-v1\nstarted=" + java.time.Instant.now()
                + "\nrevision=" + System.getProperty("baseline.revision", "unspecified")
                + "\njava=" + System.getProperty("java.runtime.version")
                + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "\narch=" + System.getProperty("os.arch") + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
                + "\nmax_heap_bytes=" + Runtime.getRuntime().maxMemory()
                + "\nvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                + "\ncancel_planning=" + Boolean.getBoolean("baseline.cancelPlanning") + "\nruns=" + runs + "\nwarmups=" + warmups
                + "\nscope=base_render_service\nAA=unmeasured\nFX=unmeasured\nthermal=unmeasured\n");
        try (var manifest = writer(output, "manifest.csv")) {
            manifest.println(BaselineFixtures.HEADER);
            for (var f : fixtures) for (var step : f.steps()) manifest.println(step.csv(f.id()));
        }
        var lockPath = Path.of(System.getProperty("java.io.tmpdir"), "fractallens-baseline-benchmark.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock(); var rows = writer(output, "samples.csv"); var events = writer(output, "diagnostics.csv")) {
            if (lock == null) throw new IllegalStateException("Another baseline benchmark is running");
            rows.println("trial,fixture,step,width,height,phase,run,trigger,backend,reused_pixels,generation,complete,ready_pixels,sample_hash");
            events.println("trial,key,value");
            long trial = 0;
            for (var fixture : fixtures) {
                try (var selected = BaselineBenchmark.newBackend(); var control = BaselineBenchmark.newBackend()) {
                    var serviceRef = new AtomicReference<FractalRenderService>();
                    var cancelFirst = new AtomicBoolean();
                    var triggered = new AtomicBoolean();
                    var requests = new LinkedBlockingQueue<RenderDiagnostics>();
                    RenderBackend wrapper = new RenderBackend() {
                        public RenderFrame render(RenderFrame f, java.util.function.BooleanSupplier c,
                                                  java.util.function.Consumer<RenderRegion> p,
                                                  java.util.function.Consumer<TileTimingStats> t) throws InterruptedException {
                            return selected.render(f, c, region -> {
                                if (cancelFirst.get() && triggered.compareAndSet(false, true)) serviceRef.get().cancelCurrent();
                                p.accept(region);
                            }, t);
                        }
                        public void close() {} // selected is owned by the outer scope
                    };
                    try (var service = new FractalRenderService(wrapper)) {
                        serviceRef.set(service); service.setDiagnosticsListener(requests::add);
                        for (int run = -warmups - 1; run < runs; run++) {
                            String phase = run == -warmups - 1 ? "first_sequence" : run < 0 ? "warmup" : "sample";
                            RenderFrame active = null, retained = null;
                            for (var step : fixture.steps()) {
                                var reuse = step.action() == BaselineFixtures.Action.REUSE
                                        ? new FrameReusePlanner().plan(active, retained, step.job()).result()
                                        : FrameReuseResult.fresh(RenderFrame.create(step.job()));
                                var expected = step.action() == BaselineFixtures.Action.REUSE
                                        ? new FrameReusePlanner().plan(active, retained, step.job()).result().frame()
                                        : RenderFrame.create(step.job());
                                String trigger = step.action() == BaselineFixtures.Action.CANCEL_FIRST_REGION ? "first_region" : "none";
                                var snapshot = measure(service, requests, reuse.frame(), trigger, cancelFirst, triggered);
                                verify(reuse.frame(), expected, control);
                                save(++trial, fixture.id(), step, phase, run, trigger, selected, reuse, snapshot, rows, events);
                                retained = active; active = reuse.frame();
                                if (Boolean.getBoolean("baseline.cancelPlanning") && reuse.reusedPixels() == step.job().width() * step.job().height()) {
                                    var repeated = new FrameReusePlanner().plan(active, step.job());
                                    var cancelled = measure(service, requests, repeated.frame(), "planning", cancelFirst, triggered);
                                    verify(repeated.frame(), active, control);
                                    save(++trial, fixture.id(), step, phase, run, "planning", selected, repeated, cancelled, rows, events);
                                }
                            }
                            System.out.println("Verified " + fixture.id() + " " + fixture.steps().getFirst().job().width() + "x" + fixture.steps().getFirst().job().height() + " " + phase + " " + run);
                        }
                    }
                }
            }
            if (rows.checkError() || events.checkError()) throw new IllegalStateException("CSV write failure");
            Files.writeString(output.resolve("SUCCESS"), "All requests drained. Every ready sample passed exact CPU control.\n", StandardOpenOption.CREATE_NEW);
        }
    }

    private static RenderDiagnostics.Snapshot measure(FractalRenderService service, BlockingQueue<RenderDiagnostics> requests,
            RenderFrame frame, String trigger, AtomicBoolean cancelFirst, AtomicBoolean triggered) throws Exception {
        cancelFirst.set(trigger.equals("first_region")); triggered.set(false);
        var published = new CompletableFuture<RenderFrame>();
        service.render(frame, Runnable::run, p -> {}, published::complete, published::completeExceptionally);
        var diagnostics = requests.poll(10, TimeUnit.SECONDS);
        if (diagnostics == null) throw new IllegalStateException("Missing diagnostics");
        if (trigger.equals("planning")) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                var v = diagnostics.currentValues();
                if (v.containsKey("planning_end") || diagnostics.completion().isDone() || System.nanoTime() > deadline)
                    throw new IllegalStateException("Missed planning cancellation trigger");
                if (v.containsKey("planning_start")) { service.cancelCurrent(); triggered.set(true); break; }
                Thread.sleep(1);
            }
        }
        var snapshot = diagnostics.completion().get(180, TimeUnit.SECONDS);
        if (trigger.equals("none")) published.get(180, TimeUnit.SECONDS);
        else if (!triggered.get() || !snapshot.values().containsKey("cancel_requested"))
            throw new IllegalStateException("Cancellation did not reach an active request");
        validate(snapshot);
        return snapshot;
    }

    static void validate(RenderDiagnostics.Snapshot snapshot) {
        var v = snapshot.values();
        long registered = v.getOrDefault("worker_tasks_registered", 0L), started = v.getOrDefault("worker_tasks_started", 0L);
        if (registered != v.getOrDefault("worker_tasks_terminal", -1L)
                || registered != started + v.getOrDefault("worker_tasks_cancelled_before_start", 0L)
                || started > v.getOrDefault("worker_tasks_submitted", 0L)
                || (!v.containsKey("cancel_requested") && v.getOrDefault("planned_tasks", 0L) != registered)
                || (v.containsKey("cancel_requested") && v.containsKey("cancel_observed")
                    && v.get("cancel_requested") > v.get("cancel_observed"))
                || v.get("request_drained") < v.getOrDefault("last_worker_exit", v.get("request_submitted"))
                || v.get("request_drained") < v.getOrDefault("coordinator_exit", v.getOrDefault("coordinator_cancelled_before_start", Long.MAX_VALUE)))
            throw new IllegalStateException("Invalid worker accounting " + v);
    }

    static void verify(RenderFrame actual, RenderFrame expected, RenderBackend control) throws Exception {
        if (!expected.isComplete()) control.render(expected, () -> false, region -> {}, null);
        if (!expected.isComplete()) throw new IllegalStateException("Incomplete control");
        var a = actual.samplePlane(); var b = expected.samplePlane();
        var ready = actual.validity().readyBitsCopy();
        for (int i = ready.nextSetBit(0); i >= 0; i = ready.nextSetBit(i + 1)) {
            if (a.iterations(i) != b.iterations(i) || a.escaped(i) != b.escaped(i)
                    || Double.doubleToLongBits(a.smoothIterations(i)) != Double.doubleToLongBits(b.smoothIterations(i))
                    || Double.doubleToLongBits(a.orbitTrapDistance(i)) != Double.doubleToLongBits(b.orbitTrapDistance(i)))
                throw new IllegalStateException("CPU sample mismatch at " + i);
        }
    }
    private static void save(long trial, String fixture, BaselineFixtures.Step step, String phase, int run, String trigger,
            PrecisionSelectingRenderBackend backend, FrameReuseResult reuse, RenderDiagnostics.Snapshot snapshot,
            PrintWriter rows, PrintWriter events) {
        rows.printf(Locale.ROOT, "%d,%s,%s,%d,%d,%s,%d,%s,%s,%d,%d,%s,%d,%d%n", trial, fixture, step.name(),
                step.job().width(), step.job().height(), phase, run, trigger, backend.select(step.job()).getClass().getSimpleName(),
                reuse.reusedPixels(), snapshot.generation(), reuse.frame().isComplete(), reuse.frame().validity().readyPixelCount(),
                BaselineBenchmark.sampleHash(reuse.frame()));
        snapshot.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> events.println(trial + "," + e.getKey() + "," + e.getValue()));
        rows.flush(); events.flush();
    }
    private static PrintWriter writer(Path output, String name) throws Exception {
        return new PrintWriter(Files.newBufferedWriter(output.resolve(name), StandardOpenOption.CREATE_NEW));
    }
}
