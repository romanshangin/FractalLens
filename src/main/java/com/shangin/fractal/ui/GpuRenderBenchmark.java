package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.*;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.*;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Paired production base/AA pipeline benchmark; opt-in and never used by the application. */
public final class GpuRenderBenchmark extends Application {
    private static volatile Throwable failure;
    private Stage stage;
    private FractalSurface surface;
    private AnimationTimer pulse;
    private CompletableFuture<Long> nextPulse;
    private final List<String> rows = new ArrayList<>();
    private final SmoothPaletteColoring coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
    private static final String HEADER = "scene,width,height,aa,backend,phase,iteration,profile,aa_profile,first_region_ms,first_publish_ms,calculation_ms,base_frame_ms,total_frame_ms,fx_color_publish_ms,next_pulse_ms,certified,recovered,fallback,pack_ms,upload_ms,dispatch_ms,readback_ms,kernel_ms,recover_publish_ms,certification_ms,recovery_ms,publication_ms,batches,native_bytes,staging_bytes,host_workers,region_size,aa_total_ms,aa_base_color_ms,aa_candidate_cpu_ms,aa_candidate_critical_path_ms,aa_sampling_cpu_ms,aa_cache_color_cpu_ms,aa_tested_pixels,aa_candidates,aa_samples,heap_before_bytes,heap_after_bytes,heap_pool_peak_sum_bytes,gc_count,gc_ms,max_smooth_error";

    public static void main(String[] args) {
        launch(args);
        if (failure != null) throw new IllegalStateException("GPU render benchmark failed", failure);
    }
    @Override public void start(Stage stage) {
        this.stage = stage;
        surface = new FractalSurface();
        stage.setScene(new Scene(new StackPane(surface), 756, 491));
        stage.setTitle("FractalLens — GPU calculation decision gate");
        stage.show();
        pulse = new AnimationTimer() {
            @Override public void handle(long now) {
                if (nextPulse != null) {
                    var pending = nextPulse;
                    nextPulse = null;
                    pending.complete(System.nanoTime());
                }
            }
        };
        pulse.start();
        new Thread(this::runBenchmark, "gpu-render-benchmark").start();
    }
    private void runBenchmark() {
        Path output = Path.of(System.getProperty("fractal.benchmark.output", "target/gpu-render-benchmark.csv"));
        boolean cpuOnly = Boolean.getBoolean("fractal.benchmark.cpuOnly");
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
            if (!cpuOnly && !runtime.isUsableFor(GpuNumericCapability.FLOAT32)) throw new IllegalStateException(runtime.capabilityReport().toString());
            var cpu = new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(), new MandelbrotPerturbationRenderBackend());
            var gpu = new GpuMandelbrotRenderBackend(runtime,
                    new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(), new MandelbrotPerturbationRenderBackend()));
            var measured = new MeasuredBackend(cpu, gpu);
            try (var service = new FractalRenderService(measured, runtime); var aaService = new InteractiveAntialiasService()) {
                int warmup = Integer.getInteger("fractal.benchmark.warmup", 3);
                int samples = Integer.getInteger("fractal.benchmark.samples", 10);
                if (warmup < 1 || samples < 1) throw new IllegalArgumentException("Positive warmup and samples required");
                String metadata = "# " + runtime.capabilityReport() + "\n# Java " + System.getProperty("java.version")
                        + "; " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                        + "; processors=" + Runtime.getRuntime().availableProcessors()
                        + "; maxHeap=" + Runtime.getRuntime().maxMemory()
                        + "; outputScale=" + stage.getOutputScaleX() + "; time=" + java.time.Instant.now() + "\n";
                System.out.print(metadata);
                for (String size : System.getProperty("fractal.benchmark.sizes", "1512x982,3024x1964").split(",")) {
                    String[] dimensions = size.split("x");
                    int w = Integer.parseInt(dimensions[0]), h = Integer.parseInt(dimensions[1]);
                    if (w < 2 || h < 2 || (long) w * h > 8_000_000) throw new IllegalArgumentException("Size outside benchmark bounds");
                    configureSurface(w, h);
                    for (String name : System.getProperty("fractal.benchmark.scenes", "overview,exterior,seahorse,overview-aa,overview-refined").split(",")) {
                        Viewport viewport = switch (name) {
                            case "overview", "overview-aa", "overview-refined" -> new Viewport(-0.75, 0, 2.4);
                            case "exterior" -> new Viewport(1, 1, 0.2);
                            case "seahorse" -> new Viewport(-0.743643887037151, 0.13182590420533, 0.024);
                            default -> throw new IllegalArgumentException("Unknown scene " + name);
                        };
                        boolean aa = name.endsWith("-aa") || name.endsWith("-refined");
                        var mode = name.endsWith("-refined") ? InteractiveRenderMode.REFINED : InteractiveRenderMode.FAST;
                        var scene = new FractalScene(FractalPreset.MANDELBROT, viewport, new IterationSettings(300, 0),
                                new ColoringSettings(PalettePreset.ICE), new AntialiasSettings(SamplingPattern.REGULAR, mode));
                        for (int i = -warmup - 1; i < samples; i++) {
                            String phase = i == -warmup - 1 ? "cold" : i < 0 ? "warmup" : "sample";
                            Result[] pair = new Result[2];
                            for (int order = 0; order < (cpuOnly ? 1 : 2); order++) {
                                int backend = cpuOnly ? 0 : (i + order) & 1;
                                pair[backend] = measure(service, aaService, measured, scene, w, h, aa, backend == 1);
                            }
                            double error = cpuOnly ? 0 : verify(pair[0].frame, pair[1].frame);
                            for (int b = 0; b < (cpuOnly ? 1 : 2); b++) rows.add(pair[b].csv(name, w, h, aa, b == 1, phase, i, error));
                            Files.createDirectories(output.toAbsolutePath().getParent());
                            Files.writeString(output, metadata + HEADER + "\n" + String.join("\n", rows) + "\n");
                            if (cpuOnly) System.out.printf(Locale.ROOT, "%s %s %s %d: CPU %.1f ms%n",
                                    size, name, phase, i, pair[0].total / 1e6);
                            else System.out.printf(Locale.ROOT, "%s %s %s %d: CPU %.1f ms; GPU %.1f ms; certified %d; recovered %d; error %.8g%n",
                                    size, name, phase, i, pair[0].total / 1e6, pair[1].total / 1e6,
                                    pair[1].stats.certifiedPixels(), pair[1].stats.recoveredPixels(), error);
                        }
                    }
                }
                System.out.println("Saved " + output.toAbsolutePath());
            }
        } catch (Throwable error) {
            failure = error;
            error.printStackTrace();
        } finally { Platform.runLater(Platform::exit); }
    }
    private void configureSurface(int w, int h) throws Exception {
        fx(() -> {
            double scale = stage.getOutputScaleX();
            if (w % scale != 0 || h % scale != 0) throw new IllegalArgumentException("Buffer must fit integral logical dimensions");
            surface.setOutputScale(scale, scale);
            surface.resizeBuffer((int) (w / scale), (int) (h / scale));
            if (surface.renderWidth() != w || surface.renderHeight() != h) throw new IllegalStateException("Wrong Retina buffer size");
            var bounds = Screen.getPrimary().getVisualBounds();
            stage.setWidth(Math.min(w / scale + 20, bounds.getWidth() - 80));
            stage.setHeight(Math.min(h / scale + 50, bounds.getHeight() - 100));
            System.out.printf(Locale.ROOT, "Buffer %dx%d; window %.0fx%.0f logical; scale %.1f%n", w, h, stage.getWidth(), stage.getHeight(), scale);
        });
    }
    private Result measure(FractalRenderService service, InteractiveAntialiasService aaService, MeasuredBackend backend,
                           FractalScene scene, int w, int h, boolean aa, boolean gpu) throws Exception {
        var mode = scene.antialiasing().renderMode();
        boolean refined = mode == InteractiveRenderMode.REFINED;
        backend.useGpu = gpu;
        backend.first.set(0);
        backend.finished = 0;
        var job = new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE), scene.viewport(), w, h, 300,
                RenderPriority.center(), Optional.empty(), gpu ? SampleAccuracy.CERTIFIED_FP32 : SampleAccuracy.CPU_REFERENCE);
        long before = heap(), gcBefore = gcCount(), gcTimeBefore = gcTime();
        ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).forEach(p -> p.resetPeakUsage());
        long started = System.nanoTime();
        // Frame allocation, FX staging setup and queueing belong to end-to-end time.
        var frame = RenderFrame.create(job);
        var result = new Result(frame);
        var completed = new CompletableFuture<Void>();
        var pulseDone = new CompletableFuture<Long>();
        Platform.runLater(() -> {
            try {
                surface.beginProgressiveRender(frame, null, mode);
                service.render(frame, Platform::runLater, progress -> {
                    try {
                        long t = System.nanoTime();
                        surface.displayProgress(progress, coloring, mode);
                        result.publication += System.nanoTime() - t;
                        if (!refined && result.firstPublish == 0) result.firstPublish = System.nanoTime() - started;
                    } catch (Throwable e) { completed.completeExceptionally(e); }
                }, done -> {
                    try {
                        long t = System.nanoTime();
                        if (refined) surface.beginRefinedRender(done);
                        else surface.completeProgressiveRender(done, scene);
                        result.publication += System.nanoTime() - t;
                        result.base = System.nanoTime() - started;
                        Runnable finish = () -> {
                            if (refined) {
                                long p = System.nanoTime();
                                surface.completeProgressiveRender(done, scene);
                                result.publication += System.nanoTime() - p;
                            }
                            result.total = System.nanoTime() - started;
                            nextPulse = pulseDone;
                            Platform.requestNextPulse();
                            completed.complete(null);
                        };
                        if (aa) aaService.refine(done, coloring, SamplingPattern.REGULAR, RefinedPixelSnapshot.empty(w, h),
                                Platform::runLater, (region, pixels) -> {
                                    try {
                                        long p = System.nanoTime();
                                        if (refined) surface.displayRefinedTile(done, region, pixels);
                                        else surface.applyAntialiasing(done, region, pixels);
                                        result.publication += System.nanoTime() - p;
                                        if (result.firstPublish == 0) result.firstPublish = System.nanoTime() - started;
                                    } catch (Throwable e) { completed.completeExceptionally(e); }
                                }, finish, completed::completeExceptionally);
                        else finish.run();
                    } catch (Throwable e) { completed.completeExceptionally(e); }
                }, completed::completeExceptionally);
            } catch (Throwable e) { completed.completeExceptionally(e); }
        });
        completed.get(180, TimeUnit.SECONDS);
        result.nextPulse = pulseDone.get(15, TimeUnit.SECONDS) - (started + result.total);
        result.firstRegion = backend.first.get() - started;
        result.calculation = backend.finished - started;
        result.stats = gpu ? backend.gpu.lastStats() : new GpuMandelbrotRenderBackend.CalculationStats(0, 0, 0, false);
        result.profile = gpu ? backend.gpu.lastProfile() : GpuMandelbrotRenderBackend.Profile.empty(0, 0);
        result.aaProfile = aa ? aaService.lastProfile() : InteractiveAntialiasService.Profile.EMPTY;
        if (gpu && (result.stats.cpuFallback() || result.stats.dispatchedPixels() != (long) w * h)) {
            throw new IllegalStateException("Benchmark requires actual GPU dispatch: " + result.stats);
        }
        result.heapBefore = before; result.heapAfter = heap();
        result.heapPeak = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP)
                .mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        result.gcCount = gcCount() - gcBefore; result.gcTime = gcTime() - gcTimeBefore;
        return result;
    }
    private static double verify(RenderFrame cpu, RenderFrame gpu) {
        if (!cpu.isComplete() || !gpu.isComplete()) throw new IllegalStateException("Incomplete frame");
        var a = cpu.samplePlane(); var b = gpu.samplePlane();
        double max = 0;
        for (int i = 0; i < a.size(); i++) {
            double error = Math.abs(a.smoothIterations(i) - b.smoothIterations(i));
            if (a.iterations(i) != b.iterations(i) || a.escaped(i) != b.escaped(i) || !Double.isFinite(error) || error > 0.01) {
                throw new IllegalStateException("Conformance failed at pixel " + i + ": " + error);
            }
            max = Math.max(max, error);
        }
        return max;
    }
    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { action.run(); done.complete(null); } catch (Throwable e) { done.completeExceptionally(e); } });
        done.get(15, TimeUnit.SECONDS);
    }
    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum(); }
    private static long gcTime() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum(); }

    private static final class MeasuredBackend implements RenderBackend {
        private final RenderBackend cpu;
        private final GpuMandelbrotRenderBackend gpu;
        private volatile boolean useGpu;
        private final AtomicLong first = new AtomicLong();
        private volatile long finished;
        MeasuredBackend(RenderBackend cpu, GpuMandelbrotRenderBackend gpu) { this.cpu = cpu; this.gpu = gpu; }
        @Override public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled, Consumer<RenderRegion> progress,
                                            Consumer<TileTimingStats> timing) throws InterruptedException {
            var result = (useGpu ? gpu : cpu).render(frame, cancelled, region -> {
                first.compareAndSet(0, System.nanoTime());
                progress.accept(region);
            }, timing);
            finished = System.nanoTime();
            return result;
        }
        @Override public void close() { gpu.close(); cpu.close(); }
    }
    private static final class Result {
        final RenderFrame frame;
        long firstRegion, firstPublish, calculation, base, total, publication, nextPulse;
        long heapBefore, heapAfter, heapPeak, gcCount, gcTime;
        GpuMandelbrotRenderBackend.CalculationStats stats;
        GpuMandelbrotRenderBackend.Profile profile;
        InteractiveAntialiasService.Profile aaProfile;
        Result(RenderFrame frame) { this.frame = frame; }
        String csv(String scene, int w, int h, boolean aa, boolean gpu, String phase, int iteration, double error) {
            var values = new ArrayList<String>(List.of(scene, "" + w, "" + h, "" + aa, gpu ? "GPU" : "CPU", phase,
                    "" + iteration, "" + Boolean.getBoolean("fractal.gpu.mandelbrot.profile"),
                    "" + Boolean.getBoolean("fractal.aa.profile")));
            for (long n : new long[]{firstRegion, firstPublish, calculation, base, total, publication, nextPulse}) values.add(ms(n));
            values.add("" + stats.certifiedPixels()); values.add("" + stats.recoveredPixels()); values.add("" + stats.cpuFallback());
            for (long n : new long[]{profile.packNanos(), profile.uploadNanos(), profile.dispatchNanos(), profile.readbackNanos(),
                    profile.kernelNanos(), profile.recoverPublishNanos(), profile.certificationNanos(),
                    profile.recoveryNanos(), profile.publicationNanos()}) values.add(n < 0 ? "" : ms(n));
            for (long n : new long[]{profile.batches(), profile.nativeBytes(), profile.stagingBytes(),
                    profile.hostWorkers(), profile.regionSize()}) values.add("" + n);
            for (long n : new long[]{aaProfile.totalNanos(), aaProfile.baseColorNanos(), aaProfile.candidateNanos(),
                    aaProfile.candidateCriticalPathNanos(), aaProfile.samplingNanos(),
                    aaProfile.cacheColorNanos()}) values.add(ms(n));
            for (long n : new long[]{aaProfile.testedPixels(), aaProfile.candidates(), aaProfile.samples(),
                    heapBefore, heapAfter, heapPeak, gcCount, gcTime}) values.add("" + n);
            values.add(Double.toString(error));
            return String.join(",", values);
        }
        private static String ms(long n) { return String.format(Locale.ROOT, "%.6f", n / 1e6); }
    }
}
