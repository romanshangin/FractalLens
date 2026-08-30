package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.ui.FractalSurface;
import javafx.application.Platform;

import java.util.Objects;
import java.util.function.DoubleConsumer;

/**
 * Coordinates render requests between the camera-facing UI and the background
 * rendering pipeline, including iteration policy, frame reuse, and coloring.
 */
public final class FractalRenderController implements AutoCloseable {

    private final FractalSurface surface;
    private final FractalRenderService renderService = new FractalRenderService();
    private final InteractiveAntialiasService antialiasService =
            new InteractiveAntialiasService();

    private FractalCalculator calculator;
    private FractalPreset calculatorPreset;
    private RenderFrame activeFrame;
    private RenderFrame retainedFrame;
    private boolean initialFramePending = true;
    private final FrameReusePlanner frameReusePlanner = new FrameReusePlanner();

    public FractalRenderController(FractalSurface surface) {
        this.surface = Objects.requireNonNull(surface);
    }

    private DoubleConsumer zoomChangedHandler = ignored -> {};

    public void cancelCurrent() {
        renderService.cancelCurrent();
        antialiasService.cancelCurrent();
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
        antialiasService.cancelCurrent();

        if (calculatorPreset != scene.fractal()) {
            calculatorPreset = scene.fractal();
            calculator = new FractalCalculator(scene.fractal().createFormula());
            activeFrame = null;
            retainedFrame = null;
        }

        ColoringStrategy coloring = scene.coloring().createStrategy();
        boolean refinedDisplay = scene.antialiasing().renderMode()
                == InteractiveRenderMode.REFINED;
        Viewport viewport = scene.viewport();

        double zoomFactor =
                defaultViewport.scale()
                        / viewport.scale();

        zoomChangedHandler.accept(
                zoomFactor
        );

        int maxIterations =
                scene.iterations().maxIterations(
                        defaultViewport.scale(),
                        viewport.scale()
                );

        RenderRequest renderRequest =
                new RenderRequest(
                        calculator,
                        viewport,
                        target.width(),
                        target.height(),
                        maxIterations,
                        target.priority()
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

        RenderFrame sourceFrame = reuseSelection.sourceFrame();
        FrameReuseResult reuseResult = reuseSelection.result();

        RenderFrame targetFrame =
                reuseResult.frame();

        activeFrame =
                targetFrame;

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

        surface.beginProgressiveRender(targetFrame);

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

        if (refinedDisplay) {
            surface.hideProgressiveRender(activeFrame);
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
                    if (!refinedDisplay && !initialFramePending) {
                        surface.displayProgress(
                                progress,
                                coloring
                        );
                    }
                },

                completedFrame -> {

                    if (refinedDisplay) {
                        surface.beginRefinedRender(completedFrame);
                        antialiasService.refine(
                                completedFrame,
                                coloring,
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
                                },
                                error -> {
                                    surface.displayReadyPixels(completedFrame, coloring);
                                    surface.completeProgressiveRender(completedFrame, scene);
                                    initialFramePending = false;
                                    error.printStackTrace();
                                }
                        );
                        return;
                    }

                    if (initialFramePending) {
                        surface.displayReadyPixels(
                                completedFrame,
                                coloring
                        );
                        initialFramePending = false;
                    }

                    surface.completeProgressiveRender(
                            completedFrame,
                            scene
                    );

                    antialiasService.refine(
                            completedFrame,
                            coloring,
                            scene.antialiasing().samplingPattern(),
                            surface.refinedPixelSnapshot(completedFrame),
                            Platform::runLater,
                            (region, colors) -> surface.applyAntialiasing(
                                    completedFrame,
                                    region,
                                    colors
                            ),
                            () -> {},
                            Throwable::printStackTrace
                    );

                },

                Throwable::printStackTrace
        );
    }

    public void setOnZoomChanged(
            DoubleConsumer handler
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
    }
}
