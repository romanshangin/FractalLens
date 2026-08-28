package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.config.AdaptiveIterationPolicy;
import com.shangin.fractal.config.FractalSettings;
import com.shangin.fractal.config.IterationPolicy;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.ui.FractalSurface;
import javafx.application.Platform;

import java.util.Objects;
import java.util.function.DoubleConsumer;

/**
 * Coordinates render requests between the camera-facing UI and the background
 * rendering pipeline, including iteration policy, frame reuse, and coloring.
 */
public final class FractalRenderController implements AutoCloseable {

    private static final int ITERATIONS_PER_ZOOM_LEVEL = 50;

    private final FractalSurface surface;
    private final FractalRenderService renderService = new FractalRenderService();
    private final FractalSettings settings = new FractalSettings();
    private final IterationPolicy iterationPolicy = new AdaptiveIterationPolicy(ITERATIONS_PER_ZOOM_LEVEL);

    private FractalCalculator calculator;
    private ColoringStrategy coloring;
    private RenderPriority renderPriority = RenderPriority.center();
    private RenderFrame activeFrame;
    private final FrameReusePlanner frameReusePlanner = new FrameReusePlanner();

    public FractalRenderController(FractalSurface surface) {
        this.surface = Objects.requireNonNull(surface);
    }

    private DoubleConsumer zoomChangedHandler = ignored -> {};

    public void setFractal(FractalPreset preset) {
        Objects.requireNonNull(preset);
        calculator = new FractalCalculator(preset.createFormula());
    }

    public void setColoring(ColoringStrategy coloring) {
        this.coloring = Objects.requireNonNull(coloring);
    }

    public void setPriority(RenderPriority priority) {
        renderPriority = Objects.requireNonNull(priority);
    }

    public void resetPriority() {
        renderPriority = RenderPriority.center();
    }

    public void cancelCurrent() {
        renderService.cancelCurrent();
    }

    /** Starts a progressive render for the supplied viewport. */
    public void render(
            Viewport viewport,
            Viewport defaultViewport
    ) {
        Objects.requireNonNull(viewport);
        Objects.requireNonNull(defaultViewport);

        if (calculator == null
                || coloring == null) {
            return;
        }

        double zoomFactor =
                defaultViewport.scale()
                        / viewport.scale();

        zoomChangedHandler.accept(
                zoomFactor
        );

        int renderWidth =
                surface.renderWidth();

        int renderHeight =
                surface.renderHeight();

        if (renderWidth < 2
                || renderHeight < 2) {
            return;
        }

        int maxIterations =
                iterationPolicy.maxIterations(
                        settings.maxIterations(),
                        defaultViewport.scale(),
                        viewport.scale()
                );

        RenderRequest renderRequest =
                new RenderRequest(
                        calculator,
                        viewport,
                        renderWidth,
                        renderHeight,
                        maxIterations,
                        renderPriority
                );

        RenderFrame sourceFrame =
                activeFrame;

        /*
         * --------------------------------
         * DATA REUSE
         * --------------------------------
         */

        FrameReuseResult reuseResult =
                frameReusePlanner.plan(
                        sourceFrame,
                        renderRequest
                );

        RenderFrame targetFrame =
                reuseResult.frame();

        activeFrame =
                targetFrame;

        /*
         * --------------------------------
         * PREPARE IMAGE
         * --------------------------------
         */

        surface.beginProgressiveRender();

        boolean imageReused =
                false;

        if (sourceFrame != null
                && reuseResult.reused()) {

            imageReused =
                    surface.reuseDisplayedPixels(
                            sourceFrame,
                            reuseResult.shift()
                                    .orElseThrow()
                    );

        }

        /* Color reusable sample data when the displayed image cannot be shifted. */
        if (!imageReused) {

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

                progress -> surface.displayProgress(
                        progress,
                        coloring
                ),

                completedFrame -> {

                    surface.completeProgressiveRender(
                            completedFrame,
                            completedFrame.request()
                                    .viewport()
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
        renderService.close();
    }
}
