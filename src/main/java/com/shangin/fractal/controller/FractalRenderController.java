package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.ui.FractalSurface;
import javafx.application.Platform;

import java.util.Objects;
import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

/**
 * Coordinates render requests between the camera-facing UI and the background
 * rendering pipeline, including iteration policy, frame reuse, and coloring.
 */
public final class FractalRenderController implements AutoCloseable {

    private final FractalSurface surface;
    private final FractalRenderService renderService = new FractalRenderService();
    private final InteractiveAntialiasService antialiasService =
            new InteractiveAntialiasService();

    private FormulaDefinition formulaDefinition;
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
    private final Semaphore recolorBufferSlots = new Semaphore(2);
    private final ConcurrentLinkedDeque<int[]> recolorBuffers = new ConcurrentLinkedDeque<>();

    public FractalRenderController(FractalSurface surface) {
        this.surface = Objects.requireNonNull(surface);
    }

    private Consumer<BigDecimal> zoomChangedHandler = ignored -> {};

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
            if (!recolorBufferSlots.tryAcquire()) {
                return;
            }
            int[] ready = acquireRecolorBuffer(frame.samplePlane().size());
            try {
                antialiasService.recolorCachedInto(frame, coloring, ready);
            } catch (RuntimeException error) {
                recolorBuffers.offerFirst(ready);
                recolorBufferSlots.release();
                throw error;
            }
            Platform.runLater(() -> {
                try {
                    if (epoch == recolorEpoch.get()
                            && frame == activeFrame
                            && request > publishedRecolorSequence.get()) {
                        surface.applyRecolor(frame, ready, settings);
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
        Objects.requireNonNull(scene);
        Objects.requireNonNull(target);
        Objects.requireNonNull(defaultViewport);
        long renderGeneration = renderActivity.begin();
        recolorEpoch.incrementAndGet();
        antialiasService.cancelCurrent();

        FormulaDefinition requestedFormula = FormulaDefinition.forPreset(
                scene.fractal(), scene.coloring().orbitTrap());
        if (!requestedFormula.equals(formulaDefinition)) {
            formulaDefinition = requestedFormula;
            activeFrame = null;
            retainedFrame = null;
            frameCache.clear();
        }

        Viewport viewport = scene.viewport();

        boolean deepZoom = DeepZoomRenderPolicy.isDeepZoom(scene, target);
        InteractiveRenderMode presentationMode =
                DeepZoomRenderPolicy.presentationMode(scene, target);
        ColoringStrategy coloring = scene.coloring().createStrategy();
        boolean refinedDisplay = presentationMode == InteractiveRenderMode.REFINED;

        BigDecimal zoomFactor = defaultViewport.scaleExact()
                .divide(viewport.scaleExact(), viewport.mathContext());

        zoomChangedHandler.accept(
                zoomFactor
        );

        int maxIterations =
                scene.iterations().maxIterations(
                        defaultViewport.scaleExact(),
                        viewport.scaleExact()
                );

        RenderJob renderRequest =
                new RenderJob(
                        formulaDefinition,
                        viewport,
                        target.width(),
                        target.height(),
                        maxIterations,
                        target.priority(),
                        surface.approximatePreviewCoverage(viewport)
                );

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

        /* Color reusable sample data when the displayed image cannot be shifted. */
        if (!imageReused && !refinedDisplay) {

            surface.displayReadyPixels(
                    activeFrame,
                    coloring
            );
        }

        /*
         * --------------------------------
         * CALCULATE MISSING
         * +
         * COLOR MISSING
         * --------------------------------
         */

        renderService.render(
                activeFrame,
                Platform::runLater,

                progress -> {
                    surface.displayProgress(
                            progress.outsideApproximateCoverage(),
                            coloring,
                            presentationMode
                    );
                },

                completedFrame -> {

                    frameCache.put(completedFrame);

                    ColoringStrategy completedColoring =
                            scene.coloring().createStrategy(completedFrame.samplePlane());

                    if (refinedDisplay) {
                        surface.beginRefinedRender(completedFrame);
                        antialiasService.refine(
                                completedFrame,
                                completedColoring,
                                scene.antialiasing().samplingPattern(),
                                surface.refinedPixelSnapshot(completedFrame),
                                Platform::runLater,
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
                                    renderActivity.finish(renderGeneration);
                                    error.printStackTrace();
                                }
                        );
                        return;
                    }

                    if (initialFramePending
                            || completedFrame.request().approximateCoverage().isPresent()) {
                        surface.displayReadyPixels(
                                completedFrame,
                                completedColoring
                        );
                        initialFramePending = false;
                    }

                    surface.completeProgressiveRender(
                            completedFrame,
                            scene
                    );

                    /* The current AA calculator converts subpixel coordinates to double. */
                    if (deepZoom) {
                        renderActivity.finish(renderGeneration);
                        return;
                    }

                    antialiasService.refine(
                            completedFrame,
                            completedColoring,
                            scene.antialiasing().samplingPattern(),
                            surface.refinedPixelSnapshot(completedFrame),
                            Platform::runLater,
                            (region, colors) -> surface.applyAntialiasing(
                                    completedFrame,
                                    region,
                                    colors
                            ),
                            () -> renderActivity.finish(renderGeneration),
                            error -> {
                                renderActivity.finish(renderGeneration);
                                error.printStackTrace();
                            }
                    );

                },

                error -> {
                    renderActivity.finish(renderGeneration);
                    error.printStackTrace();
                }
        );
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

    @Override
    public void close() {
        antialiasService.close();
        renderService.close();
        recolorExecutor.shutdownNow();
        renderActivity.cancel();
    }
}
