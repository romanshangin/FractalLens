package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MacSystemMenuFxTest {
    @TempDir Path directory;

    @BeforeAll static void startFx() throws Exception {
        var started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterAll static void stopFx() { Platform.exit(); }

    @Test void retainsNativeCommandsWithoutFocusedMainWindowAndRestoresFocus() throws Exception {
        var stage = fx(Stage::new);
        var other = fx(Stage::new);
        var view = fx(() -> new MainView(stage, new LastSessionStore(directory.resolve("session.json"))));
        try {
            fx(() -> {
                stage.setScene(new Scene(view, 320, 240));
                stage.show(); stage.toFront(); stage.requestFocus();
                return null;
            });
            await(stage::isFocused);
            await(() -> ((FractalView) view.getCenter()).hasCompletedFrame());
            var expected = List.of("File", "Edit", "View", "Fractal", "Go to", "Color", "Render", "Window", "Help");
            assertEquals(expected, nativeCommands());

            // An unowned window with no menu exercises Glass's application-default
            // path, also used when the main window loses focus during a Space switch.
            fx(() -> {
                other.setScene(new Scene(new Pane(), 200, 120));
                other.show(); other.toFront(); other.requestFocus();
                return null;
            });
            await(() -> other.isFocused() && !stage.isFocused());
            assertEquals(expected, nativeCommands(), "Native commands must survive main-window focus loss");

            fx(() -> { stage.toFront(); stage.requestFocus(); return null; });
            await(stage::isFocused);
            assertEquals(expected, nativeCommands(), "Commands must remain installed without pointer input");

            fx(() -> { other.close(); stage.close(); view.close(); view.close(); return null; });
            fx(() -> { other.show(); other.toFront(); other.requestFocus(); return null; });
            await(other::isFocused);
            assertEquals(List.of(), nativeCommands(), "Closed views must release the application-default commands");
        } finally {
            fx(() -> { other.close(); stage.close(); view.close(); return null; });
        }
    }

    private static List<String> nativeCommands() throws Exception {
        return fx(() -> {
            try {
                var titles = AppKitMenuProbe.mainMenuTitles();
                return titles.subList(1, titles.size()); // The application menu belongs to Glass.
            } catch (Throwable failure) {
                throw new AssertionError("Cannot inspect the native system menu", failure);
            }
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var ready = new CountDownLatch(1);
        var timer = fx(() -> {
            var checker = new AnimationTimer() {
                @Override public void handle(long now) {
                    if (condition.getAsBoolean()) { stop(); ready.countDown(); }
                }
            };
            checker.start();
            return checker;
        });
        try {
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Expected JavaFX state did not appear");
        } finally {
            fx(() -> { timer.stop(); return null; });
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
