package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.export.InteractiveAntialiasService;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Headless scope-matched baseline. No JavaFX publication or physical presentation is timed. */
public final class BaselineBenchmark {
    private BaselineBenchmark() {}

    public static void main(String[] args) throws Exception {
        int warmups = Integer.getInteger("baseline.warmups", 1);
        int runs = Integer.getInteger("baseline.runs", 3);
        if (warmups < 0 || runs < 1) throw new IllegalArgumentException("Invalid sample counts");
        List<BaselineFixtures.Fixture> fixtures = selectedFixtures();
        Path directory = Path.of(System.getProperty("baseline.output", "target/baseline-" + Instant.now().toEpochMilli()));
        Files.createDirectories(directory.toAbsolutePath().getParent());
        // A directory is the atomic unit of a run: historical outputs cannot be overwritten.
        Files.createDirectory(directory);
        try (var manifest = Files.newBufferedWriter(directory.resolve("manifest.csv"), StandardOpenOption.CREATE_NEW)) {
            manifest.write(BaselineFixtures.HEADER + "\n");
            for (var fixture : fixtures) for (var step : fixture.steps()) {
                manifest.write(step.csv(fixture.id()) + "\n");
            }
        }
        if (Boolean.getBoolean("baseline.manifestOnly")) return;
        // Protect this harness across checkouts. Other timing suites must still be stopped manually.
        Path lockPath = Path.of(System.getProperty("java.io.tmpdir"), "fractalui-baseline-benchmark.lock");
        try (var lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lockChannel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another baseline benchmark is running");
            run(fixtures, directory, warmups, runs);
        }
    }

    static List<BaselineFixtures.Fixture> selectedFixtures() {
        String filter = System.getProperty("baseline.fixtures", "all");
        Set<String> requested = new LinkedHashSet<>(List.of(filter.split(",")));
        Set<String> found = new HashSet<>();
        List<BaselineFixtures.Fixture> selected = new ArrayList<>();
        Set<String> sizes = new HashSet<>();
        for (String size : System.getProperty("baseline.sizes", "480x270").split(",")) {
            if (!sizes.add(size)) throw new IllegalArgumentException("Duplicate size: " + size);
            String[] parts = size.split("x", -1);
            if (parts.length != 2) throw new IllegalArgumentException("Expected WIDTHxHEIGHT");
            for (var fixture : BaselineFixtures.matrix(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]))) {
                if (filter.equals("all") || requested.contains(fixture.id())) {
                    selected.add(fixture);
                    found.add(fixture.id());
                }
            }
        }
        if (!filter.equals("all") && !found.equals(requested)) {
            requested.removeAll(found);
            throw new IllegalArgumentException("Unknown fixtures: " + requested);
        }
        return List.copyOf(selected);
    }

    private static void run(List<BaselineFixtures.Fixture> fixtures, Path directory, int warmups, int runs) throws Exception {
        boolean profile = Boolean.getBoolean("fractal.aa.profile");
        Runtime runtime = Runtime.getRuntime();
        String metadata = "matrix=" + BaselineFixtures.VERSION + "\nstarted=" + Instant.now()
                + "\nlabel=" + System.getProperty("baseline.label", "current")
                + "\nrevision=" + System.getProperty("baseline.revision", "unspecified")
                + "\njava=" + System.getProperty("java.runtime.version") + "\nvm=" + System.getProperty("java.vm.name")
                + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "\narch=" + System.getProperty("os.arch") + "\nprocessors=" + runtime.availableProcessors()
                + "\nworkers_per_pool=" + Math.max(1, runtime.availableProcessors() - 1)
                + "\ntile_size=32\nmax_heap_bytes=" + runtime.maxMemory()
                + "\naa_cache_capacity_bytes=" + AntialiasSampleCache.DEFAULT_MAX_BYTES
                + "\nreference_cache_capacity_bytes=" + MandelbrotPerturbationRenderBackend.DEFAULT_REFERENCE_CACHE_BYTES
                + "\nvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                + "\naa_profile=" + profile + "\nwarmups=" + warmups + "\nsamples=" + runs
                + "\nphysical_scanout=unmeasured\njavafx_publication=unmeasured\nthermal=unmeasured\n";
        Files.writeString(directory.resolve("environment.txt"), metadata, StandardOpenOption.CREATE_NEW);
        try (var csv = new PrintWriter(Files.newBufferedWriter(directory.resolve("samples.csv"), StandardOpenOption.CREATE_NEW))) {
            csv.println("fixture,step,width,height,phase,run,backend,cap,reused_pixels,reuse_source,plan_ms,first_useful_samples_ms,first_new_region_ms,backend_ms,returned_argb_ms,color_ms,aa_prepare_ms,aa_ms,first_aa_tile_ms,operation_ms,cancel_request_ms,cancel_tail_ms,regions,complete,sample_hash,argb_hash,heap_before_bytes,heap_after_bytes,gc_count_delta,gc_ms_delta");
            for (var fixture : fixtures) {
                try (var backend = newBackend(); var aa = new InteractiveAntialiasService()) {
                    for (int run = -warmups - 1; run < runs; run++) {
                        String phase = run == -warmups - 1 ? "cold_fixture" : run < 0 ? "warmup" : "sample";
                        RenderFrame active = null, retained = null;
                        for (var step : fixture.steps()) {
                            long heapBefore = usedHeap(), gcBefore = gcCount(), gcMsBefore = gcMillis();
                            Measurement m = measure(step, backend, aa, active, retained);
                            long heapAfter = usedHeap(), gcAfter = gcCount(), gcMsAfter = gcMillis();
                            // Fingerprints/validity scans run after every measurement boundary.
                            long hash = sampleHash(m.frame());
                            csv.printf(Locale.ROOT,
                                    "%s,%s,%d,%d,%s,%d,%s,%d,%d,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%s,%d,%d,%d,%d,%d,%d%n",
                                    fixture.id(), step.name(), step.job().width(), step.job().height(), phase, run,
                                    m.backend(), step.job().maxIterations(), m.reusedPixels(), m.reuseSource(),
                                    ms(m.plan()), ms(m.firstUseful()), ms(m.firstRegion()), ms(m.backendTime()),
                                    ms(m.argbTime()), ms(m.colorTime()), ms(m.aaPreparation()), ms(m.aaTime()), ms(m.firstAa()),
                                    ms(m.operation()), ms(m.cancelRequest()), ms(m.cancelTail()), m.regions(), m.frame().isComplete(),
                                    hash, m.pixels() == null ? 0 : Arrays.hashCode(m.pixels()), heapBefore, heapAfter,
                                    gcAfter < 0 || gcBefore < 0 ? -1 : gcAfter - gcBefore,
                                    gcMsAfter < 0 || gcMsBefore < 0 ? -1 : gcMsAfter - gcMsBefore);
                            csv.flush();
                            retained = active;
                            active = m.frame();
                        }
                    }
                }
                System.out.println("Completed " + fixture.id() + " " + fixture.steps().getFirst().job().width()
                        + "x" + fixture.steps().getFirst().job().height());
            }
            if (csv.checkError()) throw new IOException("Failed to write baseline samples");
        }
    }

    static PrecisionSelectingRenderBackend newBackend() {
        return new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(), new MandelbrotPerturbationRenderBackend());
    }

    record Measurement(RenderFrame frame, int[] pixels, String backend, int reusedPixels, String reuseSource,
                       long plan, long firstUseful, long firstRegion, long backendTime, long argbTime, long colorTime,
                       long aaPreparation, long aaTime, long firstAa, long operation, long cancelRequest,
                       long cancelTail, long regions) {}

    static Measurement measure(BaselineFixtures.Step step, PrecisionSelectingRenderBackend backend,
                               InteractiveAntialiasService aa, RenderFrame active, RenderFrame retained) throws Exception {
        AtomicLong first = new AtomicLong(-1), cancel = new AtomicLong(-1), regions = new AtomicLong();
        long start = System.nanoTime();
        FrameReuseSelection selection = step.action() == BaselineFixtures.Action.REUSE
                ? new FrameReusePlanner().plan(active, retained, step.job()) : null;
        FrameReuseResult reuse = selection == null ? FrameReuseResult.fresh(RenderFrame.create(step.job())) : selection.result();
        RenderFrame frame = reuse.frame();
        long planned = System.nanoTime();
        RenderBackend selected = backend.select(step.job());
        long backendStart = System.nanoTime();
        selected.render(frame, () -> cancel.get() >= 0, region -> {
            long now = System.nanoTime();
            first.accumulateAndGet(now, (old, value) -> old < 0 ? value : Math.min(old, value));
            regions.incrementAndGet();
            if (step.action() == BaselineFixtures.Action.CANCEL_FIRST_REGION) cancel.compareAndSet(-1, now);
        }, null);
        long backendEnd = System.nanoTime();
        long firstRegion = first.get() < 0 ? -1 : first.get() - start;
        long firstUseful = reuse.reused() ? planned - start : firstRegion;
        long cancelledAt = cancel.get();
        int[] pixels = null;
        long argbEnd = -1, colorTime = -1, aaPreparation = -1, aaTime = -1, firstAa = -1;
        if (cancelledAt < 0) {
            if (!frame.isComplete()) throw new IllegalStateException("Incomplete non-cancelled fixture " + step.name());
            long colorStart = System.nanoTime();
            ColoringStrategy coloring = step.coloring(frame);
            pixels = new int[step.job().width() * step.job().height()];
            new FractalColorizer().color(frame.samplePlane(), IntBuffer.wrap(pixels), coloring);
            argbEnd = System.nanoTime();
            colorTime = argbEnd - colorStart;
            if (step.aa() != BaselineFixtures.Aa.NONE) {
                boolean deep = selected instanceof MandelbrotPerturbationRenderBackend;
                if (step.aa() == BaselineFixtures.Aa.SAME_FRAME) {
                    aaPreparation = refine(step, aa, frame, coloring, pixels, deep)[0];
                }
                long[] timing = refine(step, aa, frame, coloring, pixels, deep);
                aaTime = timing[0]; firstAa = timing[1];
            }
        }
        long end = System.nanoTime();
        if (step.action() == BaselineFixtures.Action.CANCEL_FIRST_REGION && cancelledAt < 0) {
            throw new IllegalStateException("Cancellation fixture never reached its trigger");
        }
        String source = selection == null || selection.sourceFrame() == null ? "none"
                : selection.sourceFrame() == active ? "active" : "retained";
        return new Measurement(frame, pixels, selected.getClass().getSimpleName(), reuse.reusedPixels(), source,
                planned - start, firstUseful, firstRegion, backendEnd - backendStart,
                argbEnd < 0 ? -1 : argbEnd - start, colorTime, aaPreparation, aaTime, firstAa, end - start,
                cancelledAt < 0 ? -1 : cancelledAt - start, cancelledAt < 0 ? -1 : backendEnd - cancelledAt, regions.get());
    }

    static long[] refine(BaselineFixtures.Step step, InteractiveAntialiasService service, RenderFrame frame,
                                 ColoringStrategy coloring, int[] pixels, boolean deep) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicLong first = new AtomicLong(-1);
        long start = System.nanoTime();
        java.util.function.BiConsumer<RenderRegion, int[]> publish = (region, colors) -> {
            for (int y = 0; y < region.height(); y++) {
                System.arraycopy(colors, y * region.width(), pixels,
                        (region.y() + y) * step.job().width() + region.x(), region.width());
            }
            first.compareAndSet(-1, System.nanoTime() - start);
        };
        if (deep) service.refineDeep(frame, coloring, step.pattern(), RefinedPixelSnapshot.empty(step.job().width(), step.job().height()),
                Runnable::run, publish, () -> done.complete(null), done::completeExceptionally);
        else service.refine(frame, coloring, step.pattern(), RefinedPixelSnapshot.empty(step.job().width(), step.job().height()),
                Runnable::run, publish, () -> done.complete(null), done::completeExceptionally);
        done.get(180, TimeUnit.SECONDS);
        return new long[]{System.nanoTime() - start, first.get()};
    }

    static long sampleHash(RenderFrame frame) {
        long hash = 0xcbf29ce484222325L;
        var ready = frame.validity().readyBitsCopy();
        SamplePlane data = frame.samplePlane();
        for (int i = ready.nextSetBit(0); i >= 0; i = ready.nextSetBit(i + 1)) {
            hash = mix(hash, i);
            hash = mix(hash, data.iterations(i));
            hash = mix(hash, Double.doubleToLongBits(data.smoothIterations(i)));
            hash = mix(hash, data.escaped(i) ? 1 : 0);
            hash = mix(hash, Double.doubleToLongBits(data.orbitTrapDistance(i)));
        }
        return hash;
    }

    private static long mix(long hash, long value) { return (hash ^ value) * 0x100000001b3L; }
    private static double ms(long nanos) { return nanos < 0 ? -1 : nanos / 1e6; }
    static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    static long gcCount() {
        var values = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> bean.getCollectionCount()).toArray();
        return Arrays.stream(values).anyMatch(v -> v < 0) ? -1 : Arrays.stream(values).sum();
    }
    static long gcMillis() {
        var values = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> bean.getCollectionTime()).toArray();
        return Arrays.stream(values).anyMatch(v -> v < 0) ? -1 : Arrays.stream(values).sum();
    }
}
