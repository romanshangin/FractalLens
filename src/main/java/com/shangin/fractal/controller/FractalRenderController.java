package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.config.AdaptiveIterationPolicy;
import com.shangin.fractal.config.FractalSettings;
import com.shangin.fractal.config.IterationPolicy;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderPriority;
import com.shangin.fractal.render.RenderRequest;
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

        RenderRequest request = new RenderRequest(
                calculator,
                viewport,
                renderWidth,
                renderHeight,
                maxIterations,
                renderPriority);

        surface.beginProgressiveRender();

        renderService.render(request,
                Platform::runLater,
                progress -> surface.displayProgress(progress, coloring),
                data -> surface.completeProgressiveRender(data, request.viewport()),
                Throwable::printStackTrace);
    }

    @Override
    public void close() {
        renderService.close();
    }
}