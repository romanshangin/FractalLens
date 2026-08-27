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

    public void render(
            Viewport viewport,
            Viewport defaultViewport
    ) {
        Objects.requireNonNull(viewport);
        Objects.requireNonNull(defaultViewport);

        if (calculator == null || coloring == null) {
            return;
        }

        int renderWidth = surface.renderWidth();
        int renderHeight = surface.renderHeight();

        if (renderWidth < 2 || renderHeight < 2) {
            return;
        }

        int maxIterations = iterationPolicy.maxIterations(
                settings.maxIterations(),
                defaultViewport.scale(),
                viewport.scale());

        RenderRequest renderRequest = new RenderRequest(
                calculator,
                viewport,
                renderWidth,
                renderHeight,
                maxIterations,
                renderPriority);

        RenderFrame sourceFrame = activeFrame;

        FrameReuseResult reuseResult = frameReusePlanner.plan(
                sourceFrame,
                renderRequest);

        activeFrame = reuseResult.frame();


        // temp log
        int reusedPixels =
                activeFrame.validity()
                        .readyPixelCount();

        int totalPixels =
                activeFrame.fractalData()
                        .size();

        double reusedPercent =
                100.0 * reusedPixels / totalPixels;

        System.out.printf(
                "Frame reuse: %,d / %,d pixels (%.1f%%)%n",
                reusedPixels,
                totalPixels,
                reusedPercent
        );
        // temp log

        surface.beginProgressiveRender();

        boolean imageReused = false;

        if (sourceFrame != null
                && reuseResult.reused()) {

            imageReused =
                    surface.reuseDisplayedPixels(
                            sourceFrame,
                            reuseResult.shift()
                                    .orElseThrow()
                    );
        }

        if (!imageReused) {
            surface.displayReadyPixels(
                    activeFrame,
                    coloring
            );
        }

        renderService.render(
                activeFrame,
                Platform::runLater,

                progress ->
                        surface.displayProgress(
                                progress,
                                coloring
                        ),

                completedFrame ->
                        surface.completeProgressiveRender(
                                completedFrame,
                                completedFrame.request()
                                        .viewport()
                        ),

                Throwable::printStackTrace
        );
    }

    @Override
    public void close() {
        renderService.close();
    }
}