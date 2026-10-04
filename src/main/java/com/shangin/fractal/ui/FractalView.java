package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.controller.RenderStatus;
import com.shangin.fractal.export.AdaptivePngExportService;
import com.shangin.fractal.export.ExportFileName;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.CompletedRender;
import com.shangin.fractal.render.RenderPriority;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.JuliaParameters;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.SamplingPattern;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.animation.PauseTransition;
import javafx.animation.AnimationTimer;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.List;

/**
 * Interactive fractal viewport that translates resize, scroll, and drag events
 * into camera updates and debounced render requests.
 */
public class FractalView extends StackPane {

    // Keep the preview responsive, but wait until native window resizing settles
    // before spending work on a render that would immediately be superseded.
    private static final Duration RESIZE_SETTLE_DELAY = Duration.millis(75);
    private final PauseTransition resizeRender = new PauseTransition(RESIZE_SETTLE_DELAY);
    private boolean resizePending;

    // Coalesce wheel events, but flush immediately when a pinch gesture finishes.
    private static final double SWIPE_PAN_FRACTION = 0.2;
    private final PauseTransition interactionDebounce = new PauseTransition();
    private final InteractionRenderDebouncer interactionRender = new InteractionRenderDebouncer(
            delayMs -> {
                interactionDebounce.stop();
                interactionDebounce.setDuration(Duration.millis(delayMs));
                interactionDebounce.playFromStart();
            },
            interactionDebounce::stop,
            this::finishInteraction
    );
    private double lastDragX;
    private double lastDragY;
    private boolean panning;
    private boolean panChanged;

    private final boolean loadingScreenEnabled;
    private LoadingScreen loadingScreen;

    private final RenderModeIndicator modeIndicator = new RenderModeIndicator();
    private Consumer<Boolean> deepZoomChangedHandler = ignored -> {};
    private final FractalSurface fractalSurface = new FractalSurface();
    private final FractalRenderController renderController;
    private final FractalCamera camera;
    private final FractalContextMenu contextMenu;
    private FractalScene scene;
    private RenderPriority renderPriority = RenderPriority.center();
    private Consumer<Viewport> viewportChangedHandler = ignored -> {};
    private Consumer<Boolean> renderingChangedHandler = ignored -> {};
    private Runnable colorCyclingStoppedHandler = () -> {};
    private boolean rendering;
    private boolean colorCyclePaused;
    private boolean colorCycling;
    private long lastCycleTick;
    private long lastRecolorTick;
    private double colorCycleOffset;
    private static final long RECOLOR_INTERVAL_NANOS = 33_333_333L;
    private static final double COLOR_CYCLE_SECONDS = 12.0;
    private final AnimationTimer colorCycleTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            advanceColorCycle(now);
        }
    };

    private Viewport panSourceViewport;
    private Viewport trackpadPanSourceViewport;
    private boolean trackpadScrollActive;

    public FractalView(
            FractalPreset initialFractal,
            PalettePreset initialPalette
    ) {
        this(FractalScene.create(initialFractal, initialPalette),
                System.getProperty("os.name", "").startsWith("Mac"), false);
    }

    FractalView(FractalPreset initialFractal, PalettePreset initialPalette, boolean loadingScreenEnabled) {
        this(FractalScene.create(initialFractal, initialPalette), loadingScreenEnabled, false);
    }

    public FractalView(FractalScene restoredScene) {
        this(restoredScene, System.getProperty("os.name", "").startsWith("Mac"), true);
    }

    FractalView(FractalScene initialScene, boolean restored) {
        this(initialScene, System.getProperty("os.name", "").startsWith("Mac"), restored);
    }

    FractalView(FractalScene initialScene, boolean restored, FractalRenderService renderService) {
        this(initialScene, System.getProperty("os.name", "").startsWith("Mac"),
                restored, renderService);
    }

    FractalView(FractalScene initialScene, boolean restored, FractalRenderService renderService,
                FractalRenderController.RecolorOperation recolorOperation) {
        this(initialScene, System.getProperty("os.name", "").startsWith("Mac"),
                restored, renderService, recolorOperation);
    }

    private FractalView(
            FractalScene initialScene,
            boolean loadingScreenEnabled,
            boolean restored
    ) {
        this(initialScene, loadingScreenEnabled, restored, new FractalRenderService());
    }

    private FractalView(
            FractalScene initialScene,
            boolean loadingScreenEnabled,
            boolean restored,
            FractalRenderService renderService
    ) {
        this(initialScene, loadingScreenEnabled, restored, renderService, null);
    }

    private FractalView(
            FractalScene initialScene,
            boolean loadingScreenEnabled,
            boolean restored,
            FractalRenderService renderService,
            FractalRenderController.RecolorOperation recolorOperation
    ) {
        renderController = recolorOperation == null
                ? new FractalRenderController(fractalSurface, renderService)
                : new FractalRenderController(fractalSurface, renderService, recolorOperation);
        this.loadingScreenEnabled = loadingScreenEnabled;
        scene = java.util.Objects.requireNonNull(initialScene, "Initial scene must not be null");
        modeIndicator.setIterations(scene.iterations().baseIterations());
        camera = restored
                ? new FractalCamera(scene.fractal(), scene.viewport())
                : new FractalCamera(scene.fractal());
        colorCycleOffset = scene.coloring().offset();
        configurePalette(scene.coloring().paletteStops());
        configureResize();
        configureZoom();
        configurePan();
        configureTrackpadGestures();
        contextMenu = new FractalContextMenu(this, this::copyCoordinatesAndZoom);
        setFocusTraversable(true);
        setAccessibleText("Fractal canvas");
        setAccessibleHelp("Use arrow keys to pan, Page Up and Page Down to zoom, or Home to reset. Drag to pan, scroll to zoom, or pinch on a trackpad.");
        setOnKeyPressed(event -> {
            if (event.isShortcutDown() || event.isAltDown() || event.isControlDown()) {
                return;
            }
            switch (event.getCode()) {
                case LEFT -> panBySwipe(SWIPE_PAN_FRACTION, 0.0);
                case RIGHT -> panBySwipe(-SWIPE_PAN_FRACTION, 0.0);
                case UP -> panBySwipe(0.0, SWIPE_PAN_FRACTION);
                case DOWN -> panBySwipe(0.0, -SWIPE_PAN_FRACTION);
                case PAGE_UP -> zoom(true);
                case PAGE_DOWN -> zoom(false);
                case HOME -> resetView();
                default -> { return; }
            }
            event.consume();
        });
        getChildren().addAll(fractalSurface, modeIndicator);
        StackPane.setAlignment(modeIndicator, javafx.geometry.Pos.BOTTOM_LEFT);
        StackPane.setMargin(modeIndicator, new javafx.geometry.Insets(12));
        showLoadingScreen();
        renderController.setOnRenderStatusChanged(status -> {
            modeIndicator.setRenderStatus(status);
            if (loadingScreen != null && (status.state() == RenderStatus.State.COMPLETE
                    || status.state() == RenderStatus.State.FAILED)) {
                // On failure expose the existing error indicator instead of leaving a stuck splash.
                loadingScreen.close();
                getChildren().remove(loadingScreen);
                loadingScreen = null;
                fractalSurface.setVisible(true);
                modeIndicator.setVisible(true);
            }
        });
        renderController.setOnRenderProgressChanged(modeIndicator::setProgress);
        renderController.setOnZoomChanged(modeIndicator::setZoom);
        renderController.setOnIterationsChanged(modeIndicator::setIterations);
        renderController.setOnDeepZoomChanged(active -> {
            modeIndicator.setDeepZoom(active);
            deepZoomChangedHandler.accept(active);
        });
        interactionDebounce.setOnFinished(event -> interactionRender.finish());
        fractalSurface.setOnOutputScaleChanged(this::scheduleResize);
        renderController.setOnRenderingChanged(this::renderingChanged);
        colorCycleTimer.start();
    }

    private void showLoadingScreen() {
        if (loadingScreenEnabled && loadingScreen == null) {
            loadingScreen = new LoadingScreen();
            // Keep the surface managed so layout and rendering proceed at the real window size.
            fractalSurface.setVisible(false);
            modeIndicator.setVisible(false);
            getChildren().add(loadingScreen);
        }
    }

    public InteractionLatency latency() { return fractalSurface.latency(); }

    boolean isLatencyIdle() {
        return !rendering && !resizePending && !panning && !interactionRender.hasPendingRender();
    }

    String latencyFixture() {
        CompletedRender completed = fractalSurface.completedRender();
        if (completed == null) return "incomplete";
        var request = completed.frame().request();
        return "width=" + request.width() + "; height=" + request.height()
                + "; viewport=" + request.viewport() + "; iterations=" + request.maxIterations()
                + "; sampling=" + completed.scene().antialiasing().samplingPattern();
    }

    public void setFractal(FractalPreset preset) {
        dismissContextMenu();
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        stopColorCyclingForSceneChange();
        interactionRender.cancel();
        renderController.cancelCurrent();
        showLoadingScreen();
        camera.setPreset(preset, width, height);
        scene = scene.withFractal(preset, camera.viewport());
        resetPriority();
        recalculate();
    }

    /** A bookmark is a scene replacement, including cancellation of queued gestures. */
    public void goTo(Viewport destination) {
        int width = (int) getWidth();
        int height = (int) getHeight();
        if (width < 2 || height < 2) return;
        camera.goTo(destination, width, height,
                Math.max(2, fractalSurface.renderWidth()), Math.max(2, fractalSurface.renderHeight()));
        dismissContextMenu();
        stopColorCyclingForSceneChange();
        interactionRender.cancel();
        renderController.cancelCurrent();
        showLoadingScreen();
        scene = scene.withViewport(camera.viewport());
        resetPriority();
        recalculate();
    }

    private void dismissContextMenu() {
        if (contextMenu != null) contextMenu.hide();
    }

    private void copyCoordinatesAndZoom() {
        Viewport viewport = camera.viewport();
        BigDecimal zoom = camera.defaultViewport(
                        Math.max(2, (int) getWidth()), Math.max(2, (int) getHeight()))
                .scaleExact().divide(viewport.scaleExact(), viewport.mathContext());
        ClipboardContent content = new ClipboardContent();
        content.putString("Real: " + viewport.center().real().stripTrailingZeros().toPlainString()
                + "\nImaginary: " + viewport.center().imaginary().stripTrailingZeros().toPlainString()
                + "\nZoom: " + zoom.stripTrailingZeros().toPlainString());
        Clipboard.getSystemClipboard().setContent(content);
    }

    public void resetView() {
        dismissContextMenu();
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        stopColorCyclingForSceneChange();
        interactionRender.cancel();
        renderController.cancelCurrent();
        showLoadingScreen();
        camera.reset(width, height);
        scene = scene.withViewport(camera.viewport());
        resetPriority();
        fractalSurface.showPreview(camera.viewport());
        recalculate();
    }

    /** Menu and keyboard zoom use the same precision limits as pointer gestures. */
    public void zoom(boolean in) {
        dismissContextMenu();
        int width = (int) getWidth();
        int height = (int) getHeight();
        int renderWidth = fractalSurface.renderWidth();
        int renderHeight = fractalSurface.renderHeight();
        if (width < 2 || height < 2 || renderWidth < 2 || renderHeight < 2) {
            return;
        }
        double centerX = (width - 1) / 2.0;
        double centerY = (height - 1) / 2.0;
        boolean changed = in
                ? camera.zoomIn(centerX, centerY, width, height, renderWidth, renderHeight)
                : camera.zoomOut(centerX, centerY, width, height);
        if (changed) {
            resetPriority();
            cameraChanged();
        }
    }

    public void setCenter(double centerReal, double centerImaginary) {
        dismissContextMenu();
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        if (!camera.setCenter(centerReal, centerImaginary, width, height)) {
            viewportChangedHandler.accept(camera.viewport());
            return;
        }

        stopColorCyclingForSceneChange();
        resetPriority();
        cameraChanged();
    }

    public void setCenter(BigDecimal centerReal, BigDecimal centerImaginary) {
        dismissContextMenu();
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        if (!camera.setCenter(
                new com.shangin.fractal.math.PreciseComplex(centerReal, centerImaginary),
                width, height)) {
            viewportChangedHandler.accept(camera.viewport());
            return;
        }

        stopColorCyclingForSceneChange();
        resetPriority();
        cameraChanged();
    }


    private void configurePalette(PalettePreset preset) {
        Palette palette = preset.palette();
        fractalSurface.setPreviewBackground(palette);
    }

    private void configurePalette(List<ColorStop> stops) {
        fractalSurface.setPreviewBackground(GradientPalette.cached(stops));
    }

    public void setPalette(PalettePreset preset) {
        dismissContextMenu();
        ColoringSettings settings = new ColoringSettings(
                preset, preset.stops(), scene.coloring().colorScale(), scene.coloring().offset(),
                scene.coloring().histogramColoring(), scene.coloring().orbitTrap());
        scene = scene.withColoring(settings);
        colorCycleOffset = settings.offset();
        configurePalette(preset);
        renderController.cancelRecolor();
        if (settings.histogramColoring() || settings.orbitTrap() != OrbitTrap.NONE) {
            renderController.cancelCurrent();
            fractalSurface.invalidateRefinement();
            recalculate();
        } else {
            renderController.recolor(settings);
        }
    }

    void setIterations(IterationSettings settings) {
        dismissContextMenu();
        scene = scene.withIterations(settings);
        renderController.cancelCurrent();
        fractalSurface.invalidateRefinement();
        recalculate();
    }

    void setJuliaParameters(JuliaParameters parameters) {
        dismissContextMenu();
        scene = scene.withJuliaParameters(parameters);
        if (scene.fractal() == FractalPreset.JULIA) {
            renderController.cancelCurrent();
            fractalSurface.invalidateRefinement();
            recalculate();
        }
    }

    public void setPaletteStops(List<ColorStop> stops) {
        dismissContextMenu();
        ColoringSettings current = scene.coloring();
        ColoringSettings settings = new ColoringSettings(
                current.palette(), stops, current.colorScale(), current.offset(),
                current.histogramColoring(), current.orbitTrap());
        scene = scene.withColoring(settings);
        configurePalette(stops);
        renderController.cancelRecolor();
        if (settings.histogramColoring() || settings.orbitTrap() != OrbitTrap.NONE) {
            renderController.cancelCurrent();
            fractalSurface.invalidateRefinement();
            recalculate();
        } else {
            renderController.recolor(settings);
        }
    }

    void resetColorsToDefaults() {
        stopColorCyclingForSceneChange();
        ColoringSettings defaults = new ColoringSettings(PalettePreset.ICE);
        scene = scene.withColoring(defaults);
        colorCycleOffset = defaults.offset();
        configurePalette(defaults.paletteStops());
        renderController.cancelRecolor();
        renderController.cancelCurrent();
        fractalSurface.invalidateRefinement();
        recalculate();
    }

    public void setOrbitTrap(OrbitTrap orbitTrap) {
        stopColorCyclingForSceneChange();
        ColoringSettings current = scene.coloring();
        scene = scene.withColoring(new ColoringSettings(
                current.palette(), current.paletteStops(), current.colorScale(), current.offset(),
                current.histogramColoring(), orbitTrap));
        renderController.cancelCurrent();
        fractalSurface.invalidateRefinement();
        recalculate();
    }

    public void setColorCycling(boolean enabled) {
        dismissContextMenu();
        if (enabled && (scene.coloring().histogramColoring()
                || scene.coloring().orbitTrap() != OrbitTrap.NONE)) {
            colorCycling = false;
            renderController.cancelRecolor();
            colorCyclingStoppedHandler.run();
            return;
        }
        if (!enabled) {
            colorCycling = false;
            lastCycleTick = 0L;
            renderController.cancelRecolor();
            colorCycleOffset = scene.coloring().offset();
            return;
        }
        colorCycling = enabled;
        colorCycleOffset = scene.coloring().offset();
        lastCycleTick = 0L;
    }

    public void setHistogramColoring(boolean enabled) {
        stopColorCyclingForSceneChange();
        ColoringSettings current = scene.coloring();
        ColoringSettings settings = new ColoringSettings(
                current.palette(), current.paletteStops(), current.colorScale(), current.offset(),
                enabled, current.orbitTrap());
        scene = scene.withColoring(settings);
        renderController.cancelCurrent();
        fractalSurface.invalidateRefinement();
        recalculate();
    }

    public void setOnColorCyclingStopped(Runnable handler) {
        colorCyclingStoppedHandler = java.util.Objects.requireNonNull(handler);
    }

    private void stopColorCyclingForSceneChange() {
        dismissContextMenu();
        if (!colorCycling) {
            return;
        }
        colorCycling = false;
        lastCycleTick = 0L;
        renderController.cancelRecolor();
        colorCycleOffset = scene.coloring().offset();
        colorCyclingStoppedHandler.run();
    }

    private void advanceColorCycle(long now) {
        if (!colorCycling || rendering || colorCyclePaused || !fractalSurface.hasCompletedFrame()) {
            lastCycleTick = 0L;
            return;
        }
        if (lastCycleTick == 0L) {
            lastCycleTick = now;
            return;
        }
        double elapsedSeconds = (now - lastCycleTick) / 1_000_000_000.0;
        lastCycleTick = now;
        double offset = (colorCycleOffset
                + elapsedSeconds * 2.0 / COLOR_CYCLE_SECONDS) % 2.0;
        colorCycleOffset = offset;
        ColoringSettings settings = new ColoringSettings(
                scene.coloring().palette(), scene.coloring().paletteStops(),
                scene.coloring().colorScale(), offset,
                scene.coloring().histogramColoring(), scene.coloring().orbitTrap());
        if (now - lastRecolorTick >= RECOLOR_INTERVAL_NANOS) {
            lastRecolorTick = now;
            renderController.recolor(settings, () -> {
                if (hasSameAnimationBase(scene.coloring(), settings)) {
                    scene = scene.withColoring(settings);
                }
            });
        }
    }

    private static boolean hasSameAnimationBase(
            ColoringSettings current,
            ColoringSettings applied
    ) {
        return current.palette() == applied.palette()
                && current.paletteStops().equals(applied.paletteStops())
                && Double.compare(current.colorScale(), applied.colorScale()) == 0
                && current.histogramColoring() == applied.histogramColoring()
                && current.orbitTrap() == applied.orbitTrap();
    }

    private void renderingChanged(boolean active) {
        rendering = active;
        if (active) {
            lastCycleTick = 0L;
            colorCycleOffset = scene.coloring().offset();
            colorCyclePaused = true;
        } else {
            colorCyclePaused = false;
        }
        renderingChangedHandler.accept(active);
    }

    public void setSamplingPattern(SamplingPattern pattern) {
        stopColorCyclingForSceneChange();
        scene = scene.withAntialiasing(new AntialiasSettings(
                pattern,
                scene.antialiasing().renderMode()
        ));
        renderController.cancelCurrent();
        fractalSurface.invalidateRefinement();
        recalculate();
    }

    public void setInteractiveRenderMode(InteractiveRenderMode renderMode) {
        stopColorCyclingForSceneChange();
        scene = scene.withAntialiasing(new AntialiasSettings(
                scene.antialiasing().samplingPattern(),
                renderMode
        ));
        renderController.cancelCurrent();
        recalculate();
    }

    /** Enables precise AA for the current and subsequent Mandelbrot deep frames. */
    public void setDeepAntialiasing(boolean enabled) {
        stopColorCyclingForSceneChange();
        modeIndicator.setDeepAntialiasing(enabled);
        renderController.setDeepAntialiasingEnabled(enabled);
    }

    public void setOnRenderingChanged(Consumer<Boolean> handler) {
        renderingChangedHandler = java.util.Objects.requireNonNull(handler);
    }

    public void setOnRenderError(Consumer<Throwable> handler) {
        renderController.setOnRenderError(handler);
    }

    private void configureResize() {
        resizeRender.setOnFinished(event -> {
            layout();
            if (resizePending) {
                resizeAndRender();
            }
        });

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
            latency().boundary("scroll_finished");
            trackpadScrollActive = false;
            event.consume();
        });

        setOnScroll(event -> {
            latency().input("scroll");
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

        Viewport sourceViewport = camera.viewport();
        if (!camera.pan(event.getDeltaX(), event.getDeltaY(), width, height)) {
            return;
        }

        if (trackpadPanSourceViewport == null) {
            trackpadPanSourceViewport = sourceViewport;
            interactionRender.cancel();
            renderController.cancelCurrent();
            colorCyclePaused = true;
        }

        stopColorCyclingForSceneChange();
        resetPriority();
        fractalSurface.showPreview(camera.viewport());
        interactionRender.requestPanRender();
    }

    private void finishInteraction(boolean preserveIterationLimit) {
        if (trackpadPanSourceViewport != null) {
            camera.snapToRenderGrid(
                    trackpadPanSourceViewport,
                    fractalSurface.renderWidth(),
                    fractalSurface.renderHeight()
            );
            trackpadPanSourceViewport = null;
        }

        recalculate(preserveIterationLimit);
    }

    private void configureTrackpadGestures() {
        setOnZoomStarted(event -> {
            interactionRender.zoomStarted();
            event.consume();
        });

        setOnZoomFinished(event -> {
            latency().boundary("pinch_finished");
            interactionRender.zoomFinished();
            event.consume();
        });

        setOnZoom(event -> {
            latency().input("pinch");
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
        int renderWidth = fractalSurface.renderWidth();
        int renderHeight = fractalSurface.renderHeight();

        if (width < 2 || height < 2 || renderWidth < 2 || renderHeight < 2) {
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

        stopColorCyclingForSceneChange();
        interactionRender.cancel();
        renderController.cancelCurrent();
        fractalSurface.showPreview(camera.viewport());
        camera.snapToRenderGrid(
                sourceViewport,
                renderWidth,
                renderHeight
        );
        resetPriority();
        recalculate(true);
    }

    private void scheduleResize() {
        latency().input("resize");
        dismissContextMenu();
        resizePending = true;
        stopColorCyclingForSceneChange();
        interactionRender.cancel();
        renderController.cancelCurrent();
        colorCyclePaused = true;
        resizeRender.playFromStart();
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (resizePending && fractalSurface.renderHeight() >= 2) {
            showResizePreview();
        }
    }

    private void showResizePreview() {
        int oldHeight = fractalSurface.renderHeight();
        if (camera.isDefaultView()) {
            fractalSurface.showInitialResizePreview();
        } else {
            int newWidth = fractalSurface.renderWidthFor((int) getWidth());
            int newHeight = fractalSurface.renderHeightFor((int) getHeight());
            Viewport preview = new Viewport(camera.viewport().center(),
                    camera.viewport().imaginaryUnitsPerPixelExact(oldHeight).multiply(
                            BigDecimal.valueOf(newHeight - 1L), camera.viewport().mathContext()));
            fractalSurface.showResizePreview(preview, newWidth, newHeight);
        }
    }

    private void resizeAndRender() {
        int width = (int) getWidth();
        int height = (int) getHeight();

        if (width < 2 || height < 2) {
            return;
        }

        int oldRenderHeight = fractalSurface.renderHeight();
        boolean resizeDefaultView = camera.isDefaultView();
        if (oldRenderHeight >= 2) {
            showResizePreview();
        }
        fractalSurface.resizeBuffer(width, height);
        camera.resize(width, height, oldRenderHeight, fractalSurface.renderHeight());
        resizePending = false;
        resetPriority();
        recalculate(!resizeDefaultView);
    }

    private void recalculate() {
        recalculate(false);
    }

    private void recalculate(boolean preserveIterationLimit) {
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
                defaultViewport,
                preserveIterationLimit
        );
    }

    public void close() {
        if (loadingScreen != null) loadingScreen.close();
        contextMenu.close();
        interactionRender.cancel();
        resizeRender.stop();
        colorCycleTimer.stop();
        renderController.close();
    }

    FractalScene sceneSnapshot() {
        return scene.withViewport(camera.viewport());
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
                completed.scene().coloring().createStrategy(completed.frame().samplePlane()),
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
        dismissContextMenu();
        stopColorCyclingForSceneChange();
        fractalSurface.showPreview(camera.viewport());
        renderController.cancelCurrent();
        colorCyclePaused = true;
        interactionRender.requestZoomRender();
    }

    private void configurePan() {
        setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            requestFocus();
            panSourceViewport = camera.viewport();

            lastDragX = event.getX();
            lastDragY = event.getY();
            panning = true;
            colorCyclePaused = true;
            panChanged = false;
            event.consume();
        });

        setOnMouseDragged(event -> {
            latency().input("drag");
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
                stopColorCyclingForSceneChange();
                interactionRender.cancel();
                renderController.cancelCurrent();
                panChanged = true;
            }

            fractalSurface.showPreview(camera.viewport());
            event.consume();
        });

        setOnMouseReleased(event -> {
            latency().boundary("drag_released");
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
                recalculate(true);
            } else {
                colorCyclePaused = false;
                lastCycleTick = 0L;
            }
            event.consume();
        });
    }

    public void setOnZoomChanged(
            Consumer<BigDecimal> handler
    ) {
        renderController.setOnZoomChanged(value -> {
            modeIndicator.setZoom(value);
            handler.accept(value);
        });
    }

    public void setOnDeepZoomChanged(Consumer<Boolean> handler) {
        deepZoomChangedHandler = java.util.Objects.requireNonNull(handler);
    }

    public void setOnViewportChanged(Consumer<Viewport> handler) {
        viewportChangedHandler = java.util.Objects.requireNonNull(handler);
        viewportChangedHandler.accept(camera.viewport());
    }

    private void resetPriority() {
        renderPriority = RenderPriority.center();
    }
}
