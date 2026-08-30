package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.export.AdaptivePngExportService;
import com.shangin.fractal.export.ExportFileName;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.CompletedRender;
import com.shangin.fractal.render.RenderPriority;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.SamplingPattern;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.animation.PauseTransition;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Interactive fractal viewport that translates resize, scroll, and drag events
 * into camera updates and debounced render requests.
 */
public class FractalView extends StackPane {

    // for resize only
    private static final Duration RESIZE_DELAY = Duration.millis(200);
    private final PauseTransition resizeDebounce = new PauseTransition(RESIZE_DELAY);

    //  zoom interactions
    private static final Duration INTERACTION_DELAY = Duration.millis(75);
    private static final double SWIPE_PAN_FRACTION = 0.2;
    private final PauseTransition interactionDebounce = new PauseTransition(INTERACTION_DELAY);
    private double lastDragX;
    private double lastDragY;
    private boolean panning;
    private boolean panChanged;

    private final FractalSurface fractalSurface = new FractalSurface();
    private final FractalRenderController renderController = new FractalRenderController(fractalSurface);
    private final FractalCamera camera;
    private FractalScene scene;
    private RenderPriority renderPriority = RenderPriority.center();
    private Consumer<Viewport> viewportChangedHandler = ignored -> {};

    private Viewport panSourceViewport;
    private Viewport trackpadPanSourceViewport;
    private boolean trackpadScrollActive;

    public FractalView(
            FractalPreset initialFractal,
            PalettePreset initialPalette
    ) {
        scene = FractalScene.create(initialFractal, initialPalette);
        camera = new FractalCamera(initialFractal);
        configurePalette(initialPalette);
        configureResize();
        configureZoom();
        configurePan();
        configureTrackpadGestures();
        getChildren().add(fractalSurface);
        interactionDebounce.setOnFinished(event -> finishInteraction());
        fractalSurface.setOnOutputScaleChanged(this::scheduleResize);
    }

    public void setFractal(FractalPreset preset) {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        interactionDebounce.stop();
        renderController.cancelCurrent();
        camera.setPreset(preset, width, height);
        scene = scene.withFractal(preset, camera.viewport());
        resetPriority();
        recalculate();
    }

    public void resetView() {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        interactionDebounce.stop();
        renderController.cancelCurrent();
        camera.reset(width, height);
        scene = scene.withViewport(camera.viewport());
        resetPriority();
        fractalSurface.showPreview(camera.viewport());
        recalculate();
    }

    public void setCenter(double centerReal, double centerImaginary) {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        if (!camera.setCenter(centerReal, centerImaginary, width, height)) {
            viewportChangedHandler.accept(camera.viewport());
            return;
        }

        resetPriority();
        cameraChanged();
    }


    private void configurePalette(PalettePreset preset) {
        Palette palette = preset.palette();
        fractalSurface.setPreviewBackground(palette);
    }

    public void setPalette(PalettePreset preset) {
        ColoringSettings settings = new ColoringSettings(preset);
        scene = scene.withColoring(settings);
        configurePalette(preset);
        renderController.cancelCurrent();
        fractalSurface.recolor(settings.createStrategy(), settings);
        recalculate();
    }

    public void setSamplingPattern(SamplingPattern pattern) {
        scene = scene.withAntialiasing(new AntialiasSettings(
                pattern,
                scene.antialiasing().renderMode()
        ));
        renderController.cancelCurrent();
        fractalSurface.invalidateRefinement();
        recalculate();
    }

    public void setInteractiveRenderMode(InteractiveRenderMode renderMode) {
        scene = scene.withAntialiasing(new AntialiasSettings(
                scene.antialiasing().samplingPattern(),
                renderMode
        ));
        renderController.cancelCurrent();
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
        setOnScrollStarted(event -> {
            trackpadScrollActive = true;
            event.consume();
        });

        setOnScrollFinished(event -> {
            trackpadScrollActive = false;
            event.consume();
        });

        setOnScroll(event -> {
            if (trackpadScrollActive || event.isInertia() || event.getDeltaX() != 0.0) {
                panByTrackpadScroll(event);
                event.consume();
                return;
            }

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

            renderPriority = new RenderPriority(
                    Math.clamp(x / surfaceWidth, 0.0, 1.0),
                    Math.clamp(y / surfaceHeight, 0.0, 1.0));

            cameraChanged();
            event.consume();
        });
    }

    private void panByTrackpadScroll(javafx.scene.input.ScrollEvent event) {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2
                || (event.getDeltaX() == 0.0 && event.getDeltaY() == 0.0)) {
            return;
        }

        if (trackpadPanSourceViewport == null) {
            trackpadPanSourceViewport = camera.viewport();
            interactionDebounce.stop();
            renderController.cancelCurrent();
        }

        if (!camera.pan(event.getDeltaX(), event.getDeltaY(), width, height)) {
            return;
        }

        resetPriority();
        fractalSurface.showPreview(camera.viewport());
        interactionDebounce.playFromStart();
    }

    private void finishInteraction() {
        if (trackpadPanSourceViewport != null) {
            camera.snapToRenderGrid(
                    trackpadPanSourceViewport,
                    fractalSurface.renderWidth(),
                    fractalSurface.renderHeight()
            );
            trackpadPanSourceViewport = null;
        }

        recalculate();
    }

    private void configureTrackpadGestures() {
        setOnZoom(event -> {
            double surfaceWidth = fractalSurface.getWidth();
            double surfaceHeight = fractalSurface.getHeight();
            int logicalWidth = (int) surfaceWidth;
            int logicalHeight = (int) surfaceHeight;
            int renderWidth = fractalSurface.renderWidth();
            int renderHeight = fractalSurface.renderHeight();

            if (logicalWidth < 2 || logicalHeight < 2
                    || renderWidth < 2 || renderHeight < 2) {
                return;
            }

            double zoomFactor = event.getZoomFactor();
            if (!Double.isFinite(zoomFactor) || zoomFactor <= 0.0) {
                return;
            }

            boolean changed = camera.zoomBy(
                    event.getX(),
                    event.getY(),
                    1.0 / zoomFactor,
                    logicalWidth,
                    logicalHeight,
                    renderWidth,
                    renderHeight
            );

            if (changed) {
                renderPriority = new RenderPriority(
                        Math.clamp(event.getX() / surfaceWidth, 0.0, 1.0),
                        Math.clamp(event.getY() / surfaceHeight, 0.0, 1.0)
                );
                cameraChanged();
            }

            event.consume();
        });

        setOnSwipeLeft(event -> {
            panBySwipe(-SWIPE_PAN_FRACTION, 0.0);
            event.consume();
        });
        setOnSwipeRight(event -> {
            panBySwipe(SWIPE_PAN_FRACTION, 0.0);
            event.consume();
        });
        setOnSwipeUp(event -> {
            panBySwipe(0.0, -SWIPE_PAN_FRACTION);
            event.consume();
        });
        setOnSwipeDown(event -> {
            panBySwipe(0.0, SWIPE_PAN_FRACTION);
            event.consume();
        });
    }

    private void panBySwipe(double horizontalFraction, double verticalFraction) {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        Viewport sourceViewport = camera.viewport();
        boolean changed = camera.pan(
                horizontalFraction * width,
                verticalFraction * height,
                width,
                height
        );

        if (!changed) {
            return;
        }

        interactionDebounce.stop();
        renderController.cancelCurrent();
        fractalSurface.showPreview(camera.viewport());
        camera.snapToRenderGrid(
                sourceViewport,
                fractalSurface.renderWidth(),
                fractalSurface.renderHeight()
        );
        resetPriority();
        recalculate();
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
        resetPriority();
        recalculate();
    }

    private void recalculate() {
        int logicalWidth = (int) getWidth();
        int logicalHeight = (int) getHeight();

        if (logicalWidth < 2 || logicalHeight < 2) {
            return;
        }

        int renderWidth = fractalSurface.renderWidth();
        int renderHeight = fractalSurface.renderHeight();

        if (renderWidth < 2 || renderHeight < 2) {
            return;
        }

        Viewport defaultViewport =
                camera.defaultViewport(
                        logicalWidth,
                        logicalHeight
                );

        scene = scene.withViewport(camera.viewport());
        viewportChangedHandler.accept(scene.viewport());

        RenderTarget target = new RenderTarget(
                renderWidth,
                renderHeight,
                renderPriority
        );

        renderController.render(
                scene,
                target,
                defaultViewport
        );
    }

    public void close() {
        renderController.close();
    }

    public boolean hasCompletedFrame() {
        return fractalSurface.hasCompletedFrame();
    }

    public void exportAntialiasedPng(
            AdaptivePngExportService exportService,
            Path path,
            Consumer<Path> onSuccess,
            Consumer<Throwable> onError
    ) {
        CompletedRender completed = fractalSurface.completedRender();

        if (completed == null) {
            throw new IllegalStateException("No completed frame is available");
        }

        exportService.export(
                completed.frame(),
                completed.scene().coloring().createStrategy(),
                path,
                onSuccess,
                onError
        );
    }

    public String suggestedExportFileName() {
        CompletedRender completed = fractalSurface.completedRender();

        if (completed == null) {
            return "fractal.png";
        }

        FractalScene completedScene = completed.scene();
        Viewport viewport = completedScene.viewport();

        return ExportFileName.create(
                completedScene.fractal().toString(),
                completedScene.coloring().palette().toString(),
                viewport.centerReal(),
                viewport.centerImaginary()
        );
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

                resetPriority();
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

    public void setOnViewportChanged(Consumer<Viewport> handler) {
        viewportChangedHandler = java.util.Objects.requireNonNull(handler);
        viewportChangedHandler.accept(camera.viewport());
    }

    private void resetPriority() {
        renderPriority = RenderPriority.center();
    }
}
