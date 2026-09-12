package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.render.RenderPriority;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.SamplingPattern;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in integration checks: mvn -Dfractal.fx.tests=true -Dtest=FractalResizeFxTest test */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalResizeFxTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() { Platform.exit(); }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    @Test
    void deepJuliaCompletesAndRevealsTheCanvasAtWindowResolution() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.JULIA, PalettePreset.ICE, true));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        Viewport destination = com.shangin.fractal.render.ReportedJuliaFixture.viewport();
        try {
            changeView(view, surface, () -> {
                view.resize(1200, 780);
                view.layout();
                view.goTo(destination);
                assertFalse(surface.isVisible());
            }, 30);
            fx(() -> {
                assertTrue(surface.hasCompletedFrame());
                assertTrue(surface.isVisible(), "Completed Julia must be visible after the loading layer closes");
                assertEquals(2, view.getChildrenUnmodifiable().size());
                assertEquals(destination, surface.completedRender().frame().job().viewport());
                assertFalse(FractalPreset.JULIA.hasSufficientDirectPrecision(destination, 1200, 780));
                return null;
            });
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void loadingScreenWaitsForFullRenderAndReturnsOnlyForSceneChanges(boolean macStartup) throws Exception {
        FractalView view = fx(() -> new FractalView(
                FractalPreset.MANDELBROT, PalettePreset.ICE, macStartup));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            fx(() -> {
                assertEquals(!macStartup, surface.isVisible());
                assertTrue(surface.isManaged(), "Hidden surface must still receive layout");
                assertEquals(macStartup ? 3 : 2, view.getChildrenUnmodifiable().size());
                return null;
            });
            changeView(view, surface, () -> {
                view.resize(120, 90);
                view.layout();
                assertEquals(!macStartup, surface.isVisible(), "Layout must not reveal partial pixels");
                // Supersede the initial request before it can finish.
                view.resize(160, 100);
                view.layout();
                assertEquals(!macStartup, surface.isVisible(), "Resize cancellation must not reveal the canvas");
            });
            fx(() -> {
                assertTrue(surface.isVisible());
                assertEquals(2, view.getChildrenUnmodifiable().size());
                assertTrue(surface.hasCompletedFrame());
                return null;
            });
            changeView(view, surface, () -> {
                view.resize(180, 110);
                view.layout();
                assertTrue(surface.isVisible(), "Later renders must remain progressive");
                assertEquals(2, view.getChildrenUnmodifiable().size());
            });
            for (boolean reset : new boolean[] {false, true}) {
                changeView(view, surface, () -> {
                    if (reset) view.resetView();
                    else view.setFractal(FractalPreset.JULIA);
                    assertEquals(!macStartup, surface.isVisible(),
                            "Scene changes must hide the old frame until rendering finishes");
                    assertEquals(macStartup ? 3 : 2, view.getChildrenUnmodifiable().size());
                });
                fx(() -> {
                    assertTrue(surface.isVisible());
                    assertEquals(2, view.getChildrenUnmodifiable().size());
                    assertTrue(surface.hasCompletedFrame());
                    return null;
                });
            }
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void wheelAndPinchRecalculateIterationBudget(boolean pinch) throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(100, 80); view.layout(); });
            int before = fx(() -> surface.completedRender().frame().job().maxIterations());
            changeView(view, surface, () -> {
                if (pinch) {
                    for (var type : java.util.List.of(javafx.scene.input.ZoomEvent.ZOOM_STARTED,
                            javafx.scene.input.ZoomEvent.ZOOM, javafx.scene.input.ZoomEvent.ZOOM_FINISHED)) {
                        view.fireEvent(new javafx.scene.input.ZoomEvent(type, 50, 40, 50, 40,
                                false, false, false, false, false, false, 2, 2, null));
                    }
                } else {
                    view.fireEvent(new javafx.scene.input.ScrollEvent(
                            javafx.scene.input.ScrollEvent.SCROLL, 50, 40, 50, 40,
                            false, false, false, false, false, false, 0, 40, 0, 40,
                            javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                            javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                }
            });
            var completed = fx(surface::completedRender);
            int expected = completed.scene().iterations().maxIterations(
                    new FractalCamera(FractalPreset.MANDELBROT).defaultViewport(100, 80).scaleExact(),
                    completed.scene().viewport().scaleExact());
            assertTrue(expected > before);
            assertEquals(expected, completed.frame().job().maxIterations(),
                    "Zoom must increase the budget instead of retaining the initial 300 iterations");
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void refinedResizeKeepsCalculatedBaseEdgesVisibleDuringAntialiasing() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        fx(() -> {
            var source = com.shangin.fractal.render.RenderFrame.create(new com.shangin.fractal.render.RenderRequest(
                    new com.shangin.fractal.render.FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                    FractalPreset.MANDELBROT.defaultViewport(), 4, 4, 20));
            var target = com.shangin.fractal.render.RenderFrame.create(new com.shangin.fractal.render.RenderRequest(
                    new com.shangin.fractal.render.FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                    FractalPreset.MANDELBROT.defaultViewport(), 8, 8, 20));
            var wholeFrame = new com.shangin.fractal.render.RenderRegion(0, 0, 8, 8);
            target.validity().markReady(wholeFrame);
            surface.resize(8, 8);
            surface.resizeBuffer(8, 8);
            surface.beginProgressiveRender(target, source, InteractiveRenderMode.REFINED);
            surface.displayProgress(new com.shangin.fractal.render.RenderProgressBatch(
                            target, java.util.List.of(wholeFrame)),
                    new com.shangin.fractal.coloring.SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    InteractiveRenderMode.REFINED);
            var progressive = (ImageView) surface.getChildrenUnmodifiable().get(2);
            assertNotNull(progressive.getImage(), "Resize edges must appear during the base pass");
            assertEquals(255, progressive.getImage().getPixelReader().getArgb(0, 0) >>> 24);
            surface.beginRefinedRender(target);
            assertEquals(255, progressive.getImage().getPixelReader().getArgb(0, 0) >>> 24,
                    "Starting AA must not clear already calculated resize edges");
            return null;
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "fractal.fx.fullscreen", matches = "true")
    void reportedTrackpadZoomFullscreenCase() throws Exception {
        var values = java.nio.file.Files.readAllLines(java.nio.file.Path.of(
                "src/test/resources/com/shangin/fractal/ui/trackpad-fullscreen-case.txt"));
        var real = new java.math.BigDecimal(values.get(0).split(": ", 2)[1]);
        var imaginary = new java.math.BigDecimal(values.get(1).split(": ", 2)[1]);
        var zoom = new java.math.BigDecimal(values.get(2).split(": ", 2)[1]);
        var stage = fx(javafx.stage.Stage::new);
        var main = fx(() -> new MainView(stage));
        var view = fx(() -> (FractalView) main.getCenter());
        var surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            fx(() -> {
                var bounds = javafx.stage.Screen.getPrimary().getBounds();
                stage.setScene(new javafx.scene.Scene(main,
                        Math.floor(bounds.getWidth() * .75), Math.floor(bounds.getHeight() * .75)));
                stage.setFullScreenExitHint("");
                stage.show();
                stage.centerOnScreen();
                return null;
            });
            awaitSurfaceComplete(surface);
            System.out.println("Reported case: initial window ready");
            int zoomStages = 8;
            double stageFactor = Math.pow(zoom.doubleValue(), 1.0 / zoomStages);
            for (int zoomStage = 0; zoomStage < zoomStages; zoomStage++) {
                var beforeZoom = fx(surface::completedRender);
                int currentStage = zoomStage;
                fx(() -> {
                    double x = (view.getWidth() - 1) / 2;
                    double y = (view.getHeight() - 1) / 2;
                    view.fireEvent(new javafx.scene.input.ZoomEvent(
                            javafx.scene.input.ZoomEvent.ZOOM_STARTED, x, y, 100, 100,
                            false, false, false, false, false, false, 1, 1, null));
                    view.fireEvent(new javafx.scene.input.ZoomEvent(
                            javafx.scene.input.ZoomEvent.ZOOM, x, y, 100, 100,
                            false, false, false, false, false, false, stageFactor, stageFactor, null));
                    if (currentStage == 0) view.setCenter(real, imaginary);
                    view.fireEvent(new javafx.scene.input.ZoomEvent(
                            javafx.scene.input.ZoomEvent.ZOOM_FINISHED, x, y, 100, 100,
                            false, false, false, false, false, false, 1, stageFactor, null));
                    return null;
                });
                awaitNewSurfaceFrame(surface, beforeZoom);
                pauseFx(1000);
                System.out.println("Reported case: zoom stage " + (zoomStage + 1) + " of " + zoomStages + " finished");
            }
            pauseFx(5000);
            System.out.println("Reported case: zoom render finished and remained idle for 5 seconds; entering fullscreen");
            fx(() -> { stage.setFullScreen(true); return null; });
            pauseFx(8000);
            fx(() -> {
                saveSurface(surface, "target/reported-trackpad-fullscreen.png");
                var frame = surface.completedRender();
                System.out.println("Reported case: buffer=" + surface.renderWidth() + "x" + surface.renderHeight()
                        + ", displayed=" + frame.frame().request().width() + "x" + frame.frame().request().height());
                for (int layer = 1; layer < 3; layer++) {
                    assertNull(((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage(),
                            "Fullscreen must finish without further pointer input");
                }
                assertEquals(surface.renderWidth(), frame.frame().request().width());
                assertEquals(surface.renderHeight(), frame.frame().request().height());
                return null;
            });
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
        } finally {
            fx(() -> { main.close(); stage.close(); return null; });
        }
    }

    private static void awaitNewSurfaceFrame(FractalSurface surface,
                                                   com.shangin.fractal.render.CompletedRender previous) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        var timer = fx(() -> {
            var checker = new javafx.animation.AnimationTimer() {
                @Override public void handle(long now) {
                    var frame = surface.completedRender();
                    if (frame == null || frame == previous) return;
                    for (int layer = 1; layer < 3; layer++) {
                        if (((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage() != null) return;
                    }
                    stop();
                    completed.countDown();
                }
            };
            checker.start();
            return checker;
        });
        try {
            assertTrue(completed.await(30, TimeUnit.SECONDS), "The zoomed window frame must finish first");
        } finally {
            fx(() -> { timer.stop(); return null; });
        }
    }

    private static void pauseFx(int milliseconds) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        fx(() -> {
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(milliseconds));
            pause.setOnFinished(event -> done.countDown());
            pause.play();
            return null;
        });
        assertTrue(done.await(milliseconds + 5000L, TimeUnit.MILLISECONDS));
    }

    private static void saveSurface(FractalSurface surface, String path) throws Exception {
        var image = surface.snapshot(null, null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] pixels = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        var output = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, w, h, pixels, 0, w);
        javax.imageio.ImageIO.write(output, "png", new java.io.File(path));
    }

    @Test
    void contextMenuDismissesWithoutSwallowingCanvasInput() throws Exception {
        var stage = fx(javafx.stage.Stage::new);
        var canvas = fx(javafx.scene.layout.Pane::new);
        var copied = new java.util.concurrent.atomic.AtomicInteger();
        var menu = fx(() -> new FractalContextMenu(canvas, copied::incrementAndGet));
        try {
            fx(() -> {
                stage.setScene(new javafx.scene.Scene(canvas, 360, 180));
                stage.show();
                return null;
            });
            fx(() -> { menu.show(canvas, stage.getX() + 40, stage.getY() + 60); return null; });
            fx(() -> {
                assertTrue(menu.isShowing(), "Opening must leave the menu usable");
                var image = menu.getScene().snapshot(null);
                int w = (int) image.getWidth(), h = (int) image.getHeight();
                int[] pixels = new int[w * h];
                image.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
                var output = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                output.setRGB(0, 0, w, h, pixels, 0, w);
                javax.imageio.ImageIO.write(output, "png", new java.io.File("target/context-menu-qa.png"));
                var clicks = new java.util.concurrent.atomic.AtomicInteger();
                canvas.setOnMousePressed(event -> clicks.incrementAndGet());
                canvas.fireEvent(new javafx.scene.input.MouseEvent(
                        javafx.scene.input.MouseEvent.MOUSE_PRESSED, 10, 10, 10, 10,
                        javafx.scene.input.MouseButton.PRIMARY, 1,
                        false, false, false, false, true, false, false, false, false, true, null));
                assertFalse(menu.isShowing());
                assertEquals(1, clicks.get(), "Dismissal must not require a second click to start dragging");
                menu.show(canvas, stage.getX() + 40, stage.getY() + 60);
                canvas.fireEvent(new javafx.scene.input.ScrollEvent(
                        javafx.scene.input.ScrollEvent.SCROLL, 10, 10, 10, 10,
                        false, false, false, false, false, false, 0, 1, 0, 1,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                assertFalse(menu.isShowing(), "Scrolling must dismiss the popup");
                menu.show(canvas, stage.getX() + 40, stage.getY() + 60);
                menu.getItems().getFirst().fire();
                assertEquals(1, copied.get());
                assertFalse(menu.isShowing());
                menu.show(canvas, stage.getX() + 40, stage.getY() + 60);
                stage.setWidth(stage.getWidth() + 20);
                assertFalse(menu.isShowing(), "Window changes must dismiss the popup");
                return null;
            });
        } finally {
            fx(() -> { menu.close(); stage.close(); return null; });
        }
    }

    @ParameterizedTest
    @EnumSource(InteractiveRenderMode.class)
    void resizePreservesCenterColorsAndCropCompletesSynchronously(InteractiveRenderMode mode) throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        try {
            camera.resize(80, 60);
            camera.zoomBy(40, 30, 0.25, 80, 60, 80, 60);
            Viewport original = camera.viewport();
            render(surface, controller, camera, 80, 60, false, mode);
            int[] windowPixels = pixels(surface);
            int iterations = surface.completedRender().frame().request().maxIterations();
            render(surface, controller, camera, 120, 100, true, mode);
            int[] fullscreenPixels = pixels(surface);
            assertEquals(iterations, surface.completedRender().frame().request().maxIterations());
            for (int y = 0; y < 60; y++) {
                for (int x = 0; x < 80; x++) {
                    assertEquals(windowPixels[y * 80 + x], fullscreenPixels[(y + 20) * 120 + x + 20],
                            "Resizing must preserve already displayed colors at " + x + "," + y);
                }
            }
            fx(() -> {
                controller.cancelCurrent();
                surface.resize(80, 60);
                surface.resizeBuffer(80, 60);
                camera.resize(80, 60, 100, 60);
                controller.render(scene(camera).withAntialiasing(new AntialiasSettings(SamplingPattern.REGULAR, mode)),
                        new RenderTarget(80, 60, RenderPriority.center()),
                        camera.defaultViewport(80, 60), true);
                // No asynchronous render or AA completion is needed for a crop.
                assertEquals(80, surface.completedRender().frame().request().width());
                assertEquals(60, surface.renderHeight());
                assertEquals(80, surface.renderWidth());
                return null;
            });
            assertEquals(original.center(), camera.viewport().center());
            assertEquals(original.scale(), camera.viewport().scale(), 1e-15);
            assertArrayEquals(windowPixels, pixels(surface));
            render(surface, controller, camera, 120, 100, true, mode);
            assertArrayEquals(fullscreenPixels, pixels(surface), "Second expansion must restore all pixels");
            render(surface, controller, camera, 80, 60, true, mode);
            assertArrayEquals(windowPixels, pixels(surface), "Second crop must preserve the center");
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void defaultResizeUpdatesDimensionsEvenWhenSpareBufferMatches() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        try {
            render(surface, controller, camera, 80, 60, false);
            render(surface, controller, camera, 120, 100, false);
            render(surface, controller, camera, 80, 60, false);
            assertEquals(80, surface.renderWidth());
            assertEquals(60, surface.renderHeight());
            assertEquals(80 * 60, pixels(surface).length);
            assertTrue(camera.isDefaultView());
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    private static FractalScene scene(FractalCamera camera) {
        return FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withViewport(camera.viewport());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {60, 120})
    @EnabledIfSystemProperty(named = "fractal.fx.fullscreen", matches = "true")
    void nativeFullscreenZoomMustFillEntireImage(int zoomSteps) throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        javafx.stage.Stage stage = fx(javafx.stage.Stage::new);
        try {
            changeView(view, surface, () -> {
                var bounds = javafx.stage.Screen.getPrimary().getBounds();
                stage.setScene(new javafx.scene.Scene(view, bounds.getWidth() * .75, bounds.getHeight() * .75));
                stage.show();
            });
            changeView(view, surface, () -> {
                for (int i = 0; i < zoomSteps; i++) view.zoom(true);
                view.setCenter(new java.math.BigDecimal("-0.743643887037151"),
                        new java.math.BigDecimal("0.131825904205330"));
            }, 90);
            int[] original = pixels(surface);
            for (int cycle = 0; cycle < 2; cycle++) {
                fx(() -> { stage.setFullScreen(true); return null; });
                // Include the native macOS transition and its last geometry events.
                CountDownLatch settled = new CountDownLatch(1);
                fx(() -> {
                    var pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
                    pause.setOnFinished(event -> settled.countDown());
                    pause.play();
                    return null;
                });
                assertTrue(settled.await(15, TimeUnit.SECONDS));
                awaitSurfaceComplete(surface);
                fx(() -> {
                    var parameters = new javafx.scene.SnapshotParameters();
                    parameters.setTransform(javafx.scene.transform.Transform.scale(
                            surface.renderWidth() / surface.getWidth(), surface.renderHeight() / surface.getHeight()));
                    var image = surface.snapshot(parameters, null);
                    int width = (int) image.getWidth(), height = (int) image.getHeight();
                    int[] argb = new int[width * height];
                    image.getPixelReader().getPixels(0, 0, width, height,
                            PixelFormat.getIntArgbInstance(), argb, 0, width);
                    var output = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    output.setRGB(0, 0, width, height, argb, 0, width);
                    javax.imageio.ImageIO.write(output, "png", new java.io.File("target/fullscreen-zoom-" + zoomSteps + "-qa.png"));
                    var base = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
                    int[] backingPixels = new int[width * height];
                    base.getPixelReader().getPixels(0, 0, width, height,
                            PixelFormat.getIntArgbInstance(), backingPixels, 0, width);
                    assertArrayEquals(backingPixels, argb, "Visible surface must show every rendered pixel");
                    var completed = surface.completedRender();
                    assertEquals(surface.renderWidth(), completed.frame().request().width());
                    assertEquals(surface.renderHeight(), completed.frame().request().height());
                    for (int layer = 1; layer < 3; layer++) {
                        assertNull(((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage(), "Frame must finish");
                    }
                    return null;
                });
                assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
                fx(() -> { stage.setFullScreen(false); return null; });
                CountDownLatch windowSettled = new CountDownLatch(1);
                fx(() -> {
                    var pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
                    pause.setOnFinished(event -> windowSettled.countDown());
                    pause.play();
                    return null;
                });
                assertTrue(windowSettled.await(10, TimeUnit.SECONDS));
                awaitSurfaceComplete(surface);
                assertArrayEquals(original, pixels(surface), "Each return must preserve the original zoomed center");
            }
        } finally {
            fx(() -> { stage.close(); view.close(); return null; });
        }
    }

    @Test
    void noOpTrackpadPanMustNotCancelPendingRender() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(80, 60); view.layout(); });
            changeView(view, surface, () -> {
                view.zoom(true);
                view.zoom(false);
                // At the overview bounds this scroll does not move the camera.
                view.fireEvent(new javafx.scene.input.ScrollEvent(
                        javafx.scene.input.ScrollEvent.SCROLL, 40, 30, 40, 30,
                        false, false, false, false, false, false,
                        1, 0, 1, 0,
                        javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                        javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            });
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void clickWithoutDraggingMustNotCancelPendingZoom() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(80, 60); view.layout(); });
            changeView(view, surface, () -> {
                view.zoom(true);
                for (boolean pressed : new boolean[]{true, false}) {
                    view.fireEvent(new javafx.scene.input.MouseEvent(
                            pressed ? javafx.scene.input.MouseEvent.MOUSE_PRESSED
                                    : javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                            40, 30, 40, 30, javafx.scene.input.MouseButton.PRIMARY, 1,
                            false, false, false, false, pressed, false, false, false, false, true, null));
                }
            });
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void zoomBetweenResizeEventAndLayoutMustNotRestoreOverviewPreview() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(80, 60); view.layout(); });
            changeView(view, surface, () -> {
                view.resize(120, 100);
                view.zoom(true);
                view.layout();
                var base = (ImageView) surface.getChildrenUnmodifiable().getFirst();
                assertEquals(80.0 * 1.25 / 120, base.getLocalToParentTransform().getMxx(), 1e-8,
                        "A zoom arriving during resize must stay a zoom preview, not an overview stretch");
            });
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void initialResizePreviewMustFillWindowThroughoutInterruptedAspectChanges() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(600, 400); view.layout(); });
            int[] originalPixels = pixels(surface);
            CountDownLatch resized = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            fx(() -> {
                new javafx.animation.AnimationTimer() {
                    int step;
                    final int[][] sizes = {{1000, 700}, {1000, 760}, {700, 760}, {700, 400}, {600, 400}};
                    public void handle(long now) {
                        try {
                            view.resize(sizes[step][0], sizes[step][1]);
                            view.layout();
                            var base = (ImageView) surface.getChildrenUnmodifiable().getFirst();
                            assertEquals(0, base.getBoundsInParent().getMinX(), 1e-8,
                                    "Initial preview must not reveal side bands");
                            assertEquals(surface.getWidth(), base.getBoundsInParent().getWidth(), 1e-8);
                            for (int layer = 1; layer < 3; layer++) {
                                assertNull(((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage(),
                                        "New tiles must not mix with a differently stretched initial frame");
                            }
                            if (++step == sizes.length) { stop(); resized.countDown(); }
                        } catch (Throwable error) {
                            failure.set(error); stop(); resized.countDown();
                        }
                    }
                }.start();
                return null;
            });
            assertTrue(resized.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            changeView(view, surface, () -> { view.resize(602, 402); view.layout(); });
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255));
            changeView(view, surface, () -> { view.resize(600, 400); view.layout(); });
            assertArrayEquals(originalPixels, pixels(surface),
                    "Returning to the initial dimensions must restore the same fractal");
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void resizeMustFinishUnpublishedRefinementEvenWhenAllSamplesAreReady() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        try {
            camera.resize(80, 60);
            camera.zoomBy(40, 30, 0.25, 80, 60, 80, 60);
            render(surface, controller, camera, 80, 60, false);
            fx(() -> {
                // Model a resize arriving between base completion and AA publication.
                var frame = surface.completedRender().frame();
                surface.beginProgressiveRender(frame, null, InteractiveRenderMode.REFINED);
                surface.beginRefinedRender(frame);
                int[] center = new int[20 * 20];
                java.util.Arrays.fill(center, 0xFF123456);
                surface.displayRefinedTile(frame,
                        new com.shangin.fractal.render.RenderRegion(30, 20, 20, 20), center);
                return null;
            });
            // A duplicate geometry event must not promote the partly transparent AA layer.
            render(surface, controller, camera, 80, 60, true);
            assertTrue(java.util.Arrays.stream(pixels(surface)).allMatch(pixel -> pixel >>> 24 == 255),
                    "Completed resize must not leave transparent tiles around the center");
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void viewResizeEventsKeepOddSizedRetinaFramesCentered() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            fx(() -> { surface.setOutputScale(2, 2); return null; });
            changeView(view, surface, () -> { view.resize(81, 61); view.layout(); });
            changeView(view, surface, () -> { view.resize(121, 101); view.layout(); });
            changeView(view, surface, () -> { view.resize(81, 61); view.layout(); });
            changeView(view, surface, () -> view.zoom(true));
            int[] before = pixels(surface);
            changeView(view, surface, () -> { view.resize(121, 101); view.layout(); });
            int[] expanded = pixels(surface);
            for (int y = 0; y < 122; y++) {
                for (int x = 0; x < 162; x++) {
                    assertEquals(before[y * 162 + x], expanded[(y + 40) * 242 + x + 40]);
                }
            }
            changeView(view, surface, () -> { view.resize(81, 61); view.layout(); });
            assertArrayEquals(before, pixels(surface));
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    @Test
    void dragAfterExpandedAndCroppedResizeKeepsIterationLimitForReuse() throws Exception {
        FractalView view = fx(() -> new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE));
        FractalSurface surface = fx(() -> (FractalSurface) view.getChildrenUnmodifiable().getFirst());
        try {
            changeView(view, surface, () -> { view.resize(100, 80); view.layout(); });
            changeView(view, surface, () -> {
                for (int zoom = 0; zoom < 5; zoom++) view.zoom(true);
            });

            changeView(view, surface, () -> { view.resize(160, 120); view.layout(); });
            int expandedIterations = fx(() -> surface.completedRender().frame().request().maxIterations());
            changeView(view, surface, () -> drag(view, 80, 60, 92, 67));
            assertEquals(expandedIterations,
                    fx(() -> surface.completedRender().frame().request().maxIterations()),
                    "A drag after expansion must remain compatible with the resized frame");

            changeView(view, surface, () -> { view.resize(100, 80); view.layout(); });
            int croppedIterations = fx(() -> surface.completedRender().frame().request().maxIterations());
            changeView(view, surface, () -> drag(view, 50, 40, 42, 46));
            assertEquals(croppedIterations,
                    fx(() -> surface.completedRender().frame().request().maxIterations()),
                    "A drag after cropping must remain compatible with the resized frame");
        } finally {
            fx(() -> { view.close(); return null; });
        }
    }

    private static void drag(FractalView view, double fromX, double fromY, double toX, double toY) {
        view.fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                fromX, fromY, fromX, fromY, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, true, null));
        view.fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_DRAGGED,
                toX, toY, toX, toY, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
        view.fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                toX, toY, toX, toY, javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, false, null));
    }

    private static void awaitSurfaceComplete(FractalSurface surface) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        var timer = fx(() -> {
            var checker = new javafx.animation.AnimationTimer() {
                @Override public void handle(long now) {
                    var frame = surface.completedRender();
                    if (frame == null || frame.frame().request().width() != surface.renderWidth()
                            || frame.frame().request().height() != surface.renderHeight()) return;
                    for (int layer = 1; layer < 3; layer++) {
                        if (((ImageView) surface.getChildrenUnmodifiable().get(layer)).getImage() != null) return;
                    }
                    stop();
                    completed.countDown();
                }
            };
            checker.start();
            return checker;
        });
        try {
            assertTrue(completed.await(90, TimeUnit.SECONDS), "Resized surface must finish without input");
        } finally {
            fx(() -> { timer.stop(); return null; });
        }
    }

    private static void changeView(FractalView view, FractalSurface surface, Runnable action) throws Exception {
        changeView(view, surface, action, 15);
    }

    private static void changeView(FractalView view, FractalSurface surface, Runnable action, int timeoutSeconds) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            var previous = surface.completedRender();
            view.setOnRenderingChanged(active -> {
                if (!active && surface.completedRender() != null
                        && (previous == null || previous.frame() != surface.completedRender().frame())) {
                    completed.countDown();
                }
            });
            action.run();
            return null;
        });
        assertTrue(completed.await(timeoutSeconds, TimeUnit.SECONDS), "View must complete the new frame");
    }

    private static void render(FractalSurface surface, FractalRenderController controller,
                               FractalCamera camera, int width, int height, boolean resize) throws Exception {
        render(surface, controller, camera, width, height, resize, InteractiveRenderMode.REFINED);
    }

    private static void render(FractalSurface surface, FractalRenderController controller,
                               FractalCamera camera, int width, int height, boolean resize,
                               InteractiveRenderMode mode) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            controller.cancelCurrent();
            boolean[] started = {false};
            controller.setOnRenderingChanged(active -> {
                if (active) started[0] = true;
                else if (started[0]) completed.countDown();
            });
            int oldHeight = surface.renderHeight();
            surface.resize(width, height);
            surface.resizeBuffer(width, height);
            if (oldHeight >= 2) {
                camera.resize(width, height, oldHeight, surface.renderHeight());
            } else {
                camera.resize(width, height);
            }
            controller.render(scene(camera).withAntialiasing(new AntialiasSettings(SamplingPattern.REGULAR, mode)),
                    new RenderTarget(surface.renderWidth(), surface.renderHeight(),
                    RenderPriority.center()), camera.defaultViewport(width, height), resize);
            return null;
        });
        assertTrue(completed.await(15, TimeUnit.SECONDS));
    }

    private static int[] pixels(FractalSurface surface) throws Exception {
        return fx(() -> {
            var image = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
            int width = (int) image.getWidth(), height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height,
                    PixelFormat.getIntArgbPreInstance(), pixels, 0, width);
            return pixels;
        });
    }
}
