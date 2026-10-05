package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.IterationSettings;
import javafx.animation.AnimationTimer;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CompletableFuture;
import java.util.List;
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
        await(() -> view.hasCompletedFrame() && !command("Render", "Edit Iteration Settings…").isDisable());
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
    void paletteMenuStartsWithDefaultAndAppliesEveryPreset() throws Exception {
        fx(() -> {
            Menu palette = (Menu) colorItem("Palette");
            assertEquals("Ice", palette.getItems().getFirst().getText());
            assertTrue(((RadioMenuItem) palette.getItems().getFirst()).isSelected());
            assertEquals(15, palette.getItems().size());
            return null;
        });
        for (PalettePreset preset : PalettePreset.values()) {
            fx(() -> { command("Color/Palette", preset.toString()).fire(); return null; });
            ColoringSettings expected = new ColoringSettings(preset);
            await(() -> {
                FractalSurface surface = (FractalSurface) view.getChildrenUnmodifiable().stream()
                        .filter(FractalSurface.class::isInstance).findFirst().orElseThrow();
                return surface.completedRender() != null
                        && surface.completedRender().scene().coloring().equals(expected);
            });
            fx(() -> {
                assertEquals(expected, view.sceneSnapshot().coloring());
                assertTrue(selectedChoice("Palette", preset.toString()));
                FractalSurface surface = (FractalSurface) view.getChildrenUnmodifiable().stream()
                        .filter(FractalSurface.class::isInstance).findFirst().orElseThrow();
                assertEquals(view.sceneSnapshot().coloring(), surface.completedRender().scene().coloring());
                return null;
            });
        }
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
    void colorResetRestoresAllSettingsAndMenuSelectionsFromRestoredScene() throws Exception {
        FractalScene customized = fx(() -> view.sceneSnapshot().withColoring(new ColoringSettings(
                PalettePreset.FIRE,
                List.of(new ColorStop(0, 0xFF123456), new ColorStop(1, 0xFFABCDEF)),
                7.5, 0.6, true, OrbitTrap.CROSS)));
        fx(() -> {
            view.close();
            view = new FractalView(customized, true);
            menuBar = new MainMenuBar(stage, view, customized, () -> {});
            BorderPane root = (BorderPane) stage.getScene().getRoot();
            root.setCenter(view);
            root.setTop(menuBar);
            return null;
        });
        await(() -> !colorItem("Reset Colors to Defaults").isDisable());
        FractalScene before = fx(view::sceneSnapshot);
        fx(() -> { colorItem("Reset Colors to Defaults").fire(); return null; });
        await(() -> !colorItem("Reset Colors to Defaults").isDisable());
        fx(() -> {
            assertEquals(before.withColoring(new ColoringSettings(PalettePreset.ICE)), view.sceneSnapshot());
            assertTrue(selectedChoice("Palette", "Ice"));
            assertTrue(selectedChoice("Orbit Trap", "Off"));
            assertFalse(((CheckMenuItem) colorItem("Histogram Coloring")).isSelected());
            assertFalse(((CheckMenuItem) colorItem("Animate Palette")).isSelected());
            assertFalse(colorItem("Animate Palette").isDisable());
            FractalSurface surface = (FractalSurface) view.getChildrenUnmodifiable().stream()
                    .filter(FractalSurface.class::isInstance).findFirst().orElseThrow();
            assertEquals(view.sceneSnapshot().coloring(), surface.completedRender().scene().coloring());
            return null;
        });
    }

    @Test
    void colorResetStopsAnimationAndCanBeRepeated() throws Exception {
        FractalScene before = fx(view::sceneSnapshot);
        int[] originalPixels = fx(this::displayedPixels);
        fx(() -> {
            CheckMenuItem animation = (CheckMenuItem) colorItem("Animate Palette");
            animation.setSelected(true);
            animation.fire();
            return null;
        });
        await(() -> view.sceneSnapshot().coloring().offset() > 0);
        fx(() -> { colorItem("Reset Colors to Defaults").fire(); return null; });
        await(() -> !colorItem("Reset Colors to Defaults").isDisable());
        assertEquals(before, fx(view::sceneSnapshot));
        assertArrayEquals(originalPixels, fx(this::displayedPixels));
        assertFalse(fx(() -> ((CheckMenuItem) colorItem("Animate Palette")).isSelected()));
        fx(() -> { colorItem("Reset Colors to Defaults").fire(); return null; });
        await(() -> !colorItem("Reset Colors to Defaults").isDisable());
        assertEquals(before, fx(view::sceneSnapshot));
        assertArrayEquals(originalPixels, fx(this::displayedPixels));
    }

    private int[] displayedPixels() {
        FractalSurface surface = (FractalSurface) view.getChildrenUnmodifiable().stream()
                .filter(FractalSurface.class::isInstance).findFirst().orElseThrow();
        var image = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    private Menu colorMenu() {
        return menuBar.getMenus().stream()
                .filter(menu -> "Color".equals(menu.getText())).findFirst().orElseThrow();
    }

    private MenuItem colorItem(String title) {
        return colorMenu().getItems().stream()
                .filter(item -> title.equals(item.getText())).findFirst().orElseThrow();
    }

    private boolean selectedChoice(String menu, String title) {
        return ((Menu) colorItem(menu)).getItems().stream()
                .filter(item -> title.equals(item.getText()))
                .map(item -> (RadioMenuItem) item).findFirst().orElseThrow().isSelected();
    }

    @Test
    void iterationMenuAppliesBothValuesFromOneThemedDialog() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openIterationEditor();
        if (MacIterationSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitIterationProbe.sheet(menuBar);
                assertNotNull(sheet);
                assertEquals(Integer.toString(initial.iterations().baseIterations()), AppKitIterationProbe.text(sheet, "base"));
                assertEquals(Integer.toString(initial.iterations().iterationsPerZoomLevel()), AppKitIterationProbe.text(sheet, "zoom"));
                AppKitIterationProbe.text(sheet, "base", "420");
                AppKitIterationProbe.text(sheet, "zoom", "70");
                assertEquals(initial, view.sceneSnapshot());
                AppKitIterationProbe.action(sheet, "apply");
                return null;
            });
            await(() -> view.sceneSnapshot().iterations().equals(new IterationSettings(420, 70)));
            return;
        }
        DialogPane pane = awaitDialog("Iteration Settings");
        fx(() -> {
            var base = input(pane, "iteration-base");
            var zoom = input(pane, "iteration-zoom");
            assertEquals(Integer.toString(initial.iterations().baseIterations()), base.getText());
            assertEquals(Integer.toString(initial.iterations().iterationsPerZoomLevel()), zoom.getText());
            pane.getScene().getPreferences().setColorScheme(ColorScheme.DARK);
            pane.applyCss();
            assertEquals(Color.web("#292929"), pane.getBackground().getFills().getFirst().getFill());
            base.setText("420");
            zoom.setText("70");
            assertEquals(initial, view.sceneSnapshot());
            confirm(pane);
            return null;
        });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(new IterationSettings(420, 70), fx(() -> view.sceneSnapshot().iterations()));
    }

    @Test
    void cancellingIterationDialogKeepsSceneUnchangedAfterReset() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openIterationEditor();
        if (MacIterationSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitIterationProbe.sheet(menuBar);
                AppKitIterationProbe.text(sheet, "base", "420");
                AppKitIterationProbe.text(sheet, "zoom", "70");
                AppKitIterationProbe.action(sheet, "reset");
                assertEquals("300", AppKitIterationProbe.text(sheet, "base"));
                assertEquals("50", AppKitIterationProbe.text(sheet, "zoom"));
                AppKitIterationProbe.action(sheet, "cancel");
                return null;
            });
            fx(() -> { assertEquals(initial, view.sceneSnapshot()); return null; });
            return;
        }
        DialogPane pane = awaitDialog("Iteration Settings");
        fx(() -> {
            input(pane, "iteration-base").setText("420");
            input(pane, "iteration-zoom").setText("70");
            Button reset = pane.getButtonTypes().stream()
                    .filter(type -> "Reset to Defaults".equals(type.getText()))
                    .map(type -> (Button) pane.lookupButton(type)).findFirst().orElseThrow();
            reset.fire();
            ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            return null;
        });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(initial, fx(view::sceneSnapshot));
    }

    @Test
    void invalidIterationInputShowsErrorAndKeepsSceneUnchanged() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openIterationEditor();
        if (MacIterationSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitIterationProbe.sheet(menuBar);
                for (String invalid : List.of("not a number", "0", "2147483648", "999999999999999999999")) {
                    AppKitIterationProbe.text(sheet, "base", invalid);
                    AppKitIterationProbe.action(sheet, "apply");
                    assertTrue(AppKitIterationProbe.showing(sheet));
                    assertEquals(invalid, AppKitIterationProbe.text(sheet, "base"));
                    assertFalse(AppKitIterationProbe.text(sheet, "error").isEmpty());
                    assertEquals(initial, view.sceneSnapshot());
                }
                AppKitIterationProbe.text(sheet, "base", "420");
                AppKitIterationProbe.text(sheet, "zoom", "-1");
                AppKitIterationProbe.action(sheet, "apply");
                assertTrue(AppKitIterationProbe.showing(sheet));
                assertEquals(initial, view.sceneSnapshot());
                AppKitIterationProbe.text(sheet, "zoom", "70");
                AppKitIterationProbe.action(sheet, "apply");
                return null;
            });
            await(() -> view.sceneSnapshot().iterations().equals(new IterationSettings(420, 70)));
            return;
        }
        DialogPane pane = awaitDialog("Iteration Settings");
        fx(() -> {
            input(pane, "iteration-base").setText("not a number");
            input(pane, "iteration-zoom").setText("70");
            confirm(pane);
            assertTrue(pane.lookup("#dialog-validation-error").isVisible());
            assertTrue(pane.getScene().getWindow().isShowing());
            assertEquals(initial, view.sceneSnapshot());
            input(pane, "iteration-base").setText("420");
            input(pane, "iteration-zoom").setText("-1");
            confirm(pane);
            assertTrue(pane.lookup("#dialog-validation-error").isVisible());
            assertEquals(initial, view.sceneSnapshot());
            input(pane, "iteration-zoom").setText("70");
            confirm(pane);
            return null;
        });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(new IterationSettings(420, 70), fx(() -> view.sceneSnapshot().iterations()));
    }

    @Test
    void availabilityKeepsSceneSpecificRestrictionsAfterRendering() throws Exception {
        fx(() -> {
            assertTrue(command("Render", "Deep Zoom Antialiasing").isDisable());
            assertTrue(command("Render", "Edit Julia Parameters…").isDisable());
            command("Fractal", "Julia").fire();
            return null;
        });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());
        fx(() -> {
            var histogram = (javafx.scene.control.CheckMenuItem) command("Color", "Histogram Coloring");
            histogram.setSelected(true);
            histogram.fire();
            assertTrue(command("Color", "Animate Palette").isDisable());
            return null;
        });
        await(() -> !command("Color", "Edit Palette…").isDisable());
        fx(() -> {
            assertTrue(command("Color", "Animate Palette").isDisable());
            var histogram = (javafx.scene.control.CheckMenuItem) command("Color", "Histogram Coloring");
            histogram.setSelected(false);
            histogram.fire();
            return null;
        });
        await(() -> !command("Color", "Animate Palette").isDisable());
        fx(() -> { command("Color/Orbit Trap", "Point").fire(); return null; });
        await(() -> !command("Color", "Edit Palette…").isDisable());
        fx(() -> {
            assertTrue(command("Color", "Animate Palette").isDisable());
            command("Color/Orbit Trap", "Off").fire();
            return null;
        });
        await(() -> !command("Color", "Animate Palette").isDisable());
    }

    private MenuItem command(String path, String title) {
        java.util.List<? extends MenuItem> items = menuBar.getMenus();
        for (String part : path.split("/")) {
            Menu menu = (Menu) items.stream().filter(item -> part.equals(item.getText()))
                    .findFirst().orElseThrow();
            items = menu.getItems();
        }
        return items.stream().filter(item -> title.equals(item.getText())).findFirst().orElseThrow();
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

    private static TextField input(DialogPane pane, String id) {
        return (TextField) pane.lookup("#" + id);
    }

    private static void confirm(DialogPane pane) {
        pane.getButtonTypes().stream()
                .map(type -> (Button) pane.lookupButton(type))
                .filter(Button::isDefaultButton).findFirst().orElseThrow().fire();
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

    private static DialogPane awaitDialog(String title) throws Exception {
        await(() -> Window.getWindows().stream()
                .filter(window -> window.isShowing() && window.getScene() != null
                        && window instanceof Stage dialog && title.equals(dialog.getTitle()))
                .map(window -> (DialogPane) window.getScene().getRoot())
                .findAny().isPresent());
        return fx(() -> Window.getWindows().stream()
                .filter(window -> window.isShowing() && window.getScene() != null
                        && window instanceof Stage dialog && title.equals(dialog.getTitle()))
                .map(window -> (DialogPane) window.getScene().getRoot())
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
