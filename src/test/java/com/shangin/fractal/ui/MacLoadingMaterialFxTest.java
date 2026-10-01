package com.shangin.fractal.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacLoadingMaterialFxTest {
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
    @Test void nativeMaterialAttachesResizesAndDetachesRepeatedly() throws Exception {
        Stage stage = fx(() -> { var s = new Stage(); s.setTitle("FractalLens material verification"); return s; });
        try {
            for (int i = 0; i < 3; i++) {
                var scheme = i == 0 ? javafx.application.ColorScheme.LIGHT
                        : i == 1 ? javafx.application.ColorScheme.DARK : null;
                LoadingScreen screen = fx(() -> {
                    var loading = new LoadingScreen();
                    stage.setScene(new Scene(loading, 640, 440));
                    stage.getScene().getPreferences().setColorScheme(scheme);
                    stage.show();
                    return loading;
                });
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!fx(screen::hasNativeMaterial) && System.nanoTime() < deadline) Thread.sleep(25);
                    assertTrue(fx(screen::hasNativeMaterial), "Must attach real AppKit material, not silently fall back");
                    fx(() -> { stage.setWidth(720); stage.setHeight(500); return null; });
                    Thread.sleep(Long.getLong("fractal.material.inspectionMillis", 100));
                } finally {
                    fx(() -> { screen.close(); screen.close(); assertFalse(screen.hasNativeMaterial()); return null; });
                }
            }
        } finally { fx(() -> { stage.close(); return null; }); }
    }
}
