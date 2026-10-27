package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.JuliaParameters;
import com.shangin.fractal.formula.JuliaFormula;
import com.shangin.fractal.render.FractalCalculator;
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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path bookmarkDirectory;
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
            menuBar = new MainMenuBar(stage, view, FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE), () -> {},
                    new com.shangin.fractal.app.BookmarkStore(bookmarkDirectory));
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
            menuBar.close();
            view.close();
            stage.close();
            return null;
        });
    }

    @Test
    void renderMenuOffersNoAntialiasingPatternChoice() throws Exception {
        List<String> titles = fx(() -> menuBar.getMenus().stream()
                .filter(menu -> "Render".equals(menu.getText()))
                .findFirst().orElseThrow().getItems().stream()
                .map(item -> item instanceof javafx.scene.control.SeparatorMenuItem ? "-" : item.getText())
                .toList());

        assertEquals(List.of("Edit Iteration Settings…", "Edit Julia Parameters…"), titles);
    }

    @Test
    void fileMenuOpensApplicationSettingsWithStandardShortcut() throws Exception {
        List<String> titles = fx(() -> menuBar.getMenus().stream()
                .filter(menu -> "File".equals(menu.getText()))
                .findFirst().orElseThrow().getItems().stream()
                .map(item -> item instanceof javafx.scene.control.SeparatorMenuItem ? "-" : item.getText())
                .toList());

        assertEquals(List.of("Export PNG…", "-", "Settings…", "-", "Close Window"), titles.subList(0, 5));
        assertEquals(new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.COMMA,
                javafx.scene.input.KeyCombination.SHORTCUT_DOWN),
                fx(() -> command("File", SettingsWindow.MENU_TITLE).getAccelerator()));
    }

    @Test
    void exportDimensionsRetainInvalidDraftAndApplyIndependentTarget() throws Exception {
        FractalScene original = fx(view::sceneSnapshot);
        var dialog = fx(() -> {
            var editor = FractalDialogs.exportSizeDialog(stage, view.exportTarget());
            editor.show();
            return editor;
        });
        TextField width = fx(() -> input(dialog.getDialogPane(), "export-width"));
        await(width::isFocused);
        fx(() -> {
            TextField height = input(dialog.getDialogPane(), "export-height");
            width.setText("2147483647");
            height.setText("2147483647");
            confirm(dialog.getDialogPane());
            assertTrue(dialog.isShowing());
            assertEquals("2147483647", width.getText());
            assertTrue(dialog.getDialogPane().lookup("#dialog-validation-error").isVisible());
            width.setText("3840");
            height.setText("2160");
            assertTrue(((javafx.scene.control.Label) dialog.getDialogPane()
                    .lookup("#export-size-estimate")).getText().contains("31.6 MiB"));
            assertTrue(dialog.getDialogPane().getButtonTypes().stream()
                    .anyMatch(button -> button.getText().equals("Export…")));
            width.setText("invalid");
            assertTrue(((javafx.scene.control.Label) dialog.getDialogPane()
                    .lookup("#export-size-estimate")).getText().contains("Enter valid dimensions"));
            width.setText("67");
            height.setText("41");
            width.fireEvent(new javafx.event.ActionEvent());
            assertFalse(dialog.isShowing());
            assertEquals(new com.shangin.fractal.render.RenderTarget(67, 41), dialog.getResult());
            assertEquals(original, view.sceneSnapshot());
            return null;
        });
    }

    @Test
    void exportDimensionsCancelLeavesSceneAndHistoryUntouched() throws Exception {
        fx(() -> {
            FractalScene original = view.sceneSnapshot();
            boolean undoDisabled = command("Edit", "Undo").isDisable();
            var dialog = FractalDialogs.exportSizeDialog(stage, view.exportTarget());
            dialog.show();
            input(dialog.getDialogPane(), "export-width").setText("invalid");
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
            assertFalse(dialog.isShowing());
            assertNull(dialog.getResult());
            assertEquals(original, view.sceneSnapshot());
            assertEquals(undoDisabled, command("Edit", "Undo").isDisable());
            return null;
        });
    }

    @Test
    void exportMenuKeepsCancellationAvailableDuringNavigation() throws Exception {
        fx(() -> {
            menuBar.setExportInProgress(true);
            assertFalse(command("File", "Cancel Export PNG").isDisable());
            view.setFractal(FractalPreset.JULIA);
            assertFalse(command("File", "Cancel Export PNG").isDisable());
            return null;
        });
        await(view::hasCompletedFrame);
        fx(() -> {
            menuBar.setExportInProgress(false);
            assertFalse(command("File", "Export PNG…").isDisable());
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
            menuBar.close();
            menuBar = new MainMenuBar(stage, view, customized, () -> {},
                    new com.shangin.fractal.app.BookmarkStore(bookmarkDirectory));
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

    @Test
    void sceneUndoRedoRestoresPixelsMenusAndDiscardsNewBranch() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        int[] pixels = fx(this::displayedPixels);
        fx(() -> { command("Fractal", "Julia").fire(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());
        FractalScene julia = fx(view::sceneSnapshot);
        fx(() -> { command("Edit", "Undo").fire(); return null; });
        await(() -> !command("Render", "Edit Iteration Settings…").isDisable());
        fx(() -> {
            assertEquals(initial, view.sceneSnapshot());
            assertArrayEquals(pixels, displayedPixels());
            assertTrue(((RadioMenuItem) command("Fractal", "Mandelbrot")).isSelected());
            assertTrue(command("Render", "Edit Julia Parameters…").isDisable());
            assertFalse(command("Edit", "Redo").isDisable());
            return null;
        });
        fx(() -> { command("Edit", "Redo").fire(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());
        assertEquals(julia, fx(view::sceneSnapshot));
        fx(() -> {
            command("Edit", "Undo").fire();
            view.setIterations(new IterationSettings(420, 70));
            assertTrue(command("Edit", "Redo").isDisable());
            command("Edit", "Undo").fire();
            return null;
        });
        await(() -> !command("Render", "Edit Iteration Settings…").isDisable());
        assertEquals(initial, fx(view::sceneSnapshot));
        assertArrayEquals(pixels, fx(this::displayedPixels));
    }

    @Test
    void clipboardPresetAndBookmarkRestoreCompleteScene() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        fx(() -> { command("View", "Copy Scene Preset").fire(); view.setFractal(FractalPreset.JULIA); return null; });
        fx(() -> { command("View", "Apply Scene Preset from Clipboard").fire(); return null; });
        await(() -> !command("Render", "Edit Iteration Settings…").isDisable());
        assertEquals(initial, fx(view::sceneSnapshot));
        await(() -> !command("Bookmarks", "Add Bookmark…").isDisable());
        saveBookmark("My scene", true);
        await(() -> menuBar.getMenus().stream().filter(menu -> "Bookmarks".equals(menu.getText()))
                .findFirst().orElseThrow().getItems().stream().anyMatch(item -> "My scene".equals(item.getText())));
        assertBookmarkCommands(false);
        saveBookmark("My scene", false);
        await(() -> !command("Bookmarks", "Add Bookmark…").isDisable());
        assertBookmarkCommands(false);
        fx(() -> { command("Bookmarks", "Reload Bookmarks").fire(); return null; });
        await(() -> !command("Bookmarks", "Add Bookmark…").isDisable());
        assertBookmarkCommands(false);
        fx(() -> { view.setFractal(FractalPreset.JULIA); command("Bookmarks/My scene", "Restore").fire(); return null; });
        await(() -> !command("Render", "Edit Iteration Settings…").isDisable());
        assertEquals(initial, fx(view::sceneSnapshot));
        assertEquals(initial, new com.shangin.fractal.app.BookmarkStore(bookmarkDirectory).load().getFirst().scene());
        fx(() -> {
            command("Bookmarks/My scene", "Delete").fire();
            assertBookmarkCommandsOnFxThread(true);
            return null;
        });
        await(() -> !command("Bookmarks", "Add Bookmark…").isDisable());
        assertEquals(3, fx(() -> menuBar.getMenus().stream().filter(menu -> "Bookmarks".equals(menu.getText()))
                .findFirst().orElseThrow().getItems().size()));
        assertTrue(new com.shangin.fractal.app.BookmarkStore(bookmarkDirectory).load().isEmpty());
    }

    @Test
    void failedBookmarkReloadReenablesCommandsAndAllowsRetry() throws Exception {
        await(() -> !command("Bookmarks", "Reload Bookmarks").isDisable());
        var invalid = bookmarkDirectory.resolve("invalid.json");
        java.nio.file.Files.writeString(invalid, "{}");
        fx(() -> {
            command("Bookmarks", "Reload Bookmarks").fire();
            assertTrue(command("Bookmarks", "Reload Bookmarks").isDisable());
            assertTrue(command("Bookmarks", "Add Bookmark…").isDisable());
            return null;
        });
        dismissMessage("Bookmark storage failed");
        fx(() -> {
            assertFalse(command("Bookmarks", "Reload Bookmarks").isDisable());
            assertFalse(command("Bookmarks", "Add Bookmark…").isDisable());
            if (System.getProperty("os.name", "").startsWith("Mac")) {
                AppKitMenuProbe.updateSubmenu("Bookmarks");
                assertTrue(AppKitMenuProbe.enabled("Bookmarks/Reload Bookmarks"));
                assertTrue(AppKitMenuProbe.enabled("Bookmarks/Add Bookmark…"));
            }
            return null;
        });
        java.nio.file.Files.delete(invalid);
        fx(() -> { command("Bookmarks", "Reload Bookmarks").fire(); return null; });
        await(() -> !command("Bookmarks", "Reload Bookmarks").isDisable());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.MAC)
    @org.junit.jupiter.api.condition.EnabledIf(value = "nativeSheetsAvailable",
            disabledReason = "Native sheet overlap requires the packaged AppKit bridge")
    void bookmarkAndParameterSheetsDoNotOverlapOrSaveOnCancel() throws Exception {
        fx(() -> {
            command("Bookmarks", "Add Bookmark…").fire();
            var sheet = AppKitParameterProbe.bookmarkSheet(menuBar);
            assertNotNull(sheet);
            AppKitParameterProbe.text(sheet, "first", "Unsaved scene");
            command("Bookmarks", "Add Bookmark…").fire();
            assertSame(sheet, AppKitParameterProbe.bookmarkSheet(menuBar));
            assertEquals("Unsaved scene", AppKitParameterProbe.text(sheet, "first"));
            command("Render", "Edit Iteration Settings…").fire();
            assertNull(AppKitParameterProbe.sheet(menuBar));
            AppKitParameterProbe.action(sheet, "cancel");
            return null;
        });
        fx(() -> {
            assertFalse(AppKitParameterProbe.bookmarkSheet(menuBar).isOpen());
            command("Render", "Edit Iteration Settings…").fire();
            var iterations = AppKitParameterProbe.sheet(menuBar);
            assertNotNull(iterations);
            command("Bookmarks", "Add Bookmark…").fire();
            assertFalse(AppKitParameterProbe.bookmarkSheet(menuBar).isOpen());
            assertTrue(iterations.isOpen());
            AppKitParameterProbe.action(iterations, "cancel");
            return null;
        });
        assertTrue(new com.shangin.fractal.app.BookmarkStore(bookmarkDirectory).load().isEmpty());
    }

    static boolean nativeSheetsAvailable() { return MacParameterSheet.isAvailable(); }

    private void assertBookmarkCommands(boolean busy) throws Exception {
        fx(() -> { assertBookmarkCommandsOnFxThread(busy); return null; });
    }

    private void saveBookmark(String name, boolean checkInvalid) throws Exception {
        var save = fireLater(commandFx("Bookmarks", "Add Bookmark…"));
        if (MacBookmarkSheet.isAvailable()) {
            save.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.bookmarkSheet(menuBar);
                assertNotNull(sheet, "The bookmark command must open a native sheet on macOS");
                assertTrue(AppKitParameterProbe.showing(sheet));
                if (checkInvalid) {
                    AppKitParameterProbe.action(sheet, "apply");
                    assertEquals(com.shangin.fractal.app.BookmarkStore.NAME_ERROR,
                            AppKitParameterProbe.text(sheet, "error"));
                }
                AppKitParameterProbe.text(sheet, "first", name);
                AppKitParameterProbe.action(sheet, "apply");
                return null;
            });
        } else {
            DialogPane pane = awaitDialog("Add Bookmark");
            if (checkInvalid) {
                fx(() -> { confirm(pane); assertTrue(pane.lookup("#dialog-validation-error").isVisible()); return null; });
            }
            fx(() -> { input(pane, "bookmark-name").setText(name); confirm(pane); return null; });
            save.get(10, TimeUnit.SECONDS);
        }
        await(() -> !command("Bookmarks", "Add Bookmark…").isDisable()
                && menuBar.getMenus().stream().filter(menu -> "Bookmarks".equals(menu.getText()))
                .findFirst().orElseThrow().getItems().stream().anyMatch(item -> name.equals(item.getText())));
    }

    private void assertBookmarkCommandsOnFxThread(boolean busy) {
        Menu bookmarks = menuBar.getMenus().stream().filter(menu -> "Bookmarks".equals(menu.getText()))
                .findFirst().orElseThrow();
        assertFalse(bookmarks.isDisable(), "The menu container must remain openable during storage");
        assertFalse(((Menu) bookmarks.getItems().getLast()).isDisable());
        for (String path : List.of("Bookmarks", "Bookmarks/My scene")) {
            Menu menu = path.equals("Bookmarks") ? bookmarks : (Menu) bookmarks.getItems().getLast();
            for (MenuItem item : menu.getItems()) {
                if (item instanceof Menu || item instanceof javafx.scene.control.SeparatorMenuItem) continue;
                assertEquals(busy, item.isDisable(), path + "/" + item.getText());
                if (System.getProperty("os.name", "").startsWith("Mac")) {
                    assertEquals(!busy, AppKitMenuProbe.enabled(path + "/" + item.getText()));
                }
            }
        }
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            AppKitMenuProbe.updateSubmenu("Bookmarks");
            assertEquals(!busy, AppKitMenuProbe.enabled("Bookmarks/My scene/Restore"));
            assertEquals(!busy, AppKitMenuProbe.enabled("Bookmarks/My scene/Delete"));
        }
    }

    @Test
    void pinchWithIntermediateRenderIsOneUndoAction() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        fx(() -> {
            view.fireEvent(new javafx.scene.input.ZoomEvent(javafx.scene.input.ZoomEvent.ZOOM_STARTED,
                    100, 80, 100, 80, false, false, false, false, false, false, 1, 1, null));
            view.fireEvent(new javafx.scene.input.ZoomEvent(javafx.scene.input.ZoomEvent.ZOOM,
                    100, 80, 100, 80, false, false, false, false, false, false, 1.2, 1.2, null));
            return null;
        });
        await(() -> !view.sceneSnapshot().viewport().equals(initial.viewport()) && view.isLatencyIdle());
        fx(() -> {
            view.fireEvent(new javafx.scene.input.ZoomEvent(javafx.scene.input.ZoomEvent.ZOOM,
                    100, 80, 100, 80, false, false, false, false, false, false, 1.2, 1.44, null));
            view.fireEvent(new javafx.scene.input.ZoomEvent(javafx.scene.input.ZoomEvent.ZOOM_FINISHED,
                    100, 80, 100, 80, false, false, false, false, false, false, 1, 1.44, null));
            view.undoScene();
            return null;
        });
        await(() -> !command("Render", "Edit Iteration Settings…").isDisable());
        assertEquals(initial, fx(view::sceneSnapshot));
        assertFalse(fx(view::canUndo));
        assertTrue(fx(view::canRedo));
    }

    @Test
    void textUndoKeepsSceneHistoryUntouched() throws Exception {
        fx(() -> {
            menuBar.setUseSystemMenuBar(false);
            view.setIterations(new IterationSettings(420, 70));
            return null;
        });
        await(() -> !command("Render", "Edit Iteration Settings…").isDisable());
        FractalScene scene = fx(view::sceneSnapshot);
        TextField field = fx(() -> {
            TextField input = new TextField("draft");
            ((BorderPane) stage.getScene().getRoot()).setBottom(input);
            stage.toFront(); stage.requestFocus(); input.requestFocus();
            input.appendText(" changed");
            return input;
        });
        await(() -> field.isFocused() && stage.isFocused());
        fx(() -> {
            runEditAccelerator("Undo");
            assertEquals("draft", field.getText());
            assertTrue(command("Edit", "Undo").isDisable());
            assertFalse(command("Edit", "Redo").isDisable());
            assertEquals(scene, view.sceneSnapshot());
            assertTrue(view.canUndo());
            runEditAccelerator("Redo");
            assertEquals("draft changed", field.getText());
            return null;
        });
    }

    @Test
    void bookmarkDialogTracksTextHistoryAndFocusWithoutOpeningEditMenu() throws Exception {
        fx(() -> { view.setIterations(new IterationSettings(420, 70)); return null; });
        await(view::isLatencyIdle);
        FractalScene scene = fx(view::sceneSnapshot);
        var dialog = fx(() -> {
            var editor = FractalDialogs.bookmarkNameDialog(stage);
            editor.show();
            return editor;
        });
        TextField field = fx(() -> input(dialog.getDialogPane(), "bookmark-name"));
        await(() -> field.isFocused() && dialog.getDialogPane().getScene().getWindow().isFocused());
        fx(() -> {
            assertTrue(command("Edit", "Undo").isDisable());
            field.appendText("draft");
            assertFalse(command("Edit", "Undo").isDisable());
            command("Edit", "Undo").fire();
            assertEquals("", field.getText());
            assertTrue(command("Edit", "Undo").isDisable());
            assertFalse(command("Edit", "Redo").isDisable());
            command("Edit", "Redo").fire();
            assertEquals("draft", field.getText());
            assertTrue(command("Edit", "Redo").isDisable());
            // A second field must replace the observed draft history in this scene.
            TextField other = new TextField();
            dialog.getDialogPane().setExpandableContent(other);
            dialog.getDialogPane().setExpanded(true);
            other.requestFocus();
            assertTrue(command("Edit", "Undo").isDisable());
            other.appendText("other");
            assertFalse(command("Edit", "Undo").isDisable());
            field.requestFocus();
            command("Edit", "Undo").fire();
            assertEquals("", field.getText());
            assertEquals("other", other.getText());
            assertEquals(scene, view.sceneSnapshot());
            dialog.close();
            stage.toFront(); stage.requestFocus(); view.requestFocus();
            return null;
        });
        await(() -> stage.isFocused() && view.isFocused());
        fx(() -> {
            assertFalse(command("Edit", "Undo").isDisable());
            assertTrue(command("Edit", "Redo").isDisable());
            assertEquals(scene, view.sceneSnapshot());
            return null;
        });
    }

    @Test
    void canvasFocusReenablesSceneAcceleratorsAfterEmptyTextHistory() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        fx(() -> {
            menuBar.setUseSystemMenuBar(false);
            view.setIterations(new IterationSettings(420, 70));
            return null;
        });
        await(() -> view.isLatencyIdle());
        FractalScene changed = fx(view::sceneSnapshot);
        TextField field = fx(() -> {
            TextField input = new TextField("draft");
            ((BorderPane) stage.getScene().getRoot()).setBottom(input);
            stage.toFront();
            stage.requestFocus();
            input.requestFocus();
            return input;
        });
        await(() -> field.isFocused() && stage.isFocused());
        fx(() -> {
            Menu edit = menuBar.getMenus().stream().filter(menu -> "Edit".equals(menu.getText()))
                    .findFirst().orElseThrow();
            edit.getOnShowing().handle(new javafx.event.Event(Menu.ON_SHOWING));
            assertTrue(command("Edit", "Undo").isDisable());
            view.requestFocus();
            return null;
        });
        await(view::isFocused);
        fx(() -> { runEditAccelerator("Undo"); return null; });
        await(() -> view.isLatencyIdle());
        assertEquals(initial, fx(view::sceneSnapshot));
        fx(() -> { runEditAccelerator("Redo"); return null; });
        await(() -> view.isLatencyIdle());
        assertEquals(changed, fx(view::sceneSnapshot));
    }

    private void runEditAccelerator(String title) {
        MenuItem item = command("Edit", title);
        assertFalse(item.isDisable());
        Runnable accelerator = stage.getScene().getAccelerators().get(item.getAccelerator());
        assertNotNull(accelerator, "Edit shortcut must be registered with the scene");
        accelerator.run();
    }

    @Test
    void pendingHeightResizeDoesNotChangeRestoredSnapshots() throws Exception {
        FractalScene first = fx(() -> view.sceneSnapshot().withViewport(
                new com.shangin.fractal.math.Viewport(view.sceneSnapshot().viewport().center(),
                        new java.math.BigDecimal("0.5000000000000000000000000001"))));
        fx(() -> { view.applyScene(first); return null; });
        await(() -> view.isLatencyIdle());
        fx(() -> { view.setIterations(new IterationSettings(420, 70)); return null; });
        await(() -> view.isLatencyIdle());
        FractalScene second = fx(view::sceneSnapshot);
        assertRestoredDuringResize(first, view::undoScene);
        assertRestoredDuringResize(second, view::redoScene);
        FractalScene preset = first.withIterations(new IterationSettings(360, 80));
        assertRestoredDuringResize(preset, () -> view.applyScene(preset));
        fx(() -> { view.undoScene(); return null; });
        await(() -> view.isLatencyIdle());
        assertEquals(second, fx(view::sceneSnapshot));
    }

    @Test
    void presetPrecisionUsesPendingRenderDimensionsBeforeChangingSceneOrHistory() throws Exception {
        fx(() -> {
            view.setManaged(false);
            view.resize(320, 240);
            view.applyScene(view.sceneSnapshot());
            return null;
        });
        await(view::isLatencyIdle);
        fx(() -> { view.setIterations(new IterationSettings(420, 70)); return null; });
        await(view::isLatencyIdle);
        fx(() -> { view.undoScene(); return null; });
        await(view::isLatencyIdle);
        fx(() -> {
            FractalScene initial = view.sceneSnapshot();
            boolean undo = view.canUndo();
            boolean redo = view.canRedo();
            FractalSurface surface = (FractalSurface) view.getChildrenUnmodifiable().stream()
                    .filter(FractalSurface.class::isInstance).findFirst().orElseThrow();
            var viewport = new com.shangin.fractal.math.Viewport("1", "0", "2e-12");
            FractalScene preset = initial.withFractal(FractalPreset.BURNING_SHIP, viewport);
            // The same preset is valid for the old buffer, but not the pending taller target.
            new FractalCamera(preset.fractal(), viewport).goTo(viewport, 320, 240,
                    surface.renderWidth(), surface.renderHeight());
            view.resize(320, 900);
            assertThrows(IllegalArgumentException.class, () -> view.applyScene(preset));
            assertEquals(initial, view.sceneSnapshot());
            assertEquals(undo, view.canUndo());
            assertEquals(redo, view.canRedo());
            assertEquals(initial, surface.completedRender().scene());
            // Restore the original size before the pending resize fires.
            view.resize(320, 240);
            view.redoScene();
            return null;
        });
        await(view::isLatencyIdle);
        assertEquals(new IterationSettings(420, 70), fx(() -> view.sceneSnapshot().iterations()));
    }

    private void assertRestoredDuringResize(FractalScene expected, Runnable restore) throws Exception {
        fx(() -> {
            // Keep the parent layout from replacing the deliberately resized test viewport.
            view.setManaged(false);
            view.resize(view.getWidth(), view.getHeight() + 40);
            restore.run();
            assertEquals(expected, view.sceneSnapshot());
            return null;
        });
        await(() -> view.isLatencyIdle());
        fx(() -> {
            assertEquals(expected, view.sceneSnapshot());
            FractalSurface surface = (FractalSurface) view.getChildrenUnmodifiable().stream()
                    .filter(FractalSurface.class::isInstance).findFirst().orElseThrow();
            assertEquals(surface.renderHeightFor((int) view.getHeight()), surface.renderHeight());
            assertEquals(expected, surface.completedRender().scene());
            return null;
        });
    }

    private MenuItem commandFx(String menu, String title) throws Exception {
        return fx(() -> command(menu, title));
    }

    @Test
    void invalidClipboardLeavesSceneAndHistoryUnchanged() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        fx(() -> {
            var content = new javafx.scene.input.ClipboardContent();
            content.putString("{\"schemaVersion\":999}");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            command("View", "Apply Scene Preset from Clipboard").fire();
            assertEquals(initial, view.sceneSnapshot());
            assertFalse(view.canUndo());
            return null;
        });
        dismissMessage("Invalid scene preset");
    }

    @Test
    void pendingZoomCanBeUndoneAndResizeDoesNotAddHistory() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        fx(() -> { view.zoom(true); view.undoScene(); return null; });
        await(() -> !command("Render", "Edit Iteration Settings…").isDisable());
        assertEquals(initial, fx(view::sceneSnapshot));
        fx(() -> { stage.setWidth(stage.getWidth() + 30); return null; });
        await(() -> view.isLatencyIdle());
        assertFalse(fx(view::canUndo));
        assertTrue(fx(view::canRedo));
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
    void settingsApplyStandardAndDeepZoomModesThroughUndoableSceneHistory() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        assertEquals(new AntialiasSettings(), initial.antialiasing());
        var selected = new AntialiasSettings(InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.REFINED);
        CompletableFuture<Void> action = fireLater(fx(() -> command("File", SettingsWindow.MENU_TITLE)));
        if (MacSettingsSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.settingsSheet(menuBar);
                assertNotNull(sheet);
                var standard = AppKitParameterProbe.choice(sheet, 0);
                var deep = AppKitParameterProbe.choice(sheet, 1);
                assertEquals(SettingsWindow.index(InteractiveRenderMode.REFINED), AppKitParameterProbe.selectedIndex(standard));
                assertEquals(SettingsWindow.index(InteractiveRenderMode.BASE_ONLY), AppKitParameterProbe.selectedIndex(deep));
                assertTrue(AppKitParameterProbe.enabled(deep), "The deep zoom mode is editable outside deep zoom");
                AppKitParameterProbe.select(standard, SettingsWindow.index(InteractiveRenderMode.BASE_ONLY));
                AppKitParameterProbe.select(deep, SettingsWindow.index(InteractiveRenderMode.REFINED));
                assertEquals(initial, view.sceneSnapshot());
                AppKitParameterProbe.action(sheet, "apply");
                return null;
            });
        } else {
            DialogPane pane = awaitDialog(SettingsWindow.TITLE);
            fx(() -> {
                @SuppressWarnings("unchecked")
                var standard = (javafx.scene.control.ComboBox<String>) pane.lookup("#" + SettingsWindow.STANDARD_ID);
                @SuppressWarnings("unchecked")
                var deep = (javafx.scene.control.ComboBox<String>) pane.lookup("#" + SettingsWindow.DEEP_ID);
                assertFalse(deep.isDisabled(), "The deep zoom mode is editable outside deep zoom");
                standard.getSelectionModel().select(SettingsWindow.index(InteractiveRenderMode.BASE_ONLY));
                deep.getSelectionModel().select(SettingsWindow.index(InteractiveRenderMode.REFINED));
                assertEquals(initial, view.sceneSnapshot());
                confirm(pane);
                return null;
            });
            action.get(10, TimeUnit.SECONDS);
        }
        await(() -> surface().completedRender() != null
                && surface().completedRender().scene().antialiasing().equals(selected)
                && !command("Render", "Edit Iteration Settings…").isDisable());
        fx(() -> {
            assertEquals(selected, view.sceneSnapshot().antialiasing());
            RenderModeIndicator badge = (RenderModeIndicator) view.getChildrenUnmodifiable().get(1);
            assertTrue(badge.getText().contains("Complete"), badge.getText());
            assertTrue(badge.getTooltip().getText().contains("Antialiasing is off"));
            view.undoScene();
            assertEquals(initial.antialiasing(), view.sceneSnapshot().antialiasing());
            return null;
        });
        await(() -> surface().completedRender() != null
                && surface().completedRender().scene().antialiasing().equals(initial.antialiasing())
                && !command("Render", "Edit Iteration Settings…").isDisable());
    }

    @Test
    void iterationMenuAppliesBothValuesFromOneThemedDialog() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openEditor("Edit Iteration Settings…");
        if (MacIterationSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.sheet(menuBar);
                assertNotNull(sheet);
                assertEquals(Integer.toString(initial.iterations().baseIterations()), AppKitParameterProbe.text(sheet, "first"));
                assertEquals(Integer.toString(initial.iterations().iterationsPerZoomLevel()), AppKitParameterProbe.text(sheet, "second"));
                AppKitParameterProbe.text(sheet, "first", "420");
                AppKitParameterProbe.text(sheet, "second", "70");
                assertEquals(initial, view.sceneSnapshot());
                AppKitParameterProbe.action(sheet, "apply");
                return null;
            });
            await(() -> view.sceneSnapshot().iterations().equals(new IterationSettings(420, 70)));
            assertCompletedIterations(new IterationSettings(420, 70));
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
        assertCompletedIterations(new IterationSettings(420, 70));
    }

    @Test
    void cancellingIterationDialogKeepsSceneUnchangedAfterReset() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openEditor("Edit Iteration Settings…");
        if (MacIterationSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.sheet(menuBar);
                AppKitParameterProbe.text(sheet, "first", "420");
                AppKitParameterProbe.text(sheet, "second", "70");
                AppKitParameterProbe.action(sheet, "reset");
                assertEquals("300", AppKitParameterProbe.text(sheet, "first"));
                assertEquals("50", AppKitParameterProbe.text(sheet, "second"));
                AppKitParameterProbe.action(sheet, "cancel");
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
        CompletableFuture<Void> action = openEditor("Edit Iteration Settings…");
        if (MacIterationSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.sheet(menuBar);
                for (String invalid : List.of("not a number", "0", "2147483648", "999999999999999999999")) {
                    AppKitParameterProbe.text(sheet, "first", invalid);
                    AppKitParameterProbe.action(sheet, "apply");
                    assertTrue(AppKitParameterProbe.showing(sheet));
                    assertEquals(invalid, AppKitParameterProbe.text(sheet, "first"));
                    assertFalse(AppKitParameterProbe.text(sheet, "error").isEmpty());
                    assertEquals(initial, view.sceneSnapshot());
                }
                AppKitParameterProbe.text(sheet, "first", "420");
                AppKitParameterProbe.text(sheet, "second", "-1");
                AppKitParameterProbe.action(sheet, "apply");
                assertTrue(AppKitParameterProbe.showing(sheet));
                assertEquals(initial, view.sceneSnapshot());
                AppKitParameterProbe.text(sheet, "second", "70");
                AppKitParameterProbe.action(sheet, "apply");
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

    private void assertCompletedIterations(IterationSettings expected) throws Exception {
        await(() -> surface().completedRender() != null
                && surface().completedRender().scene().iterations().equals(expected)
                && !command("Render", "Edit Iteration Settings…").isDisable());
        fx(() -> {
            assertEquals(expected.baseIterations(), surface().completedRender().frame().request().maxIterations());
            RenderModeIndicator badge = (RenderModeIndicator) view.getChildrenUnmodifiable().get(1);
            assertTrue(badge.getTooltip().getText().contains("Iterations: " + expected.baseIterations()));
            return null;
        });
    }

    @Test
    void juliaMenuAppliesBothParametersToTheCompletedFrame() throws Exception {
        fx(() -> { command("Fractal", "Julia").fire(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = openEditor("Edit Julia Parameters…");
        JuliaParameters expected = new JuliaParameters(-0.4, 0.6);
        if (MacJuliaSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.juliaSheet(menuBar);
                assertNotNull(sheet);
                command("Render", "Edit Julia Parameters…").fire();
                assertSame(sheet, AppKitParameterProbe.juliaSheet(menuBar));
                command("Render", "Edit Iteration Settings…").fire();
                assertNull(AppKitParameterProbe.sheet(menuBar));
                assertTrue(sheet.isOpen());
                assertEquals(Double.toString(initial.juliaParameters().real()), AppKitParameterProbe.text(sheet, "first"));
                assertEquals(Double.toString(initial.juliaParameters().imaginary()), AppKitParameterProbe.text(sheet, "second"));
                AppKitParameterProbe.text(sheet, "first", "-0.4");
                AppKitParameterProbe.text(sheet, "second", "0.6");
                assertEquals(initial, view.sceneSnapshot());
                AppKitParameterProbe.action(sheet, "apply");
                return null;
            });
        } else {
            DialogPane pane = awaitDialog("Julia Parameters");
            fx(() -> {
                input(pane, "julia-real").setText("-0.4");
                input(pane, "julia-imaginary").setText("0.6");
                assertEquals(initial, view.sceneSnapshot());
                confirm(pane);
                return null;
            });
            action.get(10, TimeUnit.SECONDS);
        }
        await(() -> surface().completedRender() != null
                && surface().completedRender().scene().juliaParameters().equals(expected)
                && !command("Render", "Edit Julia Parameters…").isDisable());
        var completed = fx(() -> surface().completedRender());
        assertEquals(initial.withJuliaParameters(expected), fx(view::sceneSnapshot));
        var frame = completed.frame();
        var reference = new FractalCalculator(new JuliaFormula(expected.real(), expected.imaginary()))
                .calculate(frame.request().width(), frame.request().height(),
                        frame.renderGrid(), frame.request().maxIterations());
        for (int index = 0; index < reference.size(); index++) {
            assertEquals(reference.iterations(index), frame.samplePlane().iterations(index), "Iterations at " + index);
            assertEquals(reference.escaped(index), frame.samplePlane().escaped(index), "Escape at " + index);
            assertEquals(reference.smoothIterations(index), frame.samplePlane().smoothIterations(index), "Smooth value at " + index);
        }
    }

    @Test
    void juliaMenuKeepsTheSceneAndFrameWhenInvalidDraftIsCancelled() throws Exception {
        fx(() -> { command("Fractal", "Julia").fire(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());
        FractalScene initial = fx(view::sceneSnapshot);
        var completed = fx(() -> surface().completedRender());
        CompletableFuture<Void> action = openEditor("Edit Julia Parameters…");
        if (MacJuliaSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.juliaSheet(menuBar);
                AppKitParameterProbe.text(sheet, "first", "NaN");
                AppKitParameterProbe.text(sheet, "second", "0.6");
                AppKitParameterProbe.action(sheet, "apply");
                assertTrue(sheet.isOpen());
                assertEquals("NaN", AppKitParameterProbe.text(sheet, "first"));
                assertEquals("0.6", AppKitParameterProbe.text(sheet, "second"));
                assertFalse(AppKitParameterProbe.text(sheet, "error").isEmpty());
                assertEquals(initial, view.sceneSnapshot());
                assertEquals(completed, surface().completedRender());
                AppKitParameterProbe.action(sheet, "cancel");
                return null;
            });
            var sheet = fx(() -> AppKitParameterProbe.juliaSheet(menuBar));
            await(() -> !sheet.isOpen());
        } else {
            DialogPane pane = awaitDialog("Julia Parameters");
            fx(() -> {
                input(pane, "julia-real").setText("NaN");
                input(pane, "julia-imaginary").setText("0.6");
                confirm(pane);
                assertTrue(pane.getScene().getWindow().isShowing());
                assertEquals("NaN", input(pane, "julia-real").getText());
                assertEquals("0.6", input(pane, "julia-imaginary").getText());
                assertTrue(pane.lookup("#dialog-validation-error").isVisible());
                assertEquals(initial, view.sceneSnapshot());
                assertEquals(completed, surface().completedRender());
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                return null;
            });
            action.get(10, TimeUnit.SECONDS);
        }
        assertEquals(initial, fx(view::sceneSnapshot));
        assertEquals(completed, fx(() -> surface().completedRender()));
    }

    private FractalSurface surface() {
        return (FractalSurface) view.getChildrenUnmodifiable().getFirst();
    }

    @Test
    void availabilityKeepsSceneSpecificRestrictionsAfterRendering() throws Exception {
        fx(() -> {
            assertFalse(command("File", SettingsWindow.MENU_TITLE).isDisable());
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

    private CompletableFuture<Void> openEditor(String title) throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        fx(() -> {
            MenuItem item = renderMenu().getItems().stream()
                    .filter(candidate -> title.equals(candidate.getText()))
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

    private CompletableFuture<Void> fireLater(MenuItem item) {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { item.fire(); finished.complete(null); }
            catch (Throwable failure) { finished.completeExceptionally(failure); }
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

    private void dismissMessage(String title) throws Exception {
        if (MacParameterSheet.isAvailable()) {
            await(() -> MacParameterSheet.hasOpenSheet(stage));
            fx(() -> {
                var sheet = AppKitParameterProbe.activeSheet(stage);
                assertInstanceOf(MacNoticeSheet.class, sheet);
                var alert = AppKitParameterProbe.field(sheet, "alert");
                var message = (java.lang.foreign.MemorySegment) AppKitMenuProbe.message(alert, "messageText",
                        java.lang.foreign.ValueLayout.ADDRESS);
                var bytes = (java.lang.foreign.MemorySegment) AppKitMenuProbe.message(message, "UTF8String",
                        java.lang.foreign.ValueLayout.ADDRESS);
                assertEquals(title, bytes.reinterpret(Long.MAX_VALUE).getString(0));
                AppKitParameterProbe.clickAlert(sheet, false);
                return null;
            });
        } else {
            var pane = awaitDialog(title);
            fx(() -> { ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null; });
        }
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
