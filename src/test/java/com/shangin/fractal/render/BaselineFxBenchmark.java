package com.shangin.fractal.render;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.scene.*;
import com.shangin.fractal.ui.FractalSurface;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** CPU fixture publication through production JavaFX buffers. No input-handler or scanout timer. */
public final class BaselineFxBenchmark {
    private BaselineFxBenchmark() {}
    static final String HEADER = "fixture,step,width,height,requested_mode,effective_mode,phase,run,request,backend,cap,reused_pixels,reuse_source,fx_dispatch_ms,plan_ms,first_backend_region_ms,backend_ms,base_complete_ms,first_base_publish_ms,base_full_publish_ms,aa_prepare_ms,aa_ms,first_aa_publish_ms,first_visible_publish_ms,full_publish_ms,first_post_layout_ms,full_post_layout_ms,cancel_request_ms,cancel_tail_ms,base_fx_work_ms,aa_fx_work_ms,complete,sample_hash,argb_hash,heap_before_bytes,heap_after_bytes,gc_count_delta,gc_ms_delta";

    public static void main(String[] args) throws Exception {
        int warmups = Integer.getInteger("baseline.warmups", 1), runs = Integer.getInteger("baseline.runs", 3);
        if (warmups < 0 || runs < 1) throw new IllegalArgumentException("Invalid sample counts");
        var fixtures = BaselineBenchmark.selectedFixtures();
        List<InteractiveRenderMode> modes = Arrays.stream(System.getProperty("baseline.fx.modes", "FAST,REFINED").split(","))
                .map(InteractiveRenderMode::valueOf).toList();
        if (new HashSet<>(modes).size() != modes.size()) throw new IllegalArgumentException("Duplicate mode");
        for (var fixture : fixtures) for (var step : fixture.steps()) {
            if (step.job().width() % 2 != 0 || step.job().height() % 2 != 0) {
                throw new IllegalArgumentException("Production FX buffers require even render dimensions");
            }
        }
        Path output = Path.of(System.getProperty("baseline.output", "target/baseline-fx-" + System.currentTimeMillis()));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output);
        try (var manifest = Files.newBufferedWriter(output.resolve("manifest.csv"), StandardOpenOption.CREATE_NEW)) {
            manifest.write(BaselineFixtures.HEADER + "\n");
            for (var fixture : fixtures) for (var step : fixture.steps()) manifest.write(step.csv(fixture.id()) + "\n");
        }
        System.setProperty("fractal.gpu.enabled", "false");
        Path lockPath = Path.of(System.getProperty("java.io.tmpdir"), "fractalui-baseline-benchmark.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another baseline benchmark is running");
            CompletableFuture<Void> started = new CompletableFuture<>();
            Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
            started.get(15, TimeUnit.SECONDS);
            try (Ui ui = fx(Ui::new)) {
                Files.writeString(output.resolve("environment.txt"), environment(warmups, runs, modes, ui), StandardOpenOption.CREATE_NEW);
                try (var csv = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv"), StandardOpenOption.CREATE_NEW))) {
                    csv.println(HEADER);
                    for (var fixture : fixtures) for (var mode : modes) {
                        List<Result> last = new ArrayList<>();
                        var backend = new TimedBackend(); // Owned and closed by the render service.
                        try (var service = new FractalRenderService(backend);
                             var aa = new InteractiveAntialiasService()) {
                            for (int run = -warmups - 1; run < runs; run++) {
                                String phase = run == -warmups - 1 ? "cold_fixture" : run < 0 ? "warmup" : "sample";
                                fx(() -> { ui.reset(); return null; });
                                RenderFrame active = null, retained = null;
                                last.clear();
                                for (var step : fixture.steps()) {
                                    Result result = measure(ui, step, mode, service, backend, aa, active, retained);
                                    csv.println(result.csv(fixture.id(), phase, run));
                                    csv.flush();
                                    retained = active; active = result.frame;
                                    last.add(result);
                                }
                            }
                        }
                        // Separate control work after all timed repetitions of this fixture/mode.
                        for (Result result : last) verify(result);
                        System.out.println("Verified " + fixture.id() + " " + mode.name() + " " + fixture.steps().getFirst().job().width()
                                + "x" + fixture.steps().getFirst().job().height());
                    }
                    if (csv.checkError()) throw new IOException("Failed to write FX baseline");
                }
                Files.writeString(output.resolve("SUCCESS"), "All last-sequence samples and ARGB pixels match same-grid CPU controls.\n",
                        StandardOpenOption.CREATE_NEW);
            } finally { Platform.exit(); }
        }
    }

    private static String environment(int warmups, int runs, List<InteractiveRenderMode> modes, Ui ui) throws Exception {
        return "matrix=" + BaselineFixtures.VERSION + "\nstarted=" + java.time.Instant.now()
                + "\nlabel=" + System.getProperty("baseline.label", "current")
                + "\nrevision=" + System.getProperty("baseline.revision", "unspecified")
                + "\njava=" + System.getProperty("java.runtime.version") + "\nvm=" + System.getProperty("java.vm.name")
                + "\njavafx=" + System.getProperty("javafx.runtime.version")
                + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "\narch=" + System.getProperty("os.arch") + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
                + "\nworkers_per_pool=" + Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
                + "\nmax_heap_bytes=" + Runtime.getRuntime().maxMemory()
                + "\naa_cache_capacity_bytes=" + AntialiasSampleCache.DEFAULT_MAX_BYTES
                + "\nreference_cache_capacity_bytes=" + MandelbrotPerturbationRenderBackend.DEFAULT_REFERENCE_CACHE_BYTES
                + "\nvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                + "\naa_profile=" + Boolean.getBoolean("fractal.aa.profile")
                + "\nrequested_pipeline=" + System.getProperty("prism.order", "default")
                + "\noutput_scale=" + fx(() -> ui.stage.getOutputScaleX() + "x" + ui.stage.getOutputScaleY())
                + "\nmodes=" + String.join(",", modes.stream().map(Enum::name).toList())
                + "\nwarmups=" + warmups + "\nsamples=" + runs
                + "\nrequest_origin=driver_before_fx_queue\ninput_handlers=unmeasured\nphysical_scanout=unmeasured"
                + "\nthermal=unmeasured\ngpu_calculation=false\nconformance=last_sequence_each_fixture_and_mode\n";
    }

    static final class Ui implements AutoCloseable {
        final Stage stage = new Stage();
        final StackPane root = new StackPane();
        FractalSurface surface;
        Result current;
        long sequence;
        Ui() {
            Scene scene = new Scene(root, 480, 270);
            scene.addPostLayoutPulseListener(() -> {
                Result r = current;
                if (r == null) return;
                long now = System.nanoTime();
                if (r.firstVisible >= 0 && r.firstPostLayout < 0) r.firstPostLayout = now;
                if (r.fullPublish >= 0 && r.fullPostLayout < 0) {
                    r.fullPostLayout = now;
                    r.pulsed.complete(null);
                }
            });
            stage.setTitle("FractalUI — roadmap 9.1 CPU publication baseline");
            stage.setScene(scene);
            reset();
            stage.show();
        }
        void reset() {
            current = null;
            surface = new FractalSurface();
            root.getChildren().setAll(surface);
        }
        void configure(RenderJob job) {
            double sx = stage.getOutputScaleX(), sy = stage.getOutputScaleY();
            if (job.width() % sx != 0 || job.height() % sy != 0) throw new IllegalArgumentException("Non-integral logical target");
            surface.setOutputScale(sx, sy);
            surface.resizeBuffer((int) (job.width() / sx), (int) (job.height() / sy));
            if (surface.renderWidth() != job.width() || surface.renderHeight() != job.height()) {
                throw new IllegalStateException("FX target differs from exact matrix dimensions");
            }
            var bounds = Screen.getPrimary().getVisualBounds();
            stage.setWidth(Math.min(job.width() / sx + 20, bounds.getWidth() - 80));
            stage.setHeight(Math.min(job.height() / sy + 50, bounds.getHeight() - 100));
        }
        @Override public void close() throws Exception { fx(() -> { current = null; stage.close(); return null; }); }
    }

    static final class Result {
        final BaselineFixtures.Step step;
        final InteractiveRenderMode requested;
        InteractiveRenderMode effective;
        RenderFrame frame;
        int[] pixels;
        String backend, reuseSource;
        int reused;
        long request, start, fxStart, planned, backendStart = -1, backendEnd = -1;
        final AtomicLong firstRegion = new AtomicLong(-1), cancel = new AtomicLong(-1);
        long baseComplete = -1, firstBase = -1, baseFull = -1, aaPrepare = -1, aaStart = -1, aaEnd = -1;
        long firstAa = -1, firstVisible = -1, fullPublish = -1, firstPostLayout = -1, fullPostLayout = -1;
        long baseFxWork, aaFxWork;
        long heapBefore, heapAfter, gcBefore, gcAfter, gcMsBefore, gcMsAfter;
        final CompletableFuture<Void> complete = new CompletableFuture<>(), exited = new CompletableFuture<>(), pulsed = new CompletableFuture<>();
        Result(BaselineFixtures.Step step, InteractiveRenderMode mode) { this.step = step; requested = mode; }
        void published(String stage, long now) {
            if (stage.equals("base_publish") && firstBase < 0) firstBase = now;
            if (stage.equals("aa_publish") && firstAa < 0) firstAa = now;
            if ((stage.equals("base_publish") || stage.equals("aa_publish") || stage.equals("frame_promoted"))
                    && firstVisible < 0) firstVisible = now;
        }
        String csv(String fixture, String phase, int run) {
            var fields = new ArrayList<>(List.of(fixture, step.name(), "" + step.job().width(), "" + step.job().height(),
                    requested.name(), effective.name(), phase, "" + run, "" + request, backend,
                    "" + step.job().maxIterations(), "" + reused, reuseSource));
            for (long value : new long[]{delta(fxStart, start), delta(planned, fxStart), delta(firstRegion.get(), start),
                    delta(backendEnd, backendStart), delta(baseComplete, start), delta(firstBase, start), delta(baseFull, start),
                    aaPrepare, delta(aaEnd, aaStart), delta(firstAa, start), delta(firstVisible, start), delta(fullPublish, start),
                    delta(firstPostLayout, start), delta(fullPostLayout, start), delta(cancel.get(), start),
                    cancel.get() < 0 ? -1 : delta(backendEnd, cancel.get()), baseFxWork, aaFxWork}) {
                fields.add(value < 0 ? "-1" : String.format(Locale.ROOT, "%.6f", value / 1e6));
            }
            fields.add("" + frame.isComplete()); fields.add("" + BaselineBenchmark.sampleHash(frame));
            fields.add("" + (pixels == null ? 0 : Arrays.hashCode(pixels)));
            fields.add("" + heapBefore); fields.add("" + heapAfter);
            fields.add("" + delta(gcAfter, gcBefore)); fields.add("" + delta(gcMsAfter, gcMsBefore));
            return String.join(",", fields);
        }
    }

    static long delta(long end, long start) { return end < 0 || start < 0 ? -1 : end - start; }

    static Result measure(Ui ui, BaselineFixtures.Step step, InteractiveRenderMode mode,
                          FractalRenderService service, TimedBackend backend, InteractiveAntialiasService aa,
                          RenderFrame active, RenderFrame retained) throws Exception {
        fx(() -> { ui.configure(step.job()); return null; }); // Target/window configuration is outside the request timer.
        Result r = new Result(step, mode);
        r.heapBefore = BaselineBenchmark.usedHeap();
        r.gcBefore = BaselineBenchmark.gcCount(); r.gcMsBefore = BaselineBenchmark.gcMillis();
        r.start = System.nanoTime();
        fx(() -> {
            r.fxStart = System.nanoTime();
            r.request = ++ui.sequence;
            ui.current = r;
            FrameReuseSelection selection = step.action() == BaselineFixtures.Action.REUSE
                    ? new FrameReusePlanner().plan(active, retained, step.job()) : null;
            FrameReuseResult reuse = selection == null ? FrameReuseResult.fresh(RenderFrame.create(step.job())) : selection.result();
            r.frame = reuse.frame(); r.reused = reuse.reusedPixels();
            RenderFrame source = selection == null ? null : selection.sourceFrame();
            r.reuseSource = source == null ? "none" : source == active ? "active" : "retained";
            r.planned = System.nanoTime();
            boolean deep = backend.delegate.select(step.job()) instanceof MandelbrotPerturbationRenderBackend;
            // Production controller presents deep renders and base-only work through the Fast path.
            r.effective = step.aa() == BaselineFixtures.Aa.NONE || deep ? InteractiveRenderMode.FAST : mode;
            backend.current = r;
            var settings = new ColoringSettings(PalettePreset.ICE, PalettePreset.ICE.stops(), ColoringSettings.DEFAULT_COLOR_SCALE,
                    0, step.colors() == BaselineFixtures.Colors.HISTOGRAM, step.job().formula().orbitTrap());
            var scene = new FractalScene(step.job().formula().preset(), step.job().viewport(),
                    new IterationSettings(step.job().maxIterations(), 0), settings, new AntialiasSettings(step.pattern(), r.effective));
            ColoringStrategy initial = settings.createStrategy();
            var surface = ui.surface;
            surface.latency().arm(event -> { if (ui.current == r) r.published(event.stage(), event.nanos()); });
            surface.latency().input("fixture_request");
            surface.latency().renderStarted();
            surface.beginProgressiveRender(r.frame, source, r.effective);
            if (reuse.reused()) surface.reuseDisplayedPixels(source, reuse.shift().orElseThrow());
            if (r.effective == InteractiveRenderMode.FAST) surface.displayReadyPixels(r.frame, initial);
            service.render(r.frame, Platform::runLater, progress -> guard(ui, r, () -> {
                long start = System.nanoTime();
                surface.displayProgress(progress, initial, r.effective);
                r.baseFxWork += System.nanoTime() - start;
            }), frame -> guard(ui, r, () -> {
                r.baseComplete = System.nanoTime();
                long start = System.nanoTime();
                ColoringStrategy coloring = step.coloring(frame);
                boolean refined = r.effective == InteractiveRenderMode.REFINED;
                if (refined) surface.beginRefinedRender(frame);
                else {
                    // Finish the frame-dependent histogram mapping or a fully reused frame with no progress callbacks.
                    if (step.colors() == BaselineFixtures.Colors.HISTOGRAM || reuse.reusedPixels() == frame.samplePlane().size()) {
                        surface.displayReadyPixels(frame, coloring);
                    }
                    surface.completeProgressiveRender(frame, scene);
                    r.baseFull = System.nanoTime();
                }
                r.baseFxWork += System.nanoTime() - start;
                Runnable finish = () -> guard(ui, r, () -> {
                    long p = System.nanoTime();
                    if (refined) {
                        surface.completeProgressiveRender(frame, scene);
                        r.aaFxWork += System.nanoTime() - p;
                    }
                    r.fullPublish = System.nanoTime();
                    Platform.requestNextPulse();
                    r.complete.complete(null);
                });
                if (step.aa() == BaselineFixtures.Aa.NONE) finish.run();
                else {
                    Runnable measuredAa = () -> {
                        r.aaStart = System.nanoTime();
                        refine(aa, step, frame, coloring, deep, (region, colors) -> guard(ui, r, () -> {
                            long p = System.nanoTime();
                            if (refined) surface.displayRefinedTile(frame, region, colors);
                            else surface.applyAntialiasing(frame, region, colors);
                            r.aaFxWork += System.nanoTime() - p;
                        }), () -> { r.aaEnd = System.nanoTime(); finish.run(); }, r.complete::completeExceptionally);
                    };
                    if (step.aa() == BaselineFixtures.Aa.SAME_FRAME) {
                        long preparation = System.nanoTime();
                        refine(aa, step, frame, coloring, deep, (region, colors) -> {}, () -> {
                            r.aaPrepare = System.nanoTime() - preparation;
                            measuredAa.run();
                        }, r.complete::completeExceptionally);
                    } else measuredAa.run();
                }
            }), r.complete::completeExceptionally);
            return null;
        });
        if (step.action() == BaselineFixtures.Action.CANCEL_FIRST_REGION) {
            r.exited.get(180, TimeUnit.SECONDS);
            fx(() -> { service.cancelCurrent(); return null; });
            if (r.cancel.get() < 0) throw new IllegalStateException("Cancellation trigger was not reached");
        } else {
            r.complete.get(180, TimeUnit.SECONDS);
            r.pulsed.get(15, TimeUnit.SECONDS);
            r.pixels = fx(() -> ui.surface.refinedPixelSnapshot(r.frame).colors());
        }
        fx(() -> { ui.surface.latency().stop(); ui.current = null; return null; });
        r.heapAfter = BaselineBenchmark.usedHeap();
        r.gcAfter = BaselineBenchmark.gcCount(); r.gcMsAfter = BaselineBenchmark.gcMillis();
        return r;
    }

    private static void guard(Ui ui, Result r, Runnable action) {
        if (ui.current != r) return;
        try { action.run(); } catch (Throwable error) { r.complete.completeExceptionally(error); }
    }

    private static void refine(InteractiveAntialiasService aa, BaselineFixtures.Step step, RenderFrame frame,
                               ColoringStrategy coloring, boolean deep, BiConsumer<RenderRegion, int[]> publish,
                               Runnable success, Consumer<Throwable> failure) {
        var empty = RefinedPixelSnapshot.empty(step.job().width(), step.job().height());
        if (deep) aa.refineDeep(frame, coloring, step.pattern(), empty, Platform::runLater, publish, success, failure);
        else aa.refine(frame, coloring, step.pattern(), empty, Platform::runLater, publish, success, failure);
    }

    static final class TimedBackend implements RenderBackend {
        final PrecisionSelectingRenderBackend delegate = BaselineBenchmark.newBackend();
        volatile Result current;
        @Override public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled, Consumer<RenderRegion> progress,
                                            Consumer<TileTimingStats> timings) throws InterruptedException {
            Result r = current;
            r.backend = delegate.select(frame.job()).getClass().getSimpleName();
            r.backendStart = System.nanoTime();
            try {
                var result = delegate.render(frame, () -> cancelled.getAsBoolean() || r.cancel.get() >= 0, region -> {
                    long now = System.nanoTime();
                    r.firstRegion.compareAndSet(-1, now);
                    if (r.step.action() == BaselineFixtures.Action.CANCEL_FIRST_REGION) r.cancel.compareAndSet(-1, now);
                    else progress.accept(region);
                }, timings);
                // Do not let an intentionally incomplete frame enter the service's success path.
                if (r.cancel.get() >= 0) throw new InterruptedException("Fixture cooperative cancellation");
                return result;
            } catch (RuntimeException error) {
                r.exited.completeExceptionally(error);
                throw error;
            } finally {
                r.backendEnd = System.nanoTime();
                r.exited.complete(null);
            }
        }
        @Override public void close() { delegate.close(); }
    }

    static void verify(Result r) throws Exception {
        if (r.cancel.get() >= 0) {
            if (r.pixels != null || r.fullPublish >= 0) throw new IllegalStateException("Cancelled work was promoted");
            return;
        }
        try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
            var control = RenderFrame.create(r.frame.job(), r.frame.renderGrid());
            backend.render(control, () -> false, ignored -> {}, null);
            assertSamples(control, r.frame);
            int[] expected = new int[control.samplePlane().size()];
            var coloring = r.step.coloring(control);
            new FractalColorizer().color(control.samplePlane(), IntBuffer.wrap(expected), coloring);
            if (r.step.aa() != BaselineFixtures.Aa.NONE) {
                boolean deep = backend.select(control.job()) instanceof MandelbrotPerturbationRenderBackend;
                if (r.step.aa() == BaselineFixtures.Aa.SAME_FRAME) BaselineBenchmark.refine(r.step, aa, control, coloring, expected, deep);
                BaselineBenchmark.refine(r.step, aa, control, coloring, expected, deep);
            }
            int mismatch = Arrays.mismatch(expected, r.pixels);
            if (mismatch >= 0) throw new IllegalStateException("FX/control ARGB mismatch at " + mismatch + " for " + r.step.name());
        }
    }

    static void assertSamples(RenderFrame control, RenderFrame actual) {
        if (!control.isComplete() || !actual.isComplete()) throw new IllegalStateException("Incomplete conformance input");
        var a = control.samplePlane(); var b = actual.samplePlane();
        if (a.size() != b.size()) throw new IllegalStateException("Sample size mismatch");
        for (int i = 0; i < a.size(); i++) {
            if (a.iterations(i) != b.iterations(i) || a.escaped(i) != b.escaped(i)
                    || Double.doubleToLongBits(a.smoothIterations(i)) != Double.doubleToLongBits(b.smoothIterations(i))
                    || Double.doubleToLongBits(a.orbitTrapDistance(i)) != Double.doubleToLongBits(b.orbitTrapDistance(i))) {
                throw new IllegalStateException("FX/control sample mismatch at " + i);
            }
        }
    }

    static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(180, TimeUnit.SECONDS);
    }
}
