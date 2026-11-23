package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.HistogramPaletteColoring;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.gpu.PaletteRecolorBackend;
import com.shangin.fractal.gpu.PaletteRecolorTiming;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.WidePreviewSettings;
import com.shangin.fractal.ui.FractalSurface;
import javafx.application.Platform;

import java.util.Objects;
import java.math.BigDecimal;
import java.util.function.BiConsumer;
import java.util.function.ToIntBiFunction;
import java.util.function.Consumer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

/**
 * Coordinates render requests between the camera-facing UI and the background
 * rendering pipeline, including iteration policy, frame reuse, and coloring.
 */
public final class FractalRenderController implements AutoCloseable {

    private final FractalSurface surface;
    private final WidePreviewService widePreview = new WidePreviewService(Platform::runLater);
    private WidePreviewSettings widePreviewSettings = WidePreviewSettings.DEFAULT;
    private FractalScene previewScene;
    private RenderJob previewRequest;
    private boolean visibleBusy;
    private boolean widePreviewColorCycling;
    private boolean closed;
    private final FractalRenderService renderService;
    private final InteractiveAntialiasService antialiasService;
    private final PaletteRecolorBackend paletteRecolorBackend;
    private final RecolorOperation recolorOperation;

    @FunctionalInterface
    public interface RecolorOperation {
        PaletteRecolorTiming apply(InteractiveAntialiasService antialiasService,
                                   RenderFrame frame, ColoringStrategy coloring,
                                   int[] colors, PaletteRecolorBackend backend)
                throws InterruptedException;
    }

    /** AA colors that Base only took off the visible frame, valid for the same frame and coloring. */
    private record HiddenRefinement(RenderFrame frame, ColoringSettings coloring, RefinedPixelSnapshot pixels) {}

    private FormulaDefinition formulaDefinition;
    private HiddenRefinement hiddenRefinement;
    /** A visible frame showing retained AA colors whose palette-independent samples were not retained. */
    private record UnsampledAntialias(RenderFrame frame, boolean deepZoom) {}
    private UnsampledAntialias unsampledAntialias;
    /**
     * A palette change to repeat once the pass still publishing the visible frame finishes:
     * one that frame did not show yet, or one whose AA must be sampled again after the pass.
     * It is repeated only while it is still the latest request of its recolor epoch.
     *
     * @param onApplied runs once it is published, if it was not published before
     */
    private record DeferredRecoloring(ColoringSettings settings, Runnable onApplied, long epoch, long request) {}
    private DeferredRecoloring deferredRecoloring;
    /** Whether {@link #activeFrame} uses deep-zoom AA. */
    private boolean activeDeepZoom;
    private long resampleGeneration = -1;
    private RenderFrame activeFrame;
    private RenderFrame retainedFrame;
    private boolean initialFramePending = true;
    /**
     * Histogram mapping of the last completed visible frame of {@link #formulaDefinition}:
     * pixels shown before the next frame completes use its distribution, see
     * {@link ColoringSettings#createProgressiveStrategy}. FX thread only.
     */
    private HistogramPaletteColoring completedHistogram;
    /** Keeps fresh frame allocation off the FX thread between render start and the preview. */
    private final SpareFrameStorage spareFrameStorage = new SpareFrameStorage();
    private final FrameReusePlanner frameReusePlanner = new FrameReusePlanner(spareFrameStorage::create);
    private final RenderFrameCache frameCache;
    private final RenderActivityTracker renderActivity = new RenderActivityTracker();
    private final ExecutorService recolorExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "fractal-recolor");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());
    /** Changes only when recoloring must be invalidated (navigation/new render). */
    private final AtomicLong recolorEpoch = new AtomicLong();
    /** Monotonic animation frame id; newer requests do not invalidate work in flight. */
    private final AtomicLong recolorSequence = new AtomicLong();
    private final AtomicLong publishedRecolorSequence = new AtomicLong();
    private final RecolorFailureGate recolorFailureGate = new RecolorFailureGate();
    private final Semaphore recolorBufferSlots = new Semaphore(2);
    private final ConcurrentLinkedDeque<int[]> recolorBuffers = new ConcurrentLinkedDeque<>();
    private final AtomicReference<PaletteRecolorTiming> lastPaletteRecolorTiming =
            new AtomicReference<>();

    /** Julia GPU mode: approximate GPU frames replace the CPU render for supported Julia scenes. */
    private final JuliaGpuRenderer juliaGpu;
    private boolean juliaGpuEnabled;
    /** Receives mode changes the controller makes itself, such as after a GPU refusal. */
    private Consumer<Boolean> juliaGpuChangedHandler = ignored -> {};
    /**
     * The latest GPU scene, shown or being produced; null while the CPU path owns the
     * frame. Its coloring is the latest requested palette. FX thread only, like the rest.
     */
    private GpuRender gpuRender;
    /**
     * The job of the latest GPU render, kept apart from {@link #gpuRender} so a failure that
     * clears the GPU scene does not lose the iteration limit a resize preserved. A later
     * resize, pan or mode switch keeps it, as it keeps a CPU frame's; cleared by the CPU path.
     */
    private RenderJob gpuLimitJob;
    /** Increments for every GPU submission and invalidation. */
    private long gpuSequence;
    /** Whether the submission of {@link #gpuSequence} has not returned yet. */
    private boolean gpuInFlight;
    /** Generation completed by the next current GPU frame, or -1. */
    private long gpuGeneration = -1;
    /** Runs once the GPU frame of {@link #gpuSequence} with a palette change is shown. */
    private Runnable gpuOnApplied;
    /**
     * A palette change that arrived while a GPU frame was in flight. Only the latest is
     * kept; it is submitted with {@link #gpuRender}'s coloring once that frame returns,
     * so a slow GPU still shows every frame it finishes during palette animation.
     */
    private Runnable pendingGpuRecolor;
    /**
     * Whether the last GPU invalidation cancelled only palette work (cancelRecolor), so no
     * render follows; any scene cancellation or new submission clears it.
     */
    private boolean gpuPaletteOnlyCancel;
    /** The scene of the GPU frame on screen, with the palette it actually shows. */
    private FractalScene gpuShownScene;
    /**
     * After a GPU refusal, the palette callback of the latest requested palette, which the
     * CPU fallback shows: it runs once that render's frame is published, for its generation only.
     */
    private Runnable fallbackAppliedRequest;
    private FallbackApplied fallbackApplied;

    private record FallbackApplied(long generation, Runnable onApplied) {}

    private record GpuRender(FractalScene scene, RenderTarget target, Viewport defaultViewport,
                             RenderJob job, boolean exploringJulia, boolean requiresGpu, Runnable beforeCpuFallback) {
        GpuRender withColoring(ColoringSettings coloring) {
            // Palette work uses the same presentation as the frame it recolors and can fall back to CPU.
            return new GpuRender(scene.withColoring(coloring), target, defaultViewport, job, exploringJulia,
                    false, beforeCpuFallback);
        }

        void prepareCpuFallback() {
            if (beforeCpuFallback != null) beforeCpuFallback.run();
        }
    }

    public FractalRenderController(FractalSurface surface) {
        this(surface, new FractalRenderService());
    }

    public FractalRenderController(FractalSurface surface, FractalRenderService renderService) {
        this(surface, renderService, InteractiveAntialiasService::recolorCachedInto);
    }

    public FractalRenderController(FractalSurface surface, FractalRenderService renderService,
                                   RecolorOperation recolorOperation) {
        this(surface, renderService, recolorOperation, new RenderFrameCache(), new InteractiveAntialiasService());
    }

    /** Uses a caller-sized completed-frame cache, for example to exercise its budget. */
    public FractalRenderController(FractalSurface surface, FractalRenderService renderService,
                                   RenderFrameCache frameCache) {
        this(surface, renderService, InteractiveAntialiasService::recolorCachedInto, frameCache,
                new InteractiveAntialiasService());
    }

    /** Uses a caller-supplied recolor operation and frame cache, for example to order a recolor against a render. */
    public FractalRenderController(FractalSurface surface, FractalRenderService renderService,
                                   RecolorOperation recolorOperation, RenderFrameCache frameCache) {
        this(surface, renderService, recolorOperation, frameCache, new InteractiveAntialiasService());
    }

    /** Uses caller-sized completed-frame and AA sample caches, for example to exercise their budgets. */
    public FractalRenderController(FractalSurface surface, FractalRenderService renderService,
                                   RenderFrameCache frameCache, AntialiasSampleCache sampleCache) {
        this(surface, renderService, InteractiveAntialiasService::recolorCachedInto, frameCache,
                InteractiveAntialiasService.withSampleCache(sampleCache));
    }

    private FractalRenderController(FractalSurface surface, FractalRenderService renderService,
                                    RecolorOperation recolorOperation, RenderFrameCache frameCache,
                                    InteractiveAntialiasService antialiasService) {
        this.frameCache = Objects.requireNonNull(frameCache);
        this.antialiasService = Objects.requireNonNull(antialiasService);
        this.surface = Objects.requireNonNull(surface);
        this.renderService = Objects.requireNonNull(renderService);
        this.recolorOperation = Objects.requireNonNull(recolorOperation);
        renderService.setDiagnosticsListener(diagnostics -> surface.latency().attachDiagnostics(diagnostics.completion()));
        this.paletteRecolorBackend = renderService.paletteRecolorBackend();
        this.juliaGpu = new JuliaGpuRenderer(renderService, Platform::runLater);
    }

    /**
     * Enables the Julia GPU mode. Supported Julia scenes are then rendered only by the
     * approximate GPU preview kernel: no samples, AA, cache entries or exportable frame.
     * Unsupported scenes (deep zoom, histogram, orbit traps, unavailable GPU) stay on CPU.
     * The caller renders again to apply the change. FX thread only.
     */
    public void setJuliaGpu(boolean enabled) {
        juliaGpuEnabled = enabled;
    }

    /** Called with false when the GPU refuses the kernel and the controller turns the mode off. */
    public void setOnJuliaGpuChanged(Consumer<Boolean> handler) {
        juliaGpuChangedHandler = Objects.requireNonNull(handler);
    }

    public boolean juliaGpu() { return juliaGpuEnabled; }

    /** Whether the GPU runtime can run the Julia GPU kernel now; never waits for native work. */
    public boolean isJuliaGpuAvailable() {
        return !closed && renderService.isJuliaPreviewAvailable();
    }

    /** Whether the visible frame came from the approximate Julia GPU kernel. FX thread only. */
    public boolean showsJuliaGpuFrame() {
        return gpuRender != null && surface.hasCompletedFrame() && surface.completedRender() == null;
    }

    private Consumer<BigDecimal> zoomChangedHandler = ignored -> {};
    private Consumer<Integer> iterationsChangedHandler = ignored -> {};
    private Consumer<Boolean> deepZoomChangedHandler = ignored -> {};
    private Consumer<Double> progressHandler = ignored -> {};
    private Consumer<Throwable> errorHandler = error ->
            System.getLogger(FractalRenderController.class.getName())
                    .log(System.Logger.Level.ERROR, "Render failed", error);

    /**
     * Whether the render backends support the scene's formula, coloring and viewport at
     * the target grid, so a scene can be rejected before it replaces the current one.
     */
    public boolean supports(FractalScene scene, RenderTarget target) {
        return renderService.supports(new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(),
                target.width(), target.height(), scene.iterations().maxIterations(
                        scene.fractal().defaultViewport().scaleExact(), scene.viewport().scaleExact())));
    }

    public void cancelCurrent() {
        invalidateGpuFrames();
        widePreview.cancel();
        visibleBusy = true;
        recolorEpoch.incrementAndGet();
        renderService.cancelCurrent();
        antialiasService.cancelCurrent();
        renderActivity.cancel();
    }

    /** Invalidates palette-only work without cancelling fractal or AA rendering. */
    public void cancelRecolor() {
        recolorEpoch.incrementAndGet();
        pendingGpuRecolor = null;
        // A GPU palette frame in flight is dropped; a GPU render of a new scene continues.
        if (gpuInFlight && gpuGeneration < 0) {
            invalidateGpuFrames();
            gpuPaletteOnlyCancel = true;
        }
    }

    /** Coalesced, double-buffered recolor of base and cached AA samples. */
    public void recolor(ColoringSettings settings) {
        recolor(settings, () -> {});
    }

    public void recolor(ColoringSettings settings, Runnable onApplied) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(onApplied);
        if (gpuRender != null) {
            // An approximate GPU frame has no samples to recolor: render it again with the palette.
            gpuRender = gpuRender.withColoring(settings);
            previewScene = gpuRender.scene();
            if (gpuInFlight) {
                pendingGpuRecolor = onApplied;
            } else {
                submitGpu(gpuRender, onApplied);
            }
            return;
        }
        if (previewScene != null && !widePreviewColorCycling) {
            widePreview.cancel();
            previewScene = previewScene.withColoring(settings);
            if (previewRequest != null) {
                surface.prepareWidePreview(previewRequest, settings, previewScene.iterations(), widePreviewSettings);
            }
        }
        RenderFrame frame = activeFrame;
        if (frame == null || !frame.isComplete()) {
            return;
        }
        long epoch = recolorEpoch.get();
        long request = recolorSequence.incrementAndGet();
        ColoringStrategy coloring = settings.createStrategy(frame.samplePlane());
        rememberCompletedHistogram(coloring);
        recolorExecutor.execute(() -> {
            if (epoch != recolorEpoch.get() || Thread.currentThread().isInterrupted()) return;
            if (!recolorBufferSlots.tryAcquire()) {
                return;
            }
            int[] ready = acquireRecolorBuffer(frame.samplePlane().size());
            PaletteRecolorTiming timing;
            // Read first: samples bound during the recolor may be missing from its colors.
            long sampleBinding = antialiasService.sampleBinding();
            try {
                timing = recolorOperation.apply(
                        antialiasService, frame, coloring, ready, paletteRecolorBackend);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recolorBuffers.offerFirst(ready);
                recolorBufferSlots.release();
                return;
            } catch (RuntimeException error) {
                recolorBuffers.offerFirst(ready);
                recolorBufferSlots.release();
                Platform.runLater(() -> {
                    if (recolorFailureGate.shouldReport(epoch, recolorEpoch.get(), request,
                            recolorSequence.get(), publishedRecolorSequence.get(),
                            frame == activeFrame)) {
                        errorHandler.accept(error);
                    }
                });
                return;
            }
            long queued = System.nanoTime();
            Platform.runLater(() -> {
                try {
                    if (epoch == recolorEpoch.get()
                            && frame == activeFrame
                            && request > publishedRecolorSequence.get()) {
                        if (antialiasService.sampleBinding() != sampleBinding) {
                            // Samples bound meanwhile, such as those read for a returned frame,
                            // are not in these colors: recolor again unless a newer change will.
                            if (request == recolorSequence.get()) recolor(settings, onApplied);
                            return;
                        }
                        long presentationStarted = System.nanoTime();
                        boolean published = surface.applyRecolor(frame, ready, settings);
                        lastPaletteRecolorTiming.set(timing.withPublication(
                                presentationStarted - queued, System.nanoTime() - presentationStarted));
                        publishedRecolorSequence.set(request);
                        if (!published) {
                            // The visit still publishes this frame in its scene's palette, and
                            // AA it binds later may change what the palette needs: recolor again
                            // once it finishes, then check its AA.
                            deferredRecoloring = new DeferredRecoloring(settings, onApplied, epoch, request);
                            return;
                        }
                        // An earlier palette waiting for the visit is superseded by this one.
                        if (deferredRecoloring != null && deferredRecoloring.request() < request) {
                            deferredRecoloring = null;
                        }
                        resampleAntialiasIfNeeded(frame, settings, true, request);
                        onApplied.run();
                        if (request == recolorSequence.get()) replenishWidePreview();
                    }
                } finally {
                    recolorBuffers.offerFirst(ready);
                    recolorBufferSlots.release();
                }
            });
        });
    }

    /** Most recent palette operation, split for GPU/CPU and JavaFX presentation benchmarking. */
    public PaletteRecolorTiming lastPaletteRecolorTiming() {
        return lastPaletteRecolorTiming.get();
    }

    private int[] acquireRecolorBuffer(int size) {
        int[] buffer;
        while ((buffer = recolorBuffers.pollFirst()) != null) {
            if (buffer.length == size) {
                return buffer;
            }
        }
        return new int[size];
    }

    /** Starts a progressive render for the supplied viewport. */
    public void render(
            FractalScene scene,
            RenderTarget target,
            Viewport defaultViewport
    ) {
        render(scene, target, defaultViewport, false);
    }

    public void render(FractalScene scene, RenderTarget target,
                       Viewport defaultViewport, boolean preserveIterationLimit) {
        render(scene, target, defaultViewport, preserveIterationLimit, juliaGpuEnabled);
    }

    /** Uses the GPU for Julia map frames and leaves the last image visible on failure. */
    public void renderJuliaExplorerPoint(FractalScene scene, RenderTarget target, Viewport defaultViewport) {
        render(scene, target, defaultViewport, false, true, true, null);
    }

    /** Preserves the map frame during a main-window render and allows CPU fallback. */
    public void renderJuliaExplorerMainView(FractalScene scene, RenderTarget target, Viewport defaultViewport,
                                         boolean preserveIterationLimit, Runnable beforeCpuFallback) {
        render(scene, target, defaultViewport, preserveIterationLimit, juliaGpuEnabled, true,
                Objects.requireNonNull(beforeCpuFallback));
    }

    public boolean supportsJuliaExplorer(FractalScene scene, RenderTarget target, Viewport defaultViewport,
                                         boolean preserveIterationLimit) {
        int iterations = iterationLimit(scene, defaultViewport, FormulaDefinition.forScene(scene), preserveIterationLimit);
        return !DeepZoomRenderPolicy.isDeepZoom(scene, target)
                && juliaGpu.supports(new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(),
                        target.width(), target.height(), iterations), scene.coloring());
    }

    private int iterationLimit(FractalScene scene, Viewport defaultViewport, FormulaDefinition formula,
                               boolean preserveIterationLimit) {
        // Resizing and panning retain the pixel scale, even if the resized default viewport
        // changes the zoom policy. Reuse the limit only for the same formula.
        if (preserveIterationLimit) {
            if (activeFrame != null && formula.equals(formulaDefinition)) {
                return activeFrame.request().maxIterations();
            }
            if (gpuLimitJob != null && formula.equals(gpuLimitJob.formula())) {
                return gpuLimitJob.maxIterations();
            }
        }
        return scene.iterations().maxIterations(defaultViewport.scaleExact(), scene.viewport().scaleExact());
    }

    public boolean showsExplorerFrame(FractalScene scene) {
        return gpuRender != null && gpuRender.exploringJulia() && scene.equals(gpuShownScene);
    }

    public boolean isRenderingExplorerMainView() {
        return gpuInFlight && gpuRender != null && gpuRender.beforeCpuFallback() != null;
    }

    public boolean isRenderingExplorerMainView(FractalScene scene) {
        return isRenderingExplorerMainView() && scene.equals(gpuRender.scene());
    }

    private void render(FractalScene scene, RenderTarget target, Viewport defaultViewport,
                        boolean preserveIterationLimit, boolean allowJuliaGpu) {
        render(scene, target, defaultViewport, preserveIterationLimit, allowJuliaGpu, false, null);
    }

    private void render(FractalScene scene, RenderTarget target, Viewport defaultViewport,
                        boolean preserveIterationLimit, boolean allowJuliaGpu, boolean juliaExplorerPreview,
                        Runnable beforeCpuFallback) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(target);
        Objects.requireNonNull(defaultViewport);
        widePreview.cancel();
        visibleBusy = true;
        previewScene = scene;
        surface.latency().renderStarted();
        progressHandler.accept(0.0);
        long renderGeneration = renderActivity.begin();
        // Only the fallback render that was asked for it inherits the palette callback.
        fallbackApplied = fallbackAppliedRequest == null ? null
                : new FallbackApplied(renderGeneration, fallbackAppliedRequest);
        fallbackAppliedRequest = null;
        recolorEpoch.incrementAndGet();
        antialiasService.cancelCurrent();

        FormulaDefinition requestedFormula = FormulaDefinition.forScene(scene);
        if (!requestedFormula.equals(formulaDefinition)) {
            // Pan/resize reuse never crosses formulas, but the completed-frame cache is
            // keyed by formula and parameters and shares one budget across fractals, so
            // returning to a retained view of another fractal reuses its samples.
            formulaDefinition = requestedFormula;
            activeFrame = null;
            retainedFrame = null;
            // Another fractal's distribution would not match this one's colors.
            completedHistogram = null;
        }

        Viewport viewport = scene.viewport();

        boolean deepZoom = DeepZoomRenderPolicy.isDeepZoom(scene, target);
        deepZoomChangedHandler.accept(deepZoom);
        InteractiveRenderMode presentationMode =
                DeepZoomRenderPolicy.presentationMode(scene, target);
        ColoringStrategy coloring = scene.coloring().createProgressiveStrategy(completedHistogram);
        boolean refinedDisplay = presentationMode == InteractiveRenderMode.REFINED;
        boolean baseOnly = !presentationMode.antialiases();
        FrameProgress frameProgress = new FrameProgress(renderGeneration, presentationMode,
                (long) target.width() * target.height());

        BigDecimal zoomFactor = defaultViewport.scaleExact()
                .divide(viewport.scaleExact(), viewport.mathContext());

        int maxIterations = iterationLimit(scene, defaultViewport, requestedFormula, preserveIterationLimit);
        zoomChangedHandler.accept(zoomFactor);
        iterationsChangedHandler.accept(maxIterations);

        RenderJob renderRequest =
                new RenderJob(
                        formulaDefinition,
                        viewport,
                        target.width(),
                        target.height(),
                        maxIterations,
                        target.priority(),
                        surface.approximatePreviewCoverage(viewport),
                        Boolean.getBoolean("fractal.gpu.mandelbrot.enabled")
                                && !scene.coloring().histogramColoring()
                                && scene.coloring().orbitTrap() == com.shangin.fractal.coloring.OrbitTrap.NONE
                                ? SampleAccuracy.CERTIFIED_FP32 : SampleAccuracy.CPU_REFERENCE
                );

        boolean requiresGpu = juliaExplorerPreview && beforeCpuFallback == null;
        if (allowJuliaGpu && !deepZoom && juliaGpu.supports(renderRequest, scene.coloring())) {
            renderOnGpu(new GpuRender(scene, target, defaultViewport, renderRequest, juliaExplorerPreview,
                    requiresGpu, beforeCpuFallback), renderGeneration);
            return;
        }
        if (requiresGpu) {
            invalidateGpuFrames();
            gpuRender = null;
            reportFailure(renderGeneration, new IllegalStateException(
                    "Julia Parameter Map requires a GPU-supported view and coloring."));
            return;
        }
        // The CPU path owns the frame from here; late GPU results must not replace it.
        invalidateGpuFrames();
        gpuRender = null;
        gpuShownScene = null;
        gpuLimitJob = null;
        if (beforeCpuFallback != null) beforeCpuFallback.run();

        previewRequest = renderRequest;
        surface.prepareWidePreview(renderRequest, scene.coloring(), scene.iterations(), widePreviewSettings);

        if (changesOnlyPresentation(scene, renderRequest)) {
            changePresentation(activeFrame, scene, presentationMode, deepZoom, renderGeneration, frameProgress);
            return;
        }
        // Hidden AA pixels belong to the visible frame only.
        hiddenRefinement = null;
        unsampledAntialias = null;
        deferredRecoloring = null;

        RenderFrame previousActiveFrame = activeFrame;

        /*
         * --------------------------------
         * DATA REUSE
         * --------------------------------
         */

        // Create a target frame only for the source finally chosen.
        FrameReuseSelection reuseSelection =
                frameReusePlanner.planReuse(
                        previousActiveFrame,
                        retainedFrame,
                        renderRequest
                ).or(() -> frameCache.findExact(renderRequest)
                        .flatMap(cachedFrame -> frameReusePlanner.planReuse(
                                cachedFrame,
                                null,
                                renderRequest))
                ).orElseGet(() -> frameReusePlanner.fresh(renderRequest));

        surface.latency().mark("reuse_planned");
        RenderFrame sourceFrame = reuseSelection.sourceFrame();
        FrameReuseResult reuseResult = reuseSelection.result();
        // A complete copy of the displayed grid has the same samples, so the same histogram:
        // AA pixels copied from it already show this frame's mapping.
        CompletedRender displayedBefore = surface.completedRender();
        boolean copiesDisplayedMapping = sourceFrame != null && reuseResult.reused()
                && sourceFrame.isComplete()
                && RenderFrameCache.sameCalculatedFrame(sourceFrame.request(), renderRequest)
                && displayedBefore != null && displayedBefore.frame() == sourceFrame
                && displayedBefore.scene().coloring().equals(scene.coloring());

        RenderFrame targetFrame =
                reuseResult.frame();

        activeFrame =
                targetFrame;
        activeDeepZoom = deepZoom;

        // AA bound to the active frame moves with it; a cached frame brings its own retained AA.
        RetainedRefinement retainedRefinement = sourceFrame != null && sourceFrame != previousActiveFrame
                && reuseResult.reused()
                ? frameCache.refinementFor(sourceFrame).orElse(null) : null;
        if (retainedRefinement != null && retainedRefinement.samples() != null) {
            antialiasService.restoreSamples(sourceFrame, retainedRefinement.samples());
        }
        // An unshifted copy of the same grid inherits the AA, even if this visit adds none.
        RetainedRefinement carriedRefinement = retainedRefinement != null
                && reuseResult.shift().orElseThrow().equals(new PixelShift(0, 0))
                && sourceFrame.request().width() == targetFrame.request().width()
                && sourceFrame.request().height() == targetFrame.request().height()
                ? retainedRefinement : null;
        if (sourceFrame != null && reuseResult.reused()) {
            antialiasService.reuseFrame(
                    sourceFrame,
                    targetFrame,
                    reuseResult.shift().orElseThrow());
        }
        // Base-only frames show and recolor base samples only, never reused AA pixels.
        // Cached AA samples are kept, hidden, so a later antialiasing mode reuses them.
        antialiasService.showCachedSamples(!baseOnly);
        if (baseOnly) {
            surface.invalidateRefinement();
        }

        surface.latency().mark("aa_reuse_prepared");

        /* Keep the displaced zoom level so a reverse zoom can resume it. */
        if (sourceFrame != previousActiveFrame) {
            retainedFrame = previousActiveFrame;
        }

        /*
         * --------------------------------
         * PREPARE IMAGE
         * --------------------------------
         */

        boolean imageReused =
                false;

        if (sourceFrame != null
                && reuseResult.reused()) {

            PixelShift shift = reuseResult.shift()
                    .orElseThrow();

            imageReused = surface.reuseProgressivePixels(
                    sourceFrame,
                    targetFrame,
                    shift,
                    coloring,
                    !refinedDisplay
            );
        }

        surface.beginProgressiveRender(
                targetFrame,
                sourceFrame,
                presentationMode
        );

        if (!imageReused
                && sourceFrame != null
                && reuseResult.reused()) {

            imageReused =
                    surface.reuseDisplayedPixels(
                            sourceFrame,
                            reuseResult.shift()
                                    .orElseThrow()
                    );

        }

        // AA pixels shifted from another frame show that frame's histogram mapping.
        boolean refinementFromOtherFrame = imageReused && !copiesDisplayedMapping
                && surface.refinedPixelCount(targetFrame) > 0;

        // Retained AA colors fit only the unshifted grid and the coloring that produced them.
        if (!imageReused && !baseOnly && carriedRefinement != null
                && carriedRefinement.showsColorsFor(scene.coloring())) {
            imageReused = surface.restoreStagingRefinement(targetFrame, carriedRefinement.pixels());
            if (imageReused && carriedRefinement.samples() == null) {
                unsampledAntialias = new UnsampledAntialias(targetFrame, deepZoom);
                antialiasService.vouchForRestoredColors(targetFrame, coloring, deepZoom);
            }
        }

        /* Include valid rows from cancelled tiles that never published progress. */
        if (!refinedDisplay) {

            surface.displayReadyPixelsPreservingRefinement(
                    activeFrame,
                    coloring
            );
        }

        surface.latency().mark("surface_prepared");
        frameProgress.reused(activeFrame.validity().readyPixelCount(),
                baseOnly ? 0 : surface.refinedPixelCount(activeFrame));

        // A crop of a completed image is ready synchronously, including its AA. A histogram
        // of the crop differs from its source's, so it is colored again on completion instead.
        if (preserveIterationLimit && imageReused && targetFrame.isComplete()
                && !scene.coloring().dependsOnCompletedFrame()
                && (!refinedDisplay || surface.hasCompleteRefinement(targetFrame))) {
            surface.completeProgressiveRender(targetFrame, scene);
            applyFallbackPalette(renderGeneration);
            frameCache.put(targetFrame);
            initialFramePending = false;
            finishRender(renderGeneration);
            return;
        }

        /*
         * --------------------------------
         * CALCULATE MISSING
         * +
         * COLOR MISSING
         * --------------------------------
         */

        // AA colors kept without their samples, which the frame cache dropped for its budget:
        // the persistent store may still hold the samples, read once the frame completes.
        // Base only binds them hidden, so switching to an AA mode later reuses them.
        boolean readStoredSamples = carriedRefinement != null && carriedRefinement.samples() == null;

        surface.latency().mark("render_submit");
        renderService.render(
                activeFrame,
                readStoredSamples,
                surface.latency().callbacks("base", Platform::runLater),

                progress -> {
                    surface.displayProgress(
                            progress.outsideApproximateCoverage(),
                            coloring,
                            presentationMode
                    );
                    frameProgress.base(progress.frame().validity().readyPixelCount());
                },

                completedFrame -> {
                    surface.latency().mark("base_complete");
                    frameProgress.baseComplete();

                    frameCache.put(completedFrame);
                    // A histogram maps this frame's samples differently, so AA pixels shifted
                    // from the source frame would mix two mappings: they are antialiased again.
                    if (refinementFromOtherFrame && scene.coloring().dependsOnCompletedFrame()) {
                        surface.dropStagingRefinement(completedFrame);
                    }
                    // The new entry replaced its source's; keep the AA until this visit finishes its own.
                    if (carriedRefinement != null && completedFrame == targetFrame) {
                        frameCache.retainRefinement(completedFrame,
                                withStoredSamples(completedFrame, carriedRefinement));
                    } else if (completedFrame == targetFrame) {
                        renderService.takeRestoredRefinement(completedFrame).ifPresent(stored ->
                                restoreStoredRefinement(completedFrame, stored, scene.coloring(), coloring,
                                        baseOnly, deepZoom));
                    }

                    ColoringStrategy completedColoring =
                            scene.coloring().createStrategy(completedFrame.samplePlane());
                    rememberCompletedHistogram(completedColoring);

                    if (refinedDisplay) {
                        surface.beginRefinedRender(completedFrame);
                        refine(
                                deepZoom,
                                completedFrame,
                                completedColoring,
                                surface.refinedPixelSnapshot(completedFrame),
                                frameProgress,
                                surface.latency().callbacks("aa", Platform::runLater),
                                (region, colors) -> surface.displayRefinedTile(
                                        completedFrame,
                                        region,
                                        colors
                                ),
                                () -> {
                                    surface.completeProgressiveRender(
                                            completedFrame,
                                            scene
                                    );
                                    applyFallbackPalette(renderGeneration);
                                    retainRefinement(completedFrame, scene.coloring());
                                    initialFramePending = false;
                                    finishRender(renderGeneration);
                                },
                                error -> {
                                    surface.displayReadyPixelsPreservingRefinement(
                                            completedFrame, completedColoring);
                                    surface.completeProgressiveRender(completedFrame, scene);
                                    applyFallbackPalette(renderGeneration);
                                    initialFramePending = false;
                                    reportFailure(renderGeneration, error);
                                }
                        );
                        return;
                    }

                    // Progressive tiles use the frame-independent coloring; a histogram needs the
                    // completed frame, so those pixels are colored again before promotion.
                    if (initialFramePending
                            || completedFrame.request().approximateCoverage().isPresent()
                            || scene.coloring().dependsOnCompletedFrame()) {
                        surface.displayReadyPixelsPreservingRefinement(
                                completedFrame,
                                completedColoring
                        );
                        initialFramePending = false;
                    }

                    surface.completeProgressiveRender(
                            completedFrame,
                            scene
                    );
                    // The base frame is on screen now, before any antialiasing that may be cancelled.
                    applyFallbackPalette(renderGeneration);

                    if (baseOnly) {
                        finishRender(renderGeneration);
                        return;
                    }

                    refine(
                            deepZoom,
                            completedFrame,
                            completedColoring,
                            surface.refinedPixelSnapshot(completedFrame),
                            frameProgress,
                            surface.latency().callbacks("aa", Platform::runLater),
                            (region, colors) -> surface.applyAntialiasing(
                                    completedFrame,
                                    region,
                                    colors
                            ),
                            () -> {
                                retainRefinement(completedFrame, scene.coloring());
                                finishRender(renderGeneration);
                            },
                            error -> {
                                reportFailure(renderGeneration, error);
                            }
                    );

                },

                error -> {
                    reportFailure(renderGeneration, error);
                }
        );
    }

    /**
     * Replaces the CPU render with one approximate GPU frame. Samples of the previous
     * CPU frame are dropped from reuse: the GPU frame has none, so a later CPU render
     * starts from the completed-frame cache or a fresh frame.
     */
    private void renderOnGpu(GpuRender render, long renderGeneration) {
        renderService.cancelCurrent();
        previewRequest = null;
        surface.clearWidePreview();
        surface.invalidateRefinement();
        hiddenRefinement = null;
        unsampledAntialias = null;
        deferredRecoloring = null;
        activeFrame = null;
        retainedFrame = null;
        gpuGeneration = renderGeneration;
        gpuLimitJob = render.job();
        submitGpu(render, null);
    }

    private void submitGpu(GpuRender render, Runnable onApplied) {
        gpuRender = render;
        gpuOnApplied = onApplied;
        gpuInFlight = true;
        gpuPaletteOnlyCancel = false;
        long sequence = ++gpuSequence;
        juliaGpu.submit(new JuliaGpuRenderer.Request(
                        sequence, render.job(), render.scene().coloring(), render.exploringJulia()),
                new JuliaGpuRenderer.Listener() {
                    @Override public void rendered(JuliaGpuRenderer.Request request, int[] colors,
                                                   JuliaGpuRenderer.FrameTiming timing) {
                        try {
                            if (closed || request.sequence() != gpuSequence) return;
                            recordGpuTiming(timing);
                            gpuInFlight = false;
                            // False only after a resize, whose own render replaces this frame.
                            if (surface.displayApproximateFrame(render.scene(), request.job().width(),
                                    request.job().height(), colors)) {
                                gpuShownScene = render.scene();
                                initialFramePending = false;
                                progressHandler.accept(1.0);
                                Runnable applied = gpuOnApplied;
                                gpuOnApplied = null;
                                long generation = gpuGeneration;
                                gpuGeneration = -1;
                                if (generation >= 0) finishRender(generation);
                                if (applied != null) applied.run();
                            }
                            Runnable recolorApplied = pendingGpuRecolor;
                            pendingGpuRecolor = null;
                            if (recolorApplied != null && !closed && gpuRender != null && !gpuInFlight) {
                                submitGpu(gpuRender, recolorApplied);
                            }
                        } finally {
                            juliaGpu.release(colors);
                        }
                    }

                    @Override public void unavailable(JuliaGpuRenderer.Request request) {
                        if (closed) return;
                        GpuRender latest = gpuRender;
                        // A refusal disables the runtime even when the request was superseded.
                        if (juliaGpuEnabled) {
                            if (latest != null && !latest.requiresGpu()) {
                                System.getLogger(FractalRenderController.class.getName()).log(
                                        System.Logger.Level.WARNING, "Julia GPU kernel unavailable; rendering on CPU");
                            }
                            juliaGpuEnabled = false;
                            juliaGpuChangedHandler.accept(false);
                        }
                        if (latest == null) return;
                        if (latest.requiresGpu()) {
                            failed(request, new IllegalStateException("Julia Parameter Map GPU rendering became unavailable."));
                            return;
                        }
                        // The CPU render keeps the GPU job's iteration limit (gpuLimitJob), which a
                        // resize may have preserved; the CPU path then clears the GPU state.
                        if (request.sequence() == gpuSequence) {
                            // Show the latest requested palette and record it once that CPU frame
                            // finishes, as a successful GPU frame would have.
                            Runnable applied = pendingGpuRecolor != null ? pendingGpuRecolor : gpuOnApplied;
                            invalidateGpuFrames();
                            fallbackAppliedRequest = applied;
                            latest.prepareCpuFallback();
                            render(latest.scene(), latest.target(), latest.defaultViewport(), true);
                            return;
                        }
                        // A newer GPU request reports for itself, and a cancelled scene (a resize
                        // or pan whose render follows) must not return to the superseded frame.
                        if (gpuInFlight || !gpuPaletteOnlyCancel) return;
                        // Only palette work was cancelled (Animate Palette stopped): no render
                        // replaces the approximate frame, so show its scene and palette exactly.
                        FractalScene shown = gpuShownScene == null ? latest.scene()
                                : latest.scene().withColoring(gpuShownScene.coloring());
                        invalidateGpuFrames();
                        latest.prepareCpuFallback();
                        render(shown, latest.target(), latest.defaultViewport(), true);
                    }

                    @Override public void failed(JuliaGpuRenderer.Request request, Throwable error) {
                        if (closed || request.sequence() != gpuSequence) return;
                        long generation = gpuGeneration;
                        invalidateGpuFrames();
                        // Without gpuRender later palette changes are no-ops until the next render,
                        // so neither a palette change nor stopping a running animation (the view
                        // stops it on FAILED) can resubmit the failed frame, silently show it under
                        // a FAILED status or report the failure again.
                        gpuRender = null;
                        gpuShownScene = null;
                        if (generation >= 0) {
                            // The scene render failed.
                            reportFailure(generation, error);
                            return;
                        }
                        // A palette frame failed after the scene completed: report it once as a
                        // failed frame.
                        if (renderActivity.failCompleted()) errorHandler.accept(error);
                    }
                });
    }

    /** Records the worker's stage costs for the current render when latency recording is on. FX thread. */
    private void recordGpuTiming(JuliaGpuRenderer.FrameTiming timing) {
        var latency = surface.latency();
        if (!latency.isEnabled()) return;
        long ready = timing.readyNanos();
        var gpu = timing.gpu();
        latency.measured("gpu_worker_start", timing.startNanos(), 0);
        latency.measured("gpu_call", ready, timing.callNanos());
        latency.measured("gpu_setup", ready, gpu.setupNanos());
        latency.measured("gpu_upload", ready, gpu.uploadNanos());
        latency.measured("gpu_dispatch", ready, gpu.dispatchNanos());
        latency.measured("gpu_readback", ready, gpu.readbackNanos());
        latency.measured("gpu_kernel_total", ready, gpu.totalNanos());
        if (timing.cpuFilterNanos() > 0) latency.measured("bright_filter", ready, timing.cpuFilterNanos());
        latency.measured("gpu_ready", ready, 0);
        latency.measured("gpu_callback", System.nanoTime(), 0);
    }

    /**
     * Drops queued and in-flight GPU work: a request that has not started never runs,
     * and a running one is not published. The displayed GPU frame stays visible.
     */
    private void invalidateGpuFrames() {
        gpuSequence++;
        gpuPaletteOnlyCancel = false;
        gpuInFlight = false;
        gpuGeneration = -1;
        gpuOnApplied = null;
        pendingGpuRecolor = null;
        juliaGpu.cancelPending();
    }

    /** Suspends hidden reserve recoloring while the visible palette is animated. FX-thread only. */
    public void setColorCycling(boolean cycling, ColoringSettings displayedColoring) {
        Objects.requireNonNull(displayedColoring);
        if (closed || widePreviewColorCycling == cycling) return;
        widePreviewColorCycling = cycling;
        widePreview.cancel();
        surface.clearWidePreview();
        if (!cycling && previewScene != null) {
            previewScene = previewScene.withColoring(displayedColoring);
            // A Julia GPU frame has no wide-preview request.
            if (previewRequest != null) {
                surface.prepareWidePreview(previewRequest, displayedColoring, previewScene.iterations(),
                        widePreviewSettings);
            }
            replenishWidePreview();
        }
        if (!cycling) reconcilePalette(displayedColoring);
    }

    /**
     * Stopping palette animation cancels palette work in flight, which may include a palette
     * the user chose meanwhile. The scene's palette is authoritative: when the frame on screen
     * shows another one, recolor it. A Julia GPU frame has no samples to recolor; it compares
     * the GPU frame on screen, or the one still being produced, and renders it again.
     */
    private void reconcilePalette(ColoringSettings sceneColoring) {
        if (gpuRender != null) {
            ColoringSettings target = gpuInFlight ? gpuRender.scene().coloring()
                    : gpuShownScene == null ? null : gpuShownScene.coloring();
            if (!sceneColoring.equals(target)) recolor(sceneColoring, () -> {});
            return;
        }
        CompletedRender displayed = surface.completedRender();
        if (displayed != null && displayed.scene().coloring().equals(sceneColoring)) return;
        recolor(sceneColoring);
    }

    /** Applies the preview policy: invalidates old work and pixels without disturbing exact rendering. */
    public void setWidePreviewSettings(WidePreviewSettings settings) {
        Objects.requireNonNull(settings);
        if (closed || settings.equals(widePreviewSettings)) return;
        widePreviewSettings = settings;
        widePreview.cancel();
        surface.clearWidePreview();
        if (previewRequest != null) {
            surface.prepareWidePreview(previewRequest, previewScene.coloring(), previewScene.iterations(), settings);
            replenishWidePreview();
        }
    }

    public WidePreviewSettings widePreviewSettings() { return widePreviewSettings; }

    private void finishRender(long renderGeneration) {
        if (!renderActivity.isCurrent(renderGeneration)) return;
        visibleBusy = false;
        // The next fresh frame of this size, such as a zoom-out, takes this storage.
        if (activeFrame != null) spareFrameStorage.prepareFor(activeFrame.request());
        applyFallbackPalette(renderGeneration);
        renderActivity.finish(renderGeneration);
        replenishWidePreview();
        DeferredRecoloring deferred = deferredRecoloring;
        deferredRecoloring = null;
        // A newer palette change, published or still pending, makes this one stale.
        if (deferred != null && activeFrame != null && deferred.epoch() == recolorEpoch.get()
                && deferred.request() == recolorSequence.get()) {
            // Publishes the palette on the finished frame, then samples any AA it still lacks.
            recolor(deferred.settings(), deferred.onApplied());
            return;
        }
        // A pass that skipped reused AA pixels may leave candidates of a steeper palette
        // untested. A pass that just ran in place is not repeated.
        CompletedRender displayed = surface.completedRender();
        if (renderGeneration != resampleGeneration && displayed != null && displayed.frame() == activeFrame) {
            resampleAntialiasIfNeeded(activeFrame, displayed.scene().coloring(), false,
                    publishedRecolorSequence.get());
        }
    }

    /**
     * Records the palette of a CPU fallback once its frame is published, before any
     * antialiasing that may still be cancelled. Runs once, for that render generation only.
     */
    private void applyFallbackPalette(long renderGeneration) {
        FallbackApplied applied = fallbackApplied;
        if (applied == null || applied.generation() != renderGeneration
                || !renderActivity.isCurrent(renderGeneration)) return;
        fallbackApplied = null;
        applied.onApplied().run();
    }

    private void replenishWidePreview() {
        if (closed || visibleBusy || widePreviewColorCycling || previewRequest == null
                || !widePreviewSettings.enabled()) return;
        widePreview.request(previewRequest,
                previewScene.coloring().createProgressiveStrategy(completedHistogram), widePreviewSettings,
                surface::displayWidePreview,
                error -> System.getLogger(FractalRenderController.class.getName())
                        .log(System.Logger.Level.WARNING, "Wide preview failed", error));
    }

    /**
     * Keeps the histogram mapping of the completed active frame for the next interaction.
     * The reserve shown was colored with the previous mapping, so it is hidden until the
     * idle reserve is colored again with this one; a gesture before that shows no reserve
     * rather than edges in another mapping.
     */
    private void rememberCompletedHistogram(ColoringStrategy completedColoring) {
        if (completedColoring instanceof HistogramPaletteColoring histogram && histogram != completedHistogram) {
            completedHistogram = histogram;
            surface.clearWidePreview();
        }
    }

    /** A completed visible frame whose scene differs only in antialiasing needs no new samples. */
    private boolean changesOnlyPresentation(FractalScene scene, RenderJob request) {
        CompletedRender displayed = surface.completedRender();
        return displayed != null
                && displayed.frame() == activeFrame
                && activeFrame.isComplete()
                && !displayed.scene().antialiasing().equals(scene.antialiasing())
                && displayed.scene().withAntialiasing(scene.antialiasing()).equals(scene)
                && RenderFrameCache.sameCalculatedFrame(activeFrame.request(), request);
    }

    /**
     * Applies a new antialiasing mode to the visible frame in place. Base samples
     * and finished AA tiles are kept; only missing AA tiles are calculated.
     */
    private void changePresentation(RenderFrame frame, FractalScene scene, InteractiveRenderMode mode,
                                    boolean deepZoom, long renderGeneration, FrameProgress frameProgress) {
        frameProgress.baseComplete();
        ColoringStrategy coloring = scene.coloring().createStrategy(frame.samplePlane());
        antialiasService.showCachedSamples(mode.antialiases());
        if (!mode.antialiases()) {
            // Cached samples cover smooth palettes only, so keep the finished AA colors for every coloring.
            RefinedPixelSnapshot shown = surface.refinedPixelSnapshot(frame);
            if (shown.validity().readyPixelCount() > 0) {
                hiddenRefinement = new HiddenRefinement(frame, scene.coloring(), shown);
            }
            surface.showBaseColors(frame, coloring, scene);
            finishRender(renderGeneration);
            return;
        }
        if (hiddenRefinement != null && hiddenRefinement.frame() == frame
                && hiddenRefinement.coloring().equals(scene.coloring())) {
            surface.restoreRefinement(frame, hiddenRefinement.pixels());
        }
        hiddenRefinement = null;
        RefinedPixelSnapshot refined = surface.refinedPixelSnapshot(frame);
        if (refined.validity().isComplete()) {
            // The visible image is already fully antialiased, so both AA modes show it as is.
            frameProgress.refining(refined);
            surface.completePresentationChange(frame, scene);
            finishRender(renderGeneration);
            return;
        }
        // The base is already visible, so both AA modes refine the missing tiles in place.
        refine(
                deepZoom,
                frame,
                coloring,
                refined,
                frameProgress,
                surface.latency().callbacks("aa", Platform::runLater),
                (region, colors) -> surface.applyAntialiasing(frame, region, colors),
                () -> {
                    // Before the displayed scene is replaced, it still shows any recolor
                    // published during the pass, which mixes palettes in these AA tiles.
                    retainRefinement(frame, scene.coloring());
                    surface.completePresentationChange(frame, scene);
                    finishRender(renderGeneration);
                },
                error -> reportFailure(renderGeneration, error)
        );
    }

    /**
     * The recolor just published shows only AA that cached samples cover. Retained AA
     * colors restored without their samples cannot follow a palette change, and a palette
     * steeper than those of the finished passes selects candidates nobody sampled. Either
     * way the visible frame is antialiased again, in place and cancellably, under the new
     * palette; existing samples are reused, so once finished, recoloring needs no AA pass.
     * Without a palette change, retained colors still fit and only missing candidates count.
     */
    private void resampleAntialiasIfNeeded(RenderFrame frame, ColoringSettings settings, boolean paletteChanged,
                                           long request) {
        // Base only shows no AA; returning to an AA mode restores or recalculates it.
        if (closed || frame != activeFrame || !antialiasService.cachedSamplesShown()) return;
        UnsampledAntialias tracked = unsampledAntialias;
        UnsampledAntialias unsampled = tracked != null && tracked.frame() == frame ? tracked : null;
        if (unsampled != null && !paletteChanged) return;
        boolean deepZoom = unsampled != null ? unsampled.deepZoom() : activeDeepZoom;
        ColoringStrategy coloring = settings.createStrategy(frame.samplePlane());
        // Samples the cache rejected matter only to a recolor: the pass that rejected them
        // displayed their AA colors, which a frame without a palette change still shows.
        if (unsampled == null && !(paletteChanged
                ? antialiasService.missesCandidates(frame, coloring, deepZoom)
                : antialiasService.leavesCandidatesUntested(frame, coloring, deepZoom))) return;
        // Until the visit's own pass has published this frame and finished, a new generation
        // would strand that pass: repeat the palette change after it instead. A resample
        // pass in progress is simply replaced.
        CompletedRender displayed = surface.completedRender();
        boolean ownPassRunning = visibleBusy && !renderActivity.isCurrent(resampleGeneration);
        if (ownPassRunning || displayed == null || displayed.frame() != frame) {
            deferredRecoloring = new DeferredRecoloring(settings, () -> {}, recolorEpoch.get(), request);
            return;
        }
        deferredRecoloring = null;
        long renderGeneration = renderActivity.beginRefinement();
        resampleGeneration = renderGeneration;
        visibleBusy = true;
        surface.invalidateRefinement();
        int width = frame.samplePlane().width();
        int height = frame.samplePlane().height();
        refine(
                deepZoom,
                frame,
                coloring,
                RefinedPixelSnapshot.empty(width, height),
                new FrameProgress(renderGeneration, InteractiveRenderMode.FAST, (long) width * height),
                surface.latency().callbacks("aa", Platform::runLater),
                (region, colors) -> surface.applyAntialiasing(frame, region, colors),
                () -> {
                    if (unsampled != null && unsampledAntialias == unsampled) unsampledAntialias = null;
                    retainRefinement(frame, settings);
                    finishRender(renderGeneration);
                },
                error -> reportFailure(renderGeneration, error)
        );
    }

    /**
     * Applies the AA stored in an earlier launch with a base frame just restored from disk,
     * exactly like AA retained in memory: it is kept beside the cached frame, its samples
     * serve recoloring and later passes, and colors of the displayed coloring are put on the
     * staging frame, so the visit's AA pass only fills what they leave out. FX thread only.
     */
    private void restoreStoredRefinement(RenderFrame frame, RetainedRefinement stored,
                                         ColoringSettings settings, ColoringStrategy coloring,
                                         boolean baseOnly, boolean deepZoom) {
        frameCache.retainRefinement(frame, stored);
        if (stored.samples() != null) {
            antialiasService.restoreSamples(frame, stored.samples());
        } else {
            // Bind the AA service to the restored frame, as frame reuse does for retained AA.
            antialiasService.reuseFrame(frame, frame, new PixelShift(0, 0));
        }
        if (!stored.showsColorsFor(settings)) {
            return;
        }
        if (baseOnly) {
            // Kept like AA hidden by switching to Base only, so an AA mode shows it again.
            hiddenRefinement = new HiddenRefinement(frame, settings, stored.pixels());
        } else if (surface.restoreStagingRefinement(frame, stored.pixels()) && stored.samples() == null) {
            unsampledAntialias = new UnsampledAntialias(frame, deepZoom);
            antialiasService.vouchForRestoredColors(frame, coloring, deepZoom);
        }
    }

    /**
     * Rebinds the AA samples the persistent store read for a frame whose retained AA kept
     * only its colors, so recoloring and later passes use them as if never dropped. Returns
     * the retained AA with those samples, or unchanged without any. FX thread only.
     */
    private RetainedRefinement withStoredSamples(RenderFrame frame, RetainedRefinement carried) {
        if (carried.samples() != null) return carried;
        AntialiasSampleCache.Retained samples = renderService.takeRestoredRefinement(frame)
                .map(RetainedRefinement::samples).orElse(null);
        if (samples == null) return carried;
        antialiasService.restoreSamples(frame, samples);
        if (unsampledAntialias != null && unsampledAntialias.frame() == frame) unsampledAntialias = null;
        return new RetainedRefinement(carried.pixels(), carried.coloring(), samples);
    }

    /**
     * Keeps a frame's finished AA beside it in the frame cache, so returning to the frame
     * later, including after a fractal switch, shows it without another AA pass. When
     * frames persist across launches, it is also stored beside the stored base frame.
     */
    private void retainRefinement(RenderFrame frame, ColoringSettings coloring) {
        AntialiasSampleCache.Retained samples = antialiasService.retainSamples(frame);
        CompletedRender displayed = surface.completedRender();
        // A recolor published during the pass mixes palettes in the displayed AA colors.
        boolean oneColoring = displayed != null && displayed.frame() == frame
                && displayed.scene().coloring().equals(coloring);
        RefinedPixelSnapshot pixels = oneColoring ? surface.refinedPixelSnapshot(frame) : null;
        if (pixels != null && pixels.validity().readyPixelCount() == 0) pixels = null;
        if (pixels == null && samples == null) return;
        RetainedRefinement refinement = new RetainedRefinement(pixels, pixels == null ? null : coloring, samples);
        frameCache.retainRefinement(frame, refinement);
        renderService.saveRefinementWhenSettled(frame, refinement);
    }

    /** Deep frames use candidate-only AA that keeps subpixel coordinates exact. */
    private void refine(
            boolean deepZoom,
            RenderFrame frame,
            ColoringStrategy coloring,
            RefinedPixelSnapshot reusedPixels,
            FrameProgress frameProgress,
            Executor callbackExecutor,
            ToIntBiFunction<RenderRegion, int[]> publishTile,
            Runnable onSuccess,
            Consumer<Throwable> onError
    ) {
        frameProgress.refining(reusedPixels);
        BiConsumer<RenderRegion, int[]> onTileReady =
                (region, colors) -> frameProgress.refined(publishTile.applyAsInt(region, colors));
        if (deepZoom) {
            antialiasService.refineDeep(frame, coloring, reusedPixels, callbackExecutor,
                    onTileReady, onSuccess, onError);
        } else {
            antialiasService.refine(frame, coloring, reusedPixels, callbackExecutor,
                    onTileReady, onSuccess, onError);
        }
    }

    /**
     * Whole-frame progress for one render generation, published on the FX thread.
     * Without antialiasing the base render is the whole frame; otherwise the base
     * samples and the AA pixels each account for half. Reused samples and reused
     * AA pixels count as done from the start, so a pan reports what it kept.
     */
    private final class FrameProgress {
        private final long renderGeneration;
        private final double baseShare;
        private final long pixels;
        private long basePixels;
        private long refinedPixels;
        private double published;

        FrameProgress(long renderGeneration, InteractiveRenderMode mode, long pixels) {
            this.renderGeneration = renderGeneration;
            baseShare = mode.antialiases() ? 0.5 : 1.0;
            this.pixels = pixels;
        }

        /** Records samples and AA pixels already reused when the render starts. */
        void reused(long readyPixels, long refinedReadyPixels) {
            basePixels = readyPixels;
            refinedPixels = refinedReadyPixels;
            publish();
        }

        void base(long readyPixels) {
            basePixels = readyPixels;
            publish();
        }

        void baseComplete() {
            basePixels = pixels;
            publish();
        }

        void refining(RefinedPixelSnapshot reusedPixels) {
            basePixels = pixels;
            refinedPixels = reusedPixels.validity().readyPixelCount();
            publish();
        }

        void refined(int newPixels) {
            refinedPixels += newPixels;
            publish();
        }

        private void publish() {
            double refinedShare = baseShare < 1.0 ? (1.0 - baseShare) * refinedPixels / pixels : 0.0;
            // A newer count may only be lower when stale AA pixels were dropped; never move backwards.
            published = Math.max(published, Math.min(1.0, baseShare * basePixels / pixels + refinedShare));
            if (renderActivity.isCurrent(renderGeneration)) {
                progressHandler.accept(published);
            }
        }
    }

    public void setOnRenderStatusChanged(Consumer<RenderStatus> handler) {
        renderActivity.setStatusListener(status -> {
            if (status.state() == RenderStatus.State.COMPLETE) surface.latency().mark("complete");
            handler.accept(status);
        });
    }

    public void setOnRenderProgressChanged(Consumer<Double> handler) {
        progressHandler = Objects.requireNonNull(handler);
    }

    public void setOnRenderError(Consumer<Throwable> handler) {
        errorHandler = Objects.requireNonNull(handler);
    }

    private void reportFailure(long renderGeneration, Throwable error) {
        if (renderActivity.fail(renderGeneration)) errorHandler.accept(error);
    }

    public void setOnRenderingChanged(Consumer<Boolean> handler) {
        renderActivity.setListener(handler);
    }

    public void setOnZoomChanged(
            Consumer<BigDecimal> handler
    ) {
        zoomChangedHandler =
                Objects.requireNonNull(
                        handler
                );
    }

    public void setOnDeepZoomChanged(Consumer<Boolean> handler) {
        deepZoomChangedHandler = Objects.requireNonNull(handler);
    }

    public void setOnIterationsChanged(Consumer<Integer> handler) {
        iterationsChangedHandler = Objects.requireNonNull(handler);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        widePreview.close();
        surface.clearWidePreview();
        recolorEpoch.incrementAndGet();
        recolorExecutor.shutdownNow();
        invalidateGpuFrames();
        antialiasService.close();
        // Nothing may wait for the GPU here, on the FX thread: the service, and with it the
        // runtime, closes at once when no GPU frame runs, otherwise after it drains.
        renderService.cancelCurrent();
        juliaGpu.close(renderService::close);
        spareFrameStorage.close();
        renderActivity.cancel();
    }
}
