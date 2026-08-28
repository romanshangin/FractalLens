package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.RenderPriority;
import javafx.animation.PauseTransition;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.function.DoubleConsumer;

public class FractalView extends StackPane {

    // for resize only
    private static final Duration RESIZE_DELAY = Duration.millis(200);
    private final PauseTransition resizeDebounce = new PauseTransition(RESIZE_DELAY);

    //  zoom interactions
    private static final Duration INTERACTION_DELAY = Duration.millis(75);
    private final PauseTransition interactionDebounce = new PauseTransition(INTERACTION_DELAY);
    private double lastDragX;
    private double lastDragY;
    private boolean panning;
    private boolean panChanged;

    private final FractalSurface fractalSurface = new FractalSurface();
    private final FractalRenderController renderController = new FractalRenderController(fractalSurface);
    private final FractalCamera camera;
    private ColoringStrategy coloring;

    private Viewport panSourceViewport;

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
        renderController.setFractal(preset);
    }

    public void setFractal(FractalPreset preset) {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        interactionDebounce.stop();
        renderController.cancelCurrent();
        configureFractal(preset);
        camera.setPreset(preset, width, height);
        renderController.resetPriority();
        recalculate();
    }


    private void configurePalette(PalettePreset preset) {
        Palette palette = preset.palette();
        coloring = new SmoothPaletteColoring(palette);
        renderController.setColoring(coloring);
        fractalSurface.setPreviewBackground(palette);
    }

    public void setPalette(PalettePreset preset) {
        configurePalette(preset);
        renderController.cancelCurrent();
        fractalSurface.recolor(coloring);
        recalculate();
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
        setOnScroll(event -> {
            double x = event.getX();
            double y = event.getY();

            double surfaceWidth = fractalSurface.getWidth();
            double surfaceHeight = fractalSurface.getHeight();

            int logicalWidth = (int) surfaceWidth;
            int logicalHeight = (int) surfaceHeight;

            int renderWidth = fractalSurface.renderWidth();
            int renderHeight = fractalSurface.renderHeight();

            if (logicalWidth < 2 || logicalHeight < 2 || renderWidth < 2 || renderHeight < 2) {
                return;
            }

            double deltaY = event.getDeltaY();

            if (deltaY == 0.0) {
                return;
            }

            boolean changed;

            if (deltaY > 0) {
                changed = camera.zoomIn(
                        x,
                        y,
                        logicalWidth,
                        logicalHeight,
                        renderWidth,
                        renderHeight);
            } else {
                changed = camera.zoomOut(
                        x,
                        y,
                        logicalWidth,
                        logicalHeight);
            }

            if (!changed) {
                event.consume();
                return;
            }

            renderController.setPriority(new RenderPriority(
                    Math.clamp(x / surfaceWidth, 0.0, 1.0),
                    Math.clamp(y / surfaceHeight, 0.0, 1.0))
            );

            cameraChanged();
            event.consume();
        });
    }

    private void scheduleResize() {
        renderController.cancelCurrent();
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
        renderController.resetPriority();
        recalculate();
    }

    private void recalculate() {
        int logicalWidth = (int) getWidth();
        int logicalHeight = (int) getHeight();

        if (logicalWidth < 2 || logicalHeight < 2) {
            return;
        }

        Viewport defaultViewport =
                camera.defaultViewport(
                        logicalWidth,
                        logicalHeight
                );

        renderController.render(
                camera.viewport(),
                defaultViewport
        );
    }

    public void close() {
        renderController.close();
    }

    private void cameraChanged() {
        fractalSurface.showPreview(camera.viewport());
        renderController.cancelCurrent();
        interactionDebounce.playFromStart();
    }

    private void configurePan() {
        setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            panSourceViewport = camera.viewport();

            interactionDebounce.stop();
            lastDragX = event.getX();
            lastDragY = event.getY();
            panning = true;
            panChanged = false;
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

            if (!panChanged) {
                renderController.cancelCurrent();
                panChanged = true;
            }

            fractalSurface.showPreview(camera.viewport());
            event.consume();
        });

        setOnMouseReleased(event -> {
            if (!panning) {
                return;
            }
            panning = false;
            if (panChanged) {
                camera.snapToRenderGrid(
                        panSourceViewport,
                        fractalSurface.renderWidth(),
                        fractalSurface.renderHeight()
                );

                renderController.resetPriority();
                recalculate();
            }
            event.consume();
        });
    }

    public void setOnZoomChanged(
            DoubleConsumer handler
    ) {
        renderController.setOnZoomChanged(
                handler
        );
    }
}