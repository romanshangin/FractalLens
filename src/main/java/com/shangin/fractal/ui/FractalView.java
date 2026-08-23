package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.config.AdaptiveIterationPolicy;
import com.shangin.fractal.config.FractalSettings;
import com.shangin.fractal.config.IterationPolicy;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderRequest;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

public class FractalView extends StackPane {

    // for resize only
    private static final Duration RESIZE_DELAY = Duration.millis(200);
    private final PauseTransition resizeDebounce = new PauseTransition(RESIZE_DELAY);

    // zoom int/out + drag&drop
    private static final Duration INTERACTION_DELAY = Duration.millis(75);
    private final PauseTransition interactionDebounce = new PauseTransition(INTERACTION_DELAY);
    private double lastDragX;
    private double lastDragY;
    private boolean panning;

    private static final int ITERATIONS_PER_ZOOM_LEVEL = 50;
    private final IterationPolicy iterationPolicy = new AdaptiveIterationPolicy(ITERATIONS_PER_ZOOM_LEVEL);
    private final FractalCamera camera;
    private final FractalRenderService renderService = new FractalRenderService();
    private final FractalSettings settings = new FractalSettings();
    private final FractalSurface fractalSurface = new FractalSurface();

    private FractalCalculator calculator;
    private ColoringStrategy coloring;

    public FractalView(
            FractalPreset initialFractal,
            PalettePreset initialPalette
    ) {
        camera = new FractalCamera(initialFractal);
        configureFractal(initialFractal);
        configurePalette(initialPalette);
        configureResize();
        configureZoom();
        configurePan();
        getChildren().add(fractalSurface);
        interactionDebounce.setOnFinished(event -> recalculate());
        fractalSurface.setOnOutputScaleChanged(this::scheduleResize);
    }

    private void configureFractal(FractalPreset preset) {
        calculator = new FractalCalculator(
                preset.createFormula());
    }

    public void setFractal(FractalPreset preset) {

        int width = (int) getWidth();
        int height = (int) getHeight();
        if (width < 2 || height < 2) {
            return;
        }

        renderService.cancelCurrent();
        configureFractal(preset);
        camera.setPreset(preset, width, height);
        recalculate();
    }


    private void configurePalette(PalettePreset preset) {
        Palette palette = preset.palette();
        coloring = new SmoothPaletteColoring(palette);
        fractalSurface.setPreviewBackground(palette);
    }

    public void setPalette(PalettePreset preset) {
        configurePalette(preset);
        fractalSurface.recolor(coloring);
    }

    private void configureResize() {
        resizeDebounce.setOnFinished(event -> resizeAndRender());

        widthProperty().addListener(
                (observable, oldValue, newValue)
                        -> scheduleResize());

        heightProperty().addListener(
                (observable, oldValue, newValue)
                        -> scheduleResize());
    }

    private void configureZoom() {

        interactionDebounce.setOnFinished(
                event -> recalculate()
        );

        setOnScroll(event -> {
            int logicalWidth = (int) getWidth();
            int logicalHeight = (int) getHeight();

            if (logicalWidth < 2 || logicalHeight < 2) {
                return;
            }

            double deltaY = event.getDeltaY();

            if (deltaY == 0.0) {
                return;
            }

            boolean changed;

            if (deltaY > 0) {
                changed = camera.zoomIn(
                        event.getX(),
                        event.getY(),
                        logicalWidth,
                        logicalHeight,
                        fractalSurface.renderWidth(),
                        fractalSurface.renderHeight());
            } else {
                changed = camera.zoomOut(
                        event.getX(),
                        event.getY(),
                        logicalWidth,
                        logicalHeight);
            }

            if (!changed) {
                event.consume();
                return;
            }

            cameraChanged();
            event.consume();
        });
    }

    private void scheduleResize() {
        renderService.cancelCurrent();
        resizeDebounce.playFromStart();
    }

    private void resizeAndRender() {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }
        fractalSurface.resizeBuffer(width, height);
        camera.resize(width, height);
        recalculate();
    }

    private void recalculate() {
        int logicalWidth = (int) getWidth();
        int logicalHeight = (int) getHeight();
        int renderWidth = fractalSurface.renderWidth();
        int renderHeight = fractalSurface.renderHeight();

        if (logicalWidth < 2 || logicalHeight < 2 || renderWidth < 2 || renderHeight < 2) {
            return;
        }

        int maxIterations = effectiveMaxIterations(logicalWidth, logicalHeight);

        RenderRequest request = new RenderRequest(calculator, camera.viewport(), renderWidth, renderHeight, maxIterations);

        renderService.render(request, Platform::runLater, data -> fractalSurface.display(data, coloring, request.viewport()), Throwable::printStackTrace);
    }


    private int effectiveMaxIterations(int width, int height) {

        Viewport defaultViewport = camera.defaultViewport(width, height);

        return iterationPolicy.maxIterations(settings.maxIterations(), defaultViewport.scale(), camera.viewport().scale());
    }

    public void close() {
        renderService.close();
    }

    private void cameraChanged() {
        fractalSurface.showPreview(camera.viewport());
        renderService.cancelCurrent();
        interactionDebounce.playFromStart();
    }

    private void configurePan() {
        setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }

            interactionDebounce.stop();
            renderService.cancelCurrent();
            lastDragX = event.getX();
            lastDragY = event.getY();
            panning = true;
            event.consume();
        });

        setOnMouseDragged(event -> {
            if (!panning || !event.isPrimaryButtonDown()) {
                return;
            }

            int width = (int) getWidth();
            int height = (int) getHeight();

            if (width < 2 || height < 2) {
                return;
            }

            double deltaX = event.getX() - lastDragX;
            double deltaY = event.getY() - lastDragY;

            lastDragX = event.getX();
            lastDragY = event.getY();

            boolean changed = camera.pan(deltaX, deltaY, width, height);

            if (!changed) {
                event.consume();
                return;
            }

            fractalSurface.showPreview(camera.viewport());
            renderService.cancelCurrent();
            event.consume();
        });

        setOnMouseReleased(event -> {
            if (!panning) {
                return;
            }
            panning = false;
            recalculate();
            event.consume();
        });
    }
}