package com.shangin.fractal.ui;

import com.shangin.fractal.controller.RenderStatus;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacStatusBadgeFxTest {
    @BeforeAll static void start() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }
    @AfterAll static void stop() { Platform.exit(); }
    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static final double MARGIN = 12;

    /** Waits until the native frame reflects the latest JavaFX layout and returns that state. */
    private static double[] awaitState(RenderModeIndicator badge, Predicate<double[]> ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        double[] state = fx(badge::nativeState);
        while (!ready.test(state) && System.nanoTime() < deadline) {
            Thread.sleep(25);
            state = fx(badge::nativeState);
        }
        return state;
    }

    private static void assertAnchoredBottomLeft(double[] state, String phase) {
        assertEquals(1, state[0], phase + ": the badge must be the topmost content subview");
        assertEquals(MARGIN, state[1], 0.5, phase + ": left margin");
        assertEquals(MARGIN, state[2], 0.5, phase + ": bottom margin");
        assertTrue(state[3] > 0 && state[4] > 0, phase + ": the badge must have a size");
    }

    @Test void nativeBadgeFollowsTheWindowAndStaysAboveTheLoadingMaterial() throws Exception {
        assertTrue(MacContextMenu.isAvailable(), "The AppKit bridge must load in this environment");
        RenderModeIndicator badge = fx(RenderModeIndicator::new);
        Stage stage = fx(() -> {
            var window = new Stage();
            window.setTitle("FractalLens status badge verification");
            var root = new StackPane(badge);
            StackPane.setAlignment(badge, javafx.geometry.Pos.BOTTOM_LEFT);
            StackPane.setMargin(badge, new javafx.geometry.Insets(MARGIN));
            window.setScene(new Scene(root, 640, 440));
            return window;
        });
        try {
            for (int i = 0; i < 2; i++) {
                fx(() -> { stage.show(); return null; });
                assertTrue(fx(badge::hasNativePresentation), "Must use the AppKit badge, not the JavaFX fallback");
                assertFalse(fx(badge::isVisible), "The JavaFX label must not duplicate the native badge");
                assertAnchoredBottomLeft(awaitState(badge, state -> state[2] == MARGIN), "after show");
                fx(() -> {
                    badge.setRenderStatus(new RenderStatus(RenderStatus.State.RENDERING, 0));
                    badge.setProgress(0.42);
                    badge.setDeepZoom(true);
                    badge.setRenderStatus(new RenderStatus(RenderStatus.State.COMPLETE, 15_000_000));
                    stage.setWidth(720);
                    stage.setHeight(500);
                    return null;
                });
                var loading = fx(() -> {
                    var screen = new LoadingScreen(badge::raiseNative);
                    ((StackPane) stage.getScene().getRoot()).getChildren().add(screen);
                    return screen;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!fx(loading::hasNativeMaterial) && System.nanoTime() < deadline) Thread.sleep(25);
                assertTrue(fx(loading::hasNativeMaterial));
                // The material is attached after the badge and must not cover it.
                assertAnchoredBottomLeft(fx(badge::nativeState), "after the loading material");
                double width = fx(badge::nativeState)[3];
                fx(() -> { stage.setWidth(800); stage.setHeight(560); return null; });
                assertAnchoredBottomLeft(awaitState(badge, state -> Math.abs(state[2] - MARGIN) < 0.5),
                        "after resize under the loading material");
                assertEquals(width, fx(badge::nativeState)[3], "Resize must not change the badge width");
                Thread.sleep(Long.getLong("fractal.material.inspectionMillis", 100));
                fx(() -> {
                    loading.close();
                    ((StackPane) stage.getScene().getRoot()).getChildren().remove(loading);
                    stage.hide();
                    return null;
                });
                assertFalse(fx(badge::hasNativePresentation), "Hiding the window must release the native view");
                assertTrue(fx(badge::isVisible));
            }
            fx(() -> { stage.show(); badge.close(); badge.close(); return null; });
            assertFalse(fx(badge::hasNativePresentation));
        } finally { fx(() -> { stage.close(); return null; }); }
    }
}
