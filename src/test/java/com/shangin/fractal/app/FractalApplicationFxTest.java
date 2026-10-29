package com.shangin.fractal.app;

import com.shangin.fractal.ui.FractalView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production startup path in an isolated graphical test fork. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalApplicationFxTest {
    @TempDir
    Path directory;

    public static final class StartupApplication extends FractalFxApplication {
        static final CompletableFuture<Stage> started = new CompletableFuture<>();

        @Override
        public void start(Stage stage) {
            super.start(stage);
            started.complete(stage);
        }
    }

    @Test
    void startupFocusesCanvas() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        Thread launcher = new Thread(() -> {
            try {
                Application.launch(StartupApplication.class);
            } catch (Throwable failure) {
                StartupApplication.started.completeExceptionally(failure);
            }
        }, "application-startup-test");
        launcher.setDaemon(true);
        launcher.start();
        try {
            Stage stage = StartupApplication.started.get(30, TimeUnit.SECONDS);
            FractalView canvas = fx(() -> ((StackPane)
                    ((com.shangin.fractal.ui.MainView) stage.getScene().getRoot()).getCenter())
                    .getChildren().stream().filter(FractalView.class::isInstance)
                    .map(FractalView.class::cast).findFirst().orElseThrow());
            fx(() -> {
                assertSame(canvas, stage.getScene().getFocusOwner(),
                        "Startup must focus the canvas that handles navigation keys");
                return null;
            });
        } finally {
            Platform.runLater(Platform::exit);
            launcher.join(15_000);
            System.setProperty("user.home", previousHome);
            assertFalse(launcher.isAlive(), "The application must finish its shutdown");
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
