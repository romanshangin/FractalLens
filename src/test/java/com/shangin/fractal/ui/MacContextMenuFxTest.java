package com.shangin.fractal.ui;

import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.render.RenderPriority;
import javafx.scene.image.ImageView;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacContextMenuFxTest {
    @BeforeAll static void start() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    @AfterAll static void stop() { Platform.exit(); }
    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    private static void request(Pane owner) {
        var point = owner.localToScreen(60, 60);
        owner.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                60, 60, point.getX(), point.getY(), false, null));
    }
    private static void awaitClosed(FractalContextMenu menu) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fx(menu::hasNativeMenu) && System.nanoTime() < deadline) Thread.sleep(25);
        assertFalse(fx(menu::hasNativeMenu));
    }

    @Test void nativeSelectionCancellationPulsesAndLifecycle() throws Exception {
        assertTrue(MacContextMenu.isAvailable(), "Native library must be packaged and loadable");
        var owner = fx(Pane::new);
        var copies = new AtomicInteger();
        var pulses = new AtomicInteger();
        var menu = fx(() -> new FractalContextMenu(owner, () -> {
            assertTrue(Platform.isFxApplicationThread());
            copies.incrementAndGet();
        }));
        var stage = fx(() -> {
            var s = new Stage(); s.setTitle("FractalLens native menu verification");
            s.setScene(new Scene(owner, 500, 320)); s.show(); s.toFront(); s.requestFocus(); return s;
        });
        var timer = fx(() -> {
            var t = new AnimationTimer() { public void handle(long now) { pulses.incrementAndGet(); } };
            t.start(); return t;
        });
        var originalScene = fx(stage::getScene);
        try {
            Thread.sleep(500);
            assertTrue(fx(stage::isFocused), "Native menu test window must have desktop focus");
            fx(() -> { request(owner); assertTrue(menu.hasNativeMenu()); assertFalse(menu.isShowing()); return null; });
            Thread.sleep(300);
            assertTrue(fx(() -> AppKitMenuProbe.tracking(menu)), "AppKit must actually be in its tracking loop");
            int before = pulses.get();
            Thread.sleep(500);
            assertTrue(pulses.get() > before + 2, "JavaFX must keep publishing pulses during NSMenu tracking");
            assertTrue(fx(menu::hasNativeMenu));
            fx(() -> { AppKitMenuProbe.select(menu); return null; });
            awaitClosed(menu);
            assertEquals(1, copies.get(), "Native target/action selection must invoke the command exactly once");

            fx(() -> { request(owner); return null; });
            Thread.sleep(250);
            fx(() -> { AppKitMenuProbe.cancel(menu); return null; });
            awaitClosed(menu);
            assertEquals(1, copies.get(), "Native cancellation must not invoke the command");

            fx(() -> { request(owner); return null; });
            Thread.sleep(250);
            fx(() -> {
                AppKitMenuProbe.select(menu);
                menu.hide(); // Selection is pending; disposing the owner must suppress it.
                request(owner);
                return null;
            });
            Thread.sleep(250);
            assertEquals(1, copies.get(), "A stale selection must not run after replacement");
            fx(() -> { assertTrue(AppKitMenuProbe.tracking(menu)); menu.hide(); return null; });

            fx(() -> { request(owner); return null; });
            Thread.sleep(250);
            fx(() -> { stage.setWidth(540); return null; });
            awaitClosed(menu);

            fx(() -> { request(owner); return null; });
            Thread.sleep(250);
            fx(() -> { stage.setScene(new Scene(new Pane(), 540, 320)); return null; });
            awaitClosed(menu);
            fx(() -> { stage.setScene(originalScene); return null; });
            Thread.sleep(250);

            // Cancel before the scheduled native tracking call starts, then reopen.
            fx(() -> { request(owner); menu.hide(); request(owner); menu.close(); menu.close(); return null; });
            Thread.sleep(250);
            assertFalse(fx(menu::hasNativeMenu));
            assertEquals(1, copies.get());
            fx(() -> { request(owner); assertFalse(menu.hasNativeMenu()); return null; });
        } finally {
            fx(() -> { timer.stop(); menu.close(); stage.close(); return null; });
        }
    }
    @Test void renderingPublishesWhileNativeMenuTracks() throws Exception {
        var surface = fx(() -> { var s = new FractalSurface(); s.setManaged(false); return s; });
        var controller = fx(() -> new FractalRenderController(surface));
        var owner = fx(() -> new Pane(surface));
        var menu = fx(() -> new FractalContextMenu(owner, () -> {}));
        var stage = fx(() -> {
            var s = new Stage(); s.setScene(new Scene(owner, 500, 320));
            s.show(); s.toFront(); s.requestFocus(); return s;
        });
        var completed = new CountDownLatch(1);
        try {
            Thread.sleep(500);
            assertTrue(fx(stage::isFocused), "Native menu test window must have desktop focus");
            fx(() -> { request(owner); return null; });
            Thread.sleep(250);
            fx(() -> {
                assertTrue(AppKitMenuProbe.tracking(menu));
                surface.resize(500, 320);
                surface.resizeBuffer(500, 320);
                var camera = new FractalCamera(FractalPreset.MANDELBROT);
                camera.resize(500, 320);
                var scene = FractalScene.create(
                        FractalPreset.MANDELBROT,
                        PalettePreset.ICE).withViewport(camera.viewport());
                boolean[] started = {false};
                controller.setOnRenderingChanged(active -> {
                    if (active) started[0] = true;
                    else if (started[0]) completed.countDown();
                });
                controller.render(scene, new RenderTarget(
                        surface.renderWidth(), surface.renderHeight(), RenderPriority.center()),
                        camera.defaultViewport(500, 320));
                return null;
            });
            assertTrue(completed.await(15, TimeUnit.SECONDS), "Rendering must complete before the menu closes");
            fx(() -> {
                assertTrue(AppKitMenuProbe.tracking(menu));
                assertNotNull(surface.completedRender());
                assertNotNull(((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage(),
                        "Completed pixels must reach the displayed JavaFX surface during menu tracking");
                return null;
            });
        } finally {
            fx(() -> { menu.close(); controller.close(); stage.close(); return null; });
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "fractal.fx.fullscreen", matches = "true")
    void nativeMenuTracksOnAvailableDisplaysAndInFullScreen() throws Exception {
        var owner = fx(Pane::new);
        var menu = fx(() -> new FractalContextMenu(owner, () -> {}));
        var stage = fx(() -> {
            var s = new Stage();
            s.setScene(new Scene(owner, 420, 280));
            s.setFullScreenExitHint("");
            s.show(); s.toFront(); s.requestFocus(); return s;
        });
        try {
            var screens = fx(() -> java.util.List.copyOf(javafx.stage.Screen.getScreens()));
            for (var screen : screens) {
                fx(() -> {
                    var bounds = screen.getVisualBounds();
                    stage.setX(bounds.getMinX() + 40); stage.setY(bounds.getMinY() + 40);
                    stage.toFront(); stage.requestFocus(); return null;
                });
                Thread.sleep(500);
                fx(() -> { request(owner); return null; });
                Thread.sleep(250);
                fx(() -> {
                    assertTrue(AppKitMenuProbe.tracking(menu));
                    AppKitMenuProbe.cancel(menu); return null;
                });
                awaitClosed(menu);
            }
            fx(() -> { stage.setFullScreen(true); return null; });
            Thread.sleep(2500);
            assertTrue(fx(stage::isFullScreen));
            fx(() -> { request(owner); return null; });
            Thread.sleep(250);
            fx(() -> { assertTrue(AppKitMenuProbe.tracking(menu)); AppKitMenuProbe.select(menu); return null; });
            awaitClosed(menu);
            fx(() -> { stage.setFullScreen(false); return null; });
            Thread.sleep(2500);
            fx(() -> { request(owner); return null; });
            Thread.sleep(250);
            fx(() -> { assertTrue(AppKitMenuProbe.tracking(menu)); AppKitMenuProbe.cancel(menu); return null; });
            awaitClosed(menu);
            System.out.println("Native menu display coverage: " + screens.size() + " display(s), full-screen entry/exit");
        } finally {
            fx(() -> { menu.close(); stage.setFullScreen(false); stage.close(); return null; });
        }
    }

}
