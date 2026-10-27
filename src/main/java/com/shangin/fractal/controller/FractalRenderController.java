package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.gpu.PaletteRecolorBackend;
import com.shangin.fractal.gpu.PaletteRecolorTiming;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
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
    private final FractalRenderService renderService;
    private final InteractiveAntialiasService antialiasService =
            new InteractiveAntialiasService();
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
    private RenderFrame activeFrame;
    private RenderFrame retainedFrame;
    private boolean initialFramePending = true;
    private final FrameReusePlanner frameReusePlanner = new FrameReusePlanner();
    private final RenderFrameCache frameCache = new RenderFrameCache();
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

    public FractalRenderController(FractalSurface surface) {
        this(surface, new FractalRenderService());
    }

    public FractalRenderController(FractalSurface surface, FractalRenderService renderService) {
        this(surface, renderService, InteractiveAntialiasService::recolorCachedInto);
    }

    public FractalRenderController(FractalSurface surface, FractalRenderService renderService,
                                   RecolorOperation recolorOperation) {
        this.surface = Objects.requireNonNull(surface);
        this.renderService = Objects.requireNonNull(renderService);
        this.recolorOperation = Objects.requireNonNull(recolorOperation);
        renderService.setDiagnosticsListener(diagnostics -> surface.latency().attachDiagnostics(diagnostics.completion()));
        this.paletteRecolorBackend = renderService.paletteRecolorBackend();
    }

    private Consumer<BigDecimal> zoomChangedHandler = ignored -> {};
    private Consumer<Integer> iterationsChangedHandler = ignored -> {};
    private Consumer<Boolean> deepZoomChangedHandler = ignored -> {};
    private Consumer<Double> progressHandler = ignored -> {};
    private Consumer<Throwable> errorHandler = error ->
            System.getLogger(FractalRenderController.class.getName())
                    .log(System.Logger.Level.ERROR, "Render failed", error);

    public void cancelCurrent() {
        recolorEpoch.incrementAndGet();
        renderService.cancelCurrent();
        antialiasService.cancelCurrent();
        renderActivity.cancel();
    }

    /** Invalidates palette-only work without cancelling fractal or AA rendering. */
    public void cancelRecolor() {
        recolorEpoch.incrementAndGet();
    }

    /** Coalesced, double-buffered recolor of base and cached AA samples. */
    public void recolor(ColoringSettings settings) {
        recolor(settings, () -> {});
    }

    public void recolor(ColoringSettings settings, Runnable onApplied) {
        Objects.requireNonNull(settings);
        Objects.requireNonNull(onApplied);
        RenderFrame frame = activeFrame;
        if (frame == null || !frame.isComplete()) {
            return;
        }
        long epoch = recolorEpoch.get();
        long request = recolorSequence.incrementAndGet();
        ColoringStrategy coloring = settings.createStrategy(frame.samplePlane());
        recolorExecutor.execute(() -> {
            if (epoch != recolorEpoch.get() || Thread.currentThread().isInterrupted()) return;
            if (!recolorBufferSlots.tryAcquire()) {
                return;
            }
            int[] ready = acquireRecolorBuffer(frame.samplePlane().size());
            PaletteRecolorTiming timing;
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
                        long presentationStarted = System.nanoTime();
                        surface.applyRecolor(frame, ready, settings);
                        lastPaletteRecolorTiming.set(timing.withPublication(
                                presentationStarted - queued, System.nanoTime() - presentationStarted));
                        publishedRecolorSequence.set(request);
                        onApplied.run();
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
        Objects.requireNonNull(scene);
        Objects.requireNonNull(target);
        Objects.requireNonNull(defaultViewport);
        surface.latency().renderStarted();
        progressHandler.accept(0.0);
        long renderGeneration = renderActivity.begin();
        recolorEpoch.incrementAndGet();
        antialiasService.cancelCurrent();

        FormulaDefinition requestedFormula = FormulaDefinition.forScene(scene);
        if (!requestedFormula.equals(formulaDefinition)) {
            formulaDefinition = requestedFormula;
            activeFrame = null;
            retainedFrame = null;
            frameCache.clear();
        }

        Viewport viewport = scene.viewport();

        boolean deepZoom = DeepZoomRenderPolicy.isDeepZoom(scene, target);
        deepZoomChangedHandler.accept(deepZoom);
        InteractiveRenderMode presentationMode =
                DeepZoomRenderPolicy.presentationMode(scene, target);
        ColoringStrategy coloring = scene.coloring().createStrategy();
        boolean refinedDisplay = presentationMode == InteractiveRenderMode.REFINED;
        boolean baseOnly = !presentationMode.antialiases();
        FrameProgress frameProgress = new FrameProgress(renderGeneration, presentationMode,
                (long) target.width() * target.height());

        BigDecimal zoomFactor = defaultViewport.scaleExact()
                .divide(viewport.scaleExact(), viewport.mathContext());

        int maxIterations =
                scene.iterations().maxIterations(
                        defaultViewport.scaleExact(),
                        viewport.scaleExact()
                );
        // Resizing and panning retain the pixel scale, so keep their samples
        // compatible even if the resized default viewport changes zoom policy.
        if (preserveIterationLimit && activeFrame != null) {
            maxIterations = activeFrame.request().maxIterations();
        }
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

        if (changesOnlyPresentation(scene, renderRequest)) {
            changePresentation(activeFrame, scene, presentationMode, deepZoom, renderGeneration, frameProgress);
            return;
        }
        // Hidden AA pixels belong to the visible frame only.
        hiddenRefinement = null;

        RenderFrame previousActiveFrame = activeFrame;

        /*
         * --------------------------------
         * DATA REUSE
         * --------------------------------
         */

        FrameReuseSelection reuseSelection =
                frameReusePlanner.plan(
                        previousActiveFrame,
                        retainedFrame,
                        renderRequest
                );

        if (!reuseSelection.result().reused()) {
            RenderFrame cachedFrame = frameCache.findExact(renderRequest).orElse(null);
            if (cachedFrame != null) {
                reuseSelection = frameReusePlanner.plan(
                        cachedFrame,
                        null,
                        renderRequest
                );
            }
        }

        surface.latency().mark("reuse_planned");
        RenderFrame sourceFrame = reuseSelection.sourceFrame();
        FrameReuseResult reuseResult = reuseSelection.result();

        RenderFrame targetFrame =
                reuseResult.frame();

        activeFrame =
                targetFrame;

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

        // A crop of a completed image is ready synchronously, including its AA.
        if (preserveIterationLimit && imageReused && targetFrame.isComplete()
                && (!refinedDisplay || surface.hasCompleteRefinement(targetFrame))) {
            surface.completeProgressiveRender(targetFrame, scene);
            frameCache.put(targetFrame);
            initialFramePending = false;
            renderActivity.finish(renderGeneration);
            return;
        }

        /*
         * --------------------------------
         * CALCULATE MISSING
         * +
         * COLOR MISSING
         * --------------------------------
         */

        surface.latency().mark("render_submit");
        renderService.render(
                activeFrame,
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

                    ColoringStrategy completedColoring =
                            scene.coloring().createStrategy(completedFrame.samplePlane());

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
                                    initialFramePending = false;
                                    renderActivity.finish(renderGeneration);
                                },
                                error -> {
                                    surface.displayReadyPixelsPreservingRefinement(
                                            completedFrame, completedColoring);
                                    surface.completeProgressiveRender(completedFrame, scene);
                                    initialFramePending = false;
                                    reportFailure(renderGeneration, error);
                                }
                        );
                        return;
                    }

                    if (initialFramePending
                            || completedFrame.request().approximateCoverage().isPresent()) {
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

                    if (baseOnly) {
                        renderActivity.finish(renderGeneration);
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
                            () -> renderActivity.finish(renderGeneration),
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
            renderActivity.finish(renderGeneration);
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
            renderActivity.finish(renderGeneration);
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
                    surface.completePresentationChange(frame, scene);
                    renderActivity.finish(renderGeneration);
                },
                error -> reportFailure(renderGeneration, error)
        );
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
        recolorEpoch.incrementAndGet();
        recolorExecutor.shutdownNow();
        antialiasService.close();
        renderService.close();
        renderActivity.cancel();
    }
}
