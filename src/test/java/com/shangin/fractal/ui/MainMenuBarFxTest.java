package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.IterationSettings;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class MainMenuBarFxTest {
    private Stage stage;
    private FractalView view;
    private MainMenuBar menuBar;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @BeforeEach
    void showMainWindow() throws Exception {
        fx(() -> {
            stage = new Stage();
            view = new FractalView(FractalPreset.MANDELBROT, PalettePreset.ICE, false);
            menuBar = new MainMenuBar(stage, view, FractalPreset.MANDELBROT, PalettePreset.ICE, () -> {});
            BorderPane root = new BorderPane(view);
            root.setTop(menuBar);
            stage.setScene(new Scene(root, 320, 240));
            stage.show();
            return null;
        });
        await(() -> !renderMenu().isDisable());
    }

    @AfterEach
    void closeMainWindow() throws Exception {
        fx(() -> {
            Window.getWindows().stream()
                    .filter(window -> window != stage)
                    .toList().forEach(Window::hide);
            view.close();
            stage.close();
            return null;
        });
    }

    @Test
    void helpUsesThePublicApplicationName() throws Exception {
        fx(() -> {
            Menu help = menuBar.getMenus().stream()
                    .filter(menu -> "Help".equals(menu.getText())).findFirst().orElseThrow();
            assertEquals("FractalLens Help", help.getItems().getFirst().getText());
            var dialog = FractalDialogs.helpDialog(stage);
            assertEquals("FractalLens Help", dialog.getTitle());
            dialog.close();
            return null;
        });
    }

    @Test
    void iterationMenuAppliesBothValuesOnlyAfterSecondConfirmation() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openIterationEditor();
        DialogPane base = awaitDialog("Iteration settings", "Base iteration count");
        assertEquals(Integer.toString(initial.iterations().baseIterations()), fx(() -> input(base).getText()));
        fx(() -> { input(base).setText("420"); confirm(base); return null; });

        DialogPane increment = awaitDialog("Iteration settings", "Additional iterations per zoom level");
        assertEquals(initial, fx(view::sceneSnapshot));
        assertEquals(Integer.toString(initial.iterations().iterationsPerZoomLevel()),
                fx(() -> input(increment).getText()));
        fx(() -> { input(increment).setText("70"); confirm(increment); return null; });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(new IterationSettings(420, 70), fx(() -> view.sceneSnapshot().iterations()));
    }

    @Test
    void cancellingSecondIterationDialogKeepsSceneUnchanged() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openIterationEditor();
        DialogPane base = awaitDialog("Iteration settings", "Base iteration count");
        fx(() -> { input(base).setText("420"); confirm(base); return null; });

        DialogPane increment = awaitDialog("Iteration settings", "Additional iterations per zoom level");
        fx(() -> { ((Button) increment.lookupButton(ButtonType.CANCEL)).fire(); return null; });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(initial, fx(view::sceneSnapshot));
    }

    @Test
    void invalidIterationInputShowsErrorAndKeepsSceneUnchanged() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openIterationEditor();
        DialogPane base = awaitDialog("Iteration settings", "Base iteration count");
        fx(() -> { input(base).setText("not a number"); confirm(base); return null; });

        DialogPane increment = awaitDialog("Iteration settings", "Additional iterations per zoom level");
        fx(() -> { input(increment).setText("70"); confirm(increment); return null; });
        DialogPane error = awaitDialog("Invalid iteration settings", "Invalid iteration settings");
        assertEquals("Enter a positive base count and a non-negative zoom increment.",
                fx(error::getContentText));
        assertEquals(initial, fx(view::sceneSnapshot));
        fx(() -> { ((Button) error.lookupButton(ButtonType.OK)).fire(); return null; });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(initial, fx(view::sceneSnapshot));
    }

    private CompletableFuture<Void> openIterationEditor() throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        fx(() -> {
            MenuItem item = renderMenu().getItems().stream()
                    .filter(candidate -> "Edit Iteration Settings…".equals(candidate.getText()))
                    .findFirst().orElseThrow();
            assertFalse(item.isDisable());
            Platform.runLater(() -> {
                try {
                    item.fire();
                    finished.complete(null);
                } catch (Throwable failure) {
                    finished.completeExceptionally(failure);
                }
            });
            return null;
        });
        return finished;
    }

    private Menu renderMenu() {
        return menuBar.getMenus().stream()
                .filter(menu -> "Render".equals(menu.getText())).findFirst().orElseThrow();
    }

    private static TextField input(DialogPane pane) {
        return (TextField) pane.lookup(".text-field");
    }

    private static void confirm(DialogPane pane) {
        ((Button) pane.lookupButton(ButtonType.OK)).fire();
    }

    private static DialogPane awaitDialog(String title, String header) throws Exception {
        await(() -> Window.getWindows().stream()
                .filter(window -> window.isShowing() && window.getScene() != null
                        && window instanceof Stage dialog && title.equals(dialog.getTitle()))
                .map(window -> (DialogPane) window.getScene().getRoot())
                .anyMatch(pane -> header.equals(pane.getHeaderText())));
        return fx(() -> Window.getWindows().stream()
                .filter(window -> window.isShowing() && window.getScene() != null
                        && window instanceof Stage dialog && title.equals(dialog.getTitle()))
                .map(window -> (DialogPane) window.getScene().getRoot())
                .filter(pane -> header.equals(pane.getHeaderText()))
                .findFirst().orElseThrow());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AnimationTimer timer = fx(() -> {
            AnimationTimer checker = new AnimationTimer() {
                @Override public void handle(long now) {
                    if (condition.getAsBoolean()) {
                        stop();
                        ready.countDown();
                    }
                }
            };
            checker.start();
            return checker;
        });
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS), "Expected JavaFX state did not appear");
        } finally {
            fx(() -> { timer.stop(); return null; });
        }
    }

    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
