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
import com.shangin.fractal.scene.WidePreviewSettings;
import com.shangin.fractal.formula.JuliaFormula;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.math.Viewport;
import javafx.geometry.Point2D;
import java.math.BigDecimal;
import javafx.animation.AnimationTimer;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
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
import java.util.Map;
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
        await(() -> view.hasCompletedFrame() && !command("Color", "Edit Palette…").isDisable());
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
    void renderMenuLeavesIterationAndAntialiasingSettingsToSettings() throws Exception {
        List<String> titles = fx(() -> menuBar.getMenus().stream()
                .filter(menu -> "Render".equals(menu.getText()))
                .findFirst().orElseThrow().getItems().stream()
                .map(item -> item instanceof javafx.scene.control.SeparatorMenuItem ? "-" : item.getText())
                .toList());

        assertEquals(List.of("Edit Julia Parameters…"), titles);
    }

    @Test
    void settingsUseTheApplicationMenuOnMacOrTheFileMenuWithStandardShortcut() throws Exception {
        List<String> titles = fx(() -> menuBar.getMenus().stream()
                .filter(menu -> "File".equals(menu.getText()))
                .findFirst().orElseThrow().getItems().stream()
                .map(item -> item instanceof javafx.scene.control.SeparatorMenuItem ? "-" : item.getText())
                .toList());

        if (MacContextMenu.isAvailable()) {
            // MacSystemMenuFxTest checks the native application menu item and its routing.
            assertEquals(List.of("Export PNG…"), titles);
            return;
        }
        // Without the bridge macOS keeps Settings… in File; Quit stays in the system application menu.
        assertEquals(System.getProperty("os.name", "").startsWith("Mac")
                ? List.of("Export PNG…", "-", "Settings…")
                : List.of("Export PNG…", "-", "Settings…", "-", "Quit"), titles);
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
            var scroll = (ScrollPane) dialog.getDialogPane().getContent();
            assertEquals(FractalDialogs.HELP_TEXT, ((Label) scroll.getContent()).getText());
            assertTrue(FractalDialogs.HELP_TEXT.contains("\n\nRender status\n\n"));
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
        String windowTitle = fx(stage::getTitle);
        fx(() -> { command("Fractal", "Julia").fire(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());
        assertEquals(windowTitle, fx(stage::getTitle));
        FractalScene julia = fx(view::sceneSnapshot);
        fx(() -> { command("Edit", "Undo").fire(); return null; });
        await(() -> !command("Color", "Edit Palette…").isDisable());
        assertEquals(windowTitle, fx(stage::getTitle));
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
        await(() -> !command("Color", "Edit Palette…").isDisable());
        assertEquals(initial, fx(view::sceneSnapshot));
        assertArrayEquals(pixels, fx(this::displayedPixels));
    }

    @Test
    void openJuliaAtSelectedPointMapsExactSampleAndIsOneUndoStep() throws Exception {
        // Navigate first so the mapping is checked away from the home view.
        fx(() -> { view.zoom(true); view.zoom(true); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        FractalScene source = fx(view::sceneSnapshot);
        int[] sourcePixels = fx(this::displayedPixels);
        FractalSurface surface = fx(this::surface);
        int renderWidth = fx(surface::renderWidth);
        int renderHeight = fx(surface::renderHeight);
        double width = fx(surface::getWidth);
        double height = fx(surface::getHeight);
        Viewport viewport = source.viewport();

        for (int[] sample : new int[][]{{0, 0}, {renderWidth - 1, renderHeight - 1},
                {renderWidth / 2 - 1, renderHeight / 2}, {renderWidth / 3, 1}}) {
            // The logical center of displayed render pixel (i, j).
            double x = (sample[0] + 0.5) * width / renderWidth;
            double y = (sample[1] + 0.5) * height / renderHeight;
            PreciseComplex selected = fx(() -> view.coordinateAt(x, y));
            assertEquals(0, viewport.realAtExact(BigDecimal.valueOf(sample[0]), renderWidth, renderHeight)
                    .compareTo(selected.real()), "Real coordinate of sample " + sample[0]);
            assertEquals(0, viewport.imaginaryAtExact(BigDecimal.valueOf(sample[1]), renderHeight)
                    .compareTo(selected.imaginary()), "Imaginary coordinate of sample " + sample[1]);
        }

        double x = 0.3 * width;
        double y = 0.7 * height;
        PreciseComplex selected = fx(() -> view.coordinateAt(x, y));
        List<FractalContextMenu.Command> commands = fx(() -> view.contextCommands(new Point2D(x, y)));
        assertEquals(List.of(FractalContextMenu.COPY_COORDINATES_AND_ZOOM, FractalView.OPEN_JULIA_SET_HERE,
                        FractalView.OPEN_JULIA_SET_AT_SAME_ZOOM),
                commands.stream().map(FractalContextMenu.Command::title).toList());
        fx(() -> { commands.get(1).action().run(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());

        FractalScene julia = fx(view::sceneSnapshot);
        Viewport juliaHome = new FractalCamera(FractalPreset.JULIA).defaultViewport((int) width, (int) height);
        assertEquals(source.withFractal(FractalPreset.JULIA, juliaHome)
                .withJuliaParameters(JuliaParameters.nearest(selected)), julia);
        assertEquals(new JuliaParameters(selected.real().doubleValue(), selected.imaginary().doubleValue()),
                julia.juliaParameters());
        fx(() -> {
            assertEquals(julia, surface.completedRender().scene(), "Displayed pixels must belong to the Julia scene");
            assertTrue(((RadioMenuItem) command("Fractal", "Julia")).isSelected());
            assertEquals(List.of(FractalContextMenu.COPY_COORDINATES_AND_ZOOM),
                    view.contextCommands(new Point2D(x, y)).stream().map(FractalContextMenu.Command::title).toList(),
                    "Only Mandelbrot offers the Julia transition");
            return null;
        });

        fx(() -> { command("Edit", "Undo").fire(); return null; });
        await(() -> !command("Color", "Edit Palette…").isDisable() && view.isLatencyIdle());
        fx(() -> {
            assertEquals(source, view.sceneSnapshot());
            assertEquals(source, surface.completedRender().scene());
            assertArrayEquals(sourcePixels, displayedPixels());
            assertTrue(((RadioMenuItem) command("Fractal", "Mandelbrot")).isSelected());
            return null;
        });
        fx(() -> { command("Edit", "Redo").fire(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable());
        assertEquals(julia, fx(view::sceneSnapshot));
    }

    @Test
    void openJuliaAtSameZoomCentersTheJuliaConstantAtTheSourceScale() throws Exception {
        fx(() -> { view.zoom(true); view.zoom(true); view.zoom(true); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        FractalScene source = fx(view::sceneSnapshot);
        int[] sourcePixels = fx(this::displayedPixels);
        double x = fx(() -> surface().getWidth() * 0.62);
        double y = fx(() -> surface().getHeight() * 0.41);
        PreciseComplex selected = fx(() -> view.coordinateAt(x, y));
        List<FractalContextMenu.Command> commands = fx(() -> view.contextCommands(new Point2D(x, y)));
        assertEquals(FractalView.OPEN_JULIA_SET_AT_SAME_ZOOM, commands.get(2).title());
        fx(() -> { commands.get(2).action().run(); return null; });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable() && view.isLatencyIdle());

        FractalScene julia = fx(view::sceneSnapshot);
        JuliaParameters constant = JuliaParameters.nearest(selected);
        assertEquals(source.withFractal(FractalPreset.JULIA, new Viewport(new PreciseComplex(
                        new BigDecimal(constant.real()), new BigDecimal(constant.imaginary())),
                        source.viewport().scaleExact()))
                .withJuliaParameters(constant), julia);
        // The view is centered exactly on z = c for the binary64 constant.
        assertEquals(0, new BigDecimal(constant.real()).compareTo(julia.viewport().center().real()));
        assertEquals(0, new BigDecimal(constant.imaginary()).compareTo(julia.viewport().center().imaginary()));
        assertEquals(0, source.viewport().scaleExact().compareTo(julia.viewport().scaleExact()));
        assertEquals(julia, fx(() -> surface().completedRender().scene()));

        fx(() -> { command("Edit", "Undo").fire(); return null; });
        await(() -> !command("Color", "Edit Palette…").isDisable() && view.isLatencyIdle());
        fx(() -> {
            assertEquals(source, view.sceneSnapshot());
            assertEquals(source, surface().completedRender().scene());
            assertArrayEquals(sourcePixels, displayedPixels());
            return null;
        });

        // A same-zoom command taken before navigation is ignored as well.
        List<FractalContextMenu.Command> stale = fx(() -> view.contextCommands(new Point2D(x, y)));
        fx(() -> { view.zoom(true); stale.get(2).action().run(); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        assertEquals(FractalPreset.MANDELBROT, fx(view::sceneSnapshot).fractal());
    }

    @Test
    void openJuliaAtSameZoomStaysOnTheJuliaConstantBelowItsPrecision() throws Exception {
        // At a scale far below ulp(c) the exact selection is thousands of screens away
        // from the rounded z = c; the view must still be centered on z = c.
        Viewport deep = new Viewport("-0.743643887037158704752191506114774",
                "0.131825904205311970493132056385139", "1e-20");
        fx(() -> { view.goTo(deep); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        double x = fx(() -> surface().getWidth() * 0.25);
        double y = fx(() -> surface().getHeight() * 0.75);
        PreciseComplex selected = fx(() -> view.coordinateAt(x, y));
        JuliaParameters constant = JuliaParameters.nearest(selected);
        BigDecimal offset = selected.real().subtract(new BigDecimal(constant.real())).abs();
        assertTrue(offset.compareTo(new BigDecimal("1e-18")) > 0,
                "The fixture must select a point many screens from the rounded constant");

        fx(() -> { view.contextCommands(new Point2D(x, y)).get(2).action().run(); return null; });
        await(() -> view.isLatencyIdle() && !command("Render", "Edit Julia Parameters…").isDisable());
        FractalScene julia = fx(view::sceneSnapshot);
        assertEquals(constant, julia.juliaParameters());
        assertEquals(new Viewport(new PreciseComplex(new BigDecimal(constant.real()),
                new BigDecimal(constant.imaginary())), deep.scaleExact()), julia.viewport());
        assertEquals(julia, fx(() -> surface().completedRender().scene()));
    }

    @Test
    void openJuliaCoversTheSurfaceUntilTheJuliaFrameIsComplete() throws Exception {
        for (int command : new int[]{1, 2}) {
            double x = fx(() -> surface().getWidth() * 0.3);
            double y = fx(() -> surface().getHeight() * 0.6);
            fx(() -> {
                view.contextCommands(new Point2D(x, y)).get(command).action().run();
                assertTrue(view.getChildrenUnmodifiable().stream().anyMatch(LoadingScreen.class::isInstance),
                        "Opening Julia must show the loading screen");
                assertFalse(surface().isVisible(), "Mandelbrot or partial Julia pixels must stay hidden");
                return null;
            });
            await(() -> view.isLatencyIdle() && !command("Render", "Edit Julia Parameters…").isDisable()
                    && view.getChildrenUnmodifiable().stream().noneMatch(LoadingScreen.class::isInstance));
            fx(() -> {
                assertTrue(surface().isVisible());
                assertEquals(view.sceneSnapshot(), surface().completedRender().scene());
                command("Edit", "Undo").fire();
                return null;
            });
            await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable()
                    && view.sceneSnapshot().fractal() == FractalPreset.MANDELBROT);
        }
    }

    @Test
    void switchingFractalsReturnsToEachFractalsLastView() throws Exception {
        fx(() -> { view.zoom(true); view.zoom(true); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        FractalScene mandelbrot = fx(view::sceneSnapshot);
        int[] mandelbrotPixels = fx(this::displayedPixels);

        // A fractal that has not been visited yet starts at its home view.
        fx(() -> { command("Fractal", "Burning Ship").fire(); return null; });
        await(() -> view.isLatencyIdle() && view.sceneSnapshot().fractal() == FractalPreset.BURNING_SHIP
                && !command("Color", "Edit Palette…").isDisable());
        double width = fx(() -> view.getWidth());
        double height = fx(() -> view.getHeight());
        assertEquals(new FractalCamera(FractalPreset.BURNING_SHIP).defaultViewport((int) width, (int) height),
                fx(view::sceneSnapshot).viewport());
        fx(() -> { view.zoom(true); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        FractalScene burningShip = fx(view::sceneSnapshot);

        fx(() -> { command("Fractal", "Mandelbrot").fire(); return null; });
        await(() -> view.isLatencyIdle() && view.sceneSnapshot().fractal() == FractalPreset.MANDELBROT
                && !command("Color", "Edit Palette…").isDisable());
        fx(() -> {
            assertEquals(mandelbrot, view.sceneSnapshot(), "Mandelbrot returns to its exact last view");
            assertEquals(mandelbrot, surface().completedRender().scene());
            assertArrayEquals(mandelbrotPixels, displayedPixels());
            return null;
        });

        fx(() -> { command("Fractal", "Burning Ship").fire(); return null; });
        await(() -> view.isLatencyIdle() && view.sceneSnapshot().fractal() == FractalPreset.BURNING_SHIP
                && !command("Color", "Edit Palette…").isDisable());
        assertEquals(burningShip, fx(view::sceneSnapshot));
        assertEquals(burningShip, fx(() -> surface().completedRender().scene()));

        // Selecting the active fractal again still resets it to its home view.
        fx(() -> { command("Fractal", "Burning Ship").fire(); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        assertEquals(new FractalCamera(FractalPreset.BURNING_SHIP).defaultViewport((int) width, (int) height),
                fx(view::sceneSnapshot).viewport());

        // Undo leaves the reset view; leaving Burning Ship by Undo remembers its zoomed view too.
        fx(() -> { command("Edit", "Undo").fire(); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        assertEquals(burningShip, fx(view::sceneSnapshot));
        fx(() -> { command("Edit", "Undo").fire(); return null; });
        await(() -> view.isLatencyIdle() && view.sceneSnapshot().fractal() == FractalPreset.MANDELBROT
                && !command("Color", "Edit Palette…").isDisable());
        fx(() -> { command("Fractal", "Burning Ship").fire(); return null; });
        await(() -> view.isLatencyIdle() && view.sceneSnapshot().fractal() == FractalPreset.BURNING_SHIP
                && !command("Color", "Edit Palette…").isDisable());
        assertEquals(burningShip.viewport(), fx(view::sceneSnapshot).viewport());

        Map<FractalPreset, Viewport> views = fx(view::fractalViews);
        assertEquals(mandelbrot.viewport(), views.get(FractalPreset.MANDELBROT));
        assertEquals(burningShip.viewport(), views.get(FractalPreset.BURNING_SHIP));
    }

    @Test
    void openJuliaSetHereKeepsTheInitialViewWhenJuliaHasARememberedView() throws Exception {
        fx(() -> { command("Fractal", "Julia").fire(); return null; });
        await(() -> view.isLatencyIdle() && !command("Render", "Edit Julia Parameters…").isDisable());
        fx(() -> { view.zoom(true); return null; });
        await(() -> view.isLatencyIdle() && !command("Render", "Edit Julia Parameters…").isDisable());
        Viewport rememberedJulia = fx(view::sceneSnapshot).viewport();
        fx(() -> { command("Fractal", "Mandelbrot").fire(); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable()
                && view.sceneSnapshot().fractal() == FractalPreset.MANDELBROT);

        double x = fx(() -> surface().getWidth() * 0.4);
        double y = fx(() -> surface().getHeight() * 0.6);
        fx(() -> { view.contextCommands(new Point2D(x, y)).get(1).action().run(); return null; });
        await(() -> view.isLatencyIdle() && !command("Render", "Edit Julia Parameters…").isDisable());
        double width = fx(() -> view.getWidth());
        double height = fx(() -> view.getHeight());
        Viewport julia = fx(view::sceneSnapshot).viewport();
        assertEquals(new FractalCamera(FractalPreset.JULIA).defaultViewport((int) width, (int) height), julia);
        assertNotEquals(rememberedJulia, julia);
    }

    @Test
    void savedFractalViewsAreUsedForInactiveFractalsOnly() throws Exception {
        Viewport savedJulia = new Viewport("0.125", "-0.25", "0.5");
        Viewport savedMandelbrot = new Viewport("-1.25", "0", "0.5");
        FractalScene active = fx(view::sceneSnapshot);
        fx(() -> {
            view.restoreFractalViews(Map.of(FractalPreset.JULIA, savedJulia, FractalPreset.MANDELBROT, savedMandelbrot));
            assertEquals(active, view.sceneSnapshot(), "The restored active scene keeps its own view");
            command("Fractal", "Julia").fire();
            return null;
        });
        await(() -> view.isLatencyIdle() && !command("Render", "Edit Julia Parameters…").isDisable());
        assertEquals(savedJulia, fx(view::sceneSnapshot).viewport());
        assertEquals(fx(view::sceneSnapshot), fx(() -> surface().completedRender().scene()));
        fx(() -> { command("Fractal", "Mandelbrot").fire(); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable()
                && view.sceneSnapshot().fractal() == FractalPreset.MANDELBROT);
        assertEquals(active.viewport(), fx(view::sceneSnapshot).viewport());
    }

    @Test
    void openJuliaCancelsRunningRenderAndIgnoresStaleSelections() throws Exception {
        double x = fx(() -> surface().getWidth() * 0.25);
        double y = fx(() -> surface().getHeight() * 0.5);
        List<FractalContextMenu.Command> stale = fx(() -> view.contextCommands(new Point2D(x, y)));
        // A command taken before navigation must not act on the newer view.
        fx(() -> { view.zoom(true); stale.get(1).action().run(); return null; });
        await(() -> view.isLatencyIdle() && !command("Color", "Edit Palette…").isDisable());
        FractalScene zoomed = fx(view::sceneSnapshot);
        assertEquals(FractalPreset.MANDELBROT, zoomed.fractal());
        assertEquals(zoomed, fx(() -> surface().completedRender().scene()));

        // Selecting while a Mandelbrot render is still running replaces it without stale pixels.
        PreciseComplex selected = fx(() -> {
            view.zoom(true);
            assertFalse(view.isLatencyIdle(), "The Mandelbrot render must still be in progress");
            PreciseComplex point = view.coordinateAt(x, y);
            view.contextCommands(new Point2D(x, y)).get(1).action().run();
            return point;
        });
        await(() -> !command("Render", "Edit Julia Parameters…").isDisable() && view.isLatencyIdle());
        FractalScene julia = fx(view::sceneSnapshot);
        assertEquals(FractalPreset.JULIA, julia.fractal());
        assertEquals(JuliaParameters.nearest(selected), julia.juliaParameters());
        assertEquals(julia, fx(() -> surface().completedRender().scene()));
        fx(() -> { command("Edit", "Undo").fire(); return null; });
        await(() -> !command("Color", "Edit Palette…").isDisable() && view.isLatencyIdle());
        FractalScene restored = fx(view::sceneSnapshot);
        assertEquals(FractalPreset.MANDELBROT, restored.fractal());
        assertNotEquals(zoomed.viewport(), restored.viewport(), "Undo returns to the view the point was selected in");
        assertEquals(restored, fx(() -> surface().completedRender().scene()));
    }

    @Test
    void clipboardPresetAndBookmarkRestoreCompleteScene() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        fx(() -> { command("View", "Copy Scene Preset").fire(); view.setFractal(FractalPreset.JULIA); return null; });
        fx(() -> { command("View", "Apply Scene Preset from Clipboard").fire(); return null; });
        await(() -> !command("Color", "Edit Palette…").isDisable());
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
        await(() -> !command("Color", "Edit Palette…").isDisable());
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
            settingsCommand().fire();
            assertNull(AppKitParameterProbe.settingsSheet(menuBar));
            AppKitParameterProbe.action(sheet, "cancel");
            return null;
        });
        fx(() -> {
            assertFalse(AppKitParameterProbe.bookmarkSheet(menuBar).isOpen());
            settingsCommand().fire();
            var settings = AppKitParameterProbe.settingsSheet(menuBar);
            assertNotNull(settings);
            command("Bookmarks", "Add Bookmark…").fire();
            assertFalse(AppKitParameterProbe.bookmarkSheet(menuBar).isOpen());
            assertTrue(settings.isOpen());
            AppKitParameterProbe.action(settings, "cancel");
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
        await(() -> !command("Color", "Edit Palette…").isDisable());
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
        await(() -> !command("Color", "Edit Palette…").isDisable());
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
        await(() -> !command("Color", "Edit Palette…").isDisable());
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
        CompletableFuture<Void> action = fireLater(fx(this::settingsCommand));
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
                && !command("Color", "Edit Palette…").isDisable());
        fx(() -> {
            assertEquals(selected, view.sceneSnapshot().antialiasing());
            RenderModeIndicator badge = (RenderModeIndicator) view.getChildrenUnmodifiable().get(1);
            assertTrue(badge.getText().contains("Complete"), badge.getText());
            view.undoScene();
            assertEquals(initial.antialiasing(), view.sceneSnapshot().antialiasing());
            return null;
        });
        await(() -> surface().completedRender() != null
                && surface().completedRender().scene().antialiasing().equals(initial.antialiasing())
                && !command("Color", "Edit Palette…").isDisable());
    }

    @Test
    void settingsSwitchWidePreviewOutsideTheSceneAndReportExplicitChanges() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        var reported = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
        fx(() -> {
            menuBar.setOnWidePreviewChanged(settings -> reported.add(settings.enabled()));
            assertEquals(WidePreviewSettings.DEFAULT.enabled(), view.widePreviewEnabled(),
                    "The preview follows the application default");
            return null;
        });
        boolean initialPreview = WidePreviewSettings.DEFAULT.enabled();
        for (boolean enabled : List.of(!initialPreview, initialPreview)) {
            CompletableFuture<Void> action = fireLater(fx(this::settingsCommand));
            int choice = enabled ? 1 : 0;
            if (MacSettingsSheet.isAvailable()) {
                action.get(10, TimeUnit.SECONDS);
                fx(() -> {
                    var sheet = AppKitParameterProbe.settingsSheet(menuBar);
                    var wide = AppKitParameterProbe.choice(sheet, 2);
                    assertEquals(1 - choice, AppKitParameterProbe.selectedIndex(wide));
                    AppKitParameterProbe.select(wide, choice);
                    AppKitParameterProbe.select(AppKitParameterProbe.choice(sheet, 3), enabled == initialPreview ? 1 : 2);
                    AppKitParameterProbe.select(AppKitParameterProbe.choice(sheet, 4), enabled == initialPreview ? 1 : 2);
                    AppKitParameterProbe.action(sheet, "apply");
                    return null;
                });
            } else {
                DialogPane pane = awaitDialog(SettingsWindow.TITLE);
                fx(() -> {
                    @SuppressWarnings("unchecked")
                    var wide = (javafx.scene.control.ComboBox<String>) pane.lookup("#" + SettingsWindow.WIDE_PREVIEW_ID);
                    assertEquals(1 - choice, wide.getSelectionModel().getSelectedIndex());
                    wide.getSelectionModel().select(choice);
                    ((javafx.scene.control.ComboBox<?>) pane.lookup("#" + SettingsWindow.WIDE_PREVIEW_EXTENT_ID))
                            .getSelectionModel().select(enabled == initialPreview ? 1 : 2);
                    ((javafx.scene.control.ComboBox<?>) pane.lookup("#" + SettingsWindow.WIDE_PREVIEW_QUALITY_ID))
                            .getSelectionModel().select(enabled == initialPreview ? 1 : 2);
                    confirm(pane);
                    return null;
                });
                action.get(10, TimeUnit.SECONDS);
            }
            await(() -> view.widePreviewEnabled() == enabled);
            fx(() -> {
                assertEquals(enabled == initialPreview ? 2.0 : 3.0, view.widePreviewSettings().extent());
                assertEquals(enabled == initialPreview ? 8 : 4, view.widePreviewSettings().pixelStep());
                assertEquals(initial, view.sceneSnapshot(), "The preview is not part of the scene");
                assertTrue(view.hasCompletedFrame(), "Switching the preview keeps the exact frame");
                assertFalse(view.canUndo(), "Switching the preview records no scene history");
                return null;
            });
        }
        assertEquals(List.of(!initialPreview, initialPreview), reported);
        // Applying unchanged Settings is not an explicit preview choice.
        CompletableFuture<Void> action = fireLater(fx(this::settingsCommand));
        if (MacSettingsSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> { AppKitParameterProbe.action(AppKitParameterProbe.settingsSheet(menuBar), "apply"); return null; });
        } else {
            DialogPane pane = awaitDialog(SettingsWindow.TITLE);
            fx(() -> { confirm(pane); return null; });
            action.get(10, TimeUnit.SECONDS);
        }
        fx(() -> null);
        assertEquals(List.of(!initialPreview, initialPreview), reported);
    }

    @Test
    void settingsApplyIterationsAndAntialiasingAsOneUndoableSceneChange() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        var iterations = new IterationSettings(420, 70);
        var antialiasing = new AntialiasSettings(InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.BASE_ONLY);
        CompletableFuture<Void> action = fireLater(fx(this::settingsCommand));
        if (MacSettingsSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.settingsSheet(menuBar);
                assertNotNull(sheet);
                var base = AppKitParameterProbe.textField(sheet, 0);
                var zoom = AppKitParameterProbe.textField(sheet, 1);
                assertEquals(Integer.toString(initial.iterations().baseIterations()), AppKitParameterProbe.stringValue(base));
                assertEquals(Integer.toString(initial.iterations().iterationsPerZoomLevel()), AppKitParameterProbe.stringValue(zoom));
                AppKitParameterProbe.setText(base, "420");
                AppKitParameterProbe.setText(zoom, "70");
                AppKitParameterProbe.select(AppKitParameterProbe.choice(sheet, 0), SettingsWindow.index(InteractiveRenderMode.BASE_ONLY));
                assertEquals(initial, view.sceneSnapshot());
                AppKitParameterProbe.action(sheet, "apply");
                return null;
            });
        } else {
            DialogPane pane = awaitDialog(SettingsWindow.TITLE);
            fx(() -> {
                var base = input(pane, SettingsWindow.BASE_ITERATIONS_ID);
                var zoom = input(pane, SettingsWindow.ZOOM_ITERATIONS_ID);
                assertEquals(Integer.toString(initial.iterations().baseIterations()), base.getText());
                assertEquals(Integer.toString(initial.iterations().iterationsPerZoomLevel()), zoom.getText());
                pane.getScene().getPreferences().setColorScheme(ColorScheme.DARK);
                pane.applyCss();
                assertEquals(Color.web("#292929"), pane.getBackground().getFills().getFirst().getFill());
                base.setText("420");
                zoom.setText("70");
                @SuppressWarnings("unchecked")
                var standard = (javafx.scene.control.ComboBox<String>) pane.lookup("#" + SettingsWindow.STANDARD_ID);
                standard.getSelectionModel().select(SettingsWindow.index(InteractiveRenderMode.BASE_ONLY));
                assertEquals(initial, view.sceneSnapshot());
                confirm(pane);
                return null;
            });
            action.get(10, TimeUnit.SECONDS);
        }
        await(() -> view.sceneSnapshot().iterations().equals(iterations));
        assertEquals(antialiasing, fx(() -> view.sceneSnapshot().antialiasing()));
        assertCompletedIterations(iterations);
        fx(() -> {
            view.undoScene();
            assertEquals(initial.iterations(), view.sceneSnapshot().iterations(), "One undo restores the iterations");
            assertEquals(initial.antialiasing(), view.sceneSnapshot().antialiasing(), "and the antialiasing together");
            return null;
        });
        assertCompletedIterations(initial.iterations());
    }

    @Test
    void cancellingSettingsKeepsIterationsUnchangedAfterReset() throws Exception {
        FractalScene initial = fx(() -> {
            view.setIterations(new IterationSettings(420, 70));
            return view.sceneSnapshot();
        });
        await(() -> !command("Color", "Edit Palette…").isDisable());
        CompletableFuture<Void> action = fireLater(fx(this::settingsCommand));
        if (MacSettingsSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.settingsSheet(menuBar);
                AppKitParameterProbe.action(sheet, "resetChoices");
                assertEquals("300", AppKitParameterProbe.stringValue(AppKitParameterProbe.textField(sheet, 0)));
                assertEquals("50", AppKitParameterProbe.stringValue(AppKitParameterProbe.textField(sheet, 1)));
                AppKitParameterProbe.action(sheet, "cancel");
                return null;
            });
            fx(() -> null);
            fx(() -> { assertEquals(initial, view.sceneSnapshot()); return null; });
            return;
        }
        DialogPane pane = awaitDialog(SettingsWindow.TITLE);
        fx(() -> {
            Button reset = pane.getButtonTypes().stream()
                    .filter(type -> SettingsWindow.RESET_TITLE.equals(type.getText()))
                    .map(type -> (Button) pane.lookupButton(type)).findFirst().orElseThrow();
            reset.fire();
            assertEquals("300", input(pane, SettingsWindow.BASE_ITERATIONS_ID).getText());
            ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            return null;
        });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(initial, fx(view::sceneSnapshot));
    }

    @Test
    void invalidIterationInputInSettingsShowsErrorAndKeepsSceneUnchanged() throws Exception {
        FractalScene initial = fx(view::sceneSnapshot);
        CompletableFuture<Void> action = fireLater(fx(this::settingsCommand));
        if (MacSettingsSheet.isAvailable()) {
            action.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var sheet = AppKitParameterProbe.settingsSheet(menuBar);
                var base = AppKitParameterProbe.textField(sheet, 0);
                var zoom = AppKitParameterProbe.textField(sheet, 1);
                for (String invalid : List.of("not a number", "0", "2147483648", "999999999999999999999")) {
                    AppKitParameterProbe.setText(base, invalid);
                    AppKitParameterProbe.action(sheet, "apply");
                    assertTrue(AppKitParameterProbe.showing(sheet));
                    assertEquals(invalid, AppKitParameterProbe.stringValue(base));
                    assertEquals(SettingsWindow.ITERATION_ERROR,
                            AppKitParameterProbe.stringValue(AppKitParameterProbe.field(sheet, "error")));
                    assertEquals(initial, view.sceneSnapshot());
                }
                AppKitParameterProbe.setText(base, "420");
                AppKitParameterProbe.setText(zoom, "-1");
                AppKitParameterProbe.action(sheet, "apply");
                assertTrue(AppKitParameterProbe.showing(sheet));
                assertEquals(initial, view.sceneSnapshot());
                AppKitParameterProbe.setText(zoom, "70");
                AppKitParameterProbe.action(sheet, "apply");
                return null;
            });
            await(() -> view.sceneSnapshot().iterations().equals(new IterationSettings(420, 70)));
            return;
        }
        DialogPane pane = awaitDialog(SettingsWindow.TITLE);
        fx(() -> {
            input(pane, SettingsWindow.BASE_ITERATIONS_ID).setText("not a number");
            input(pane, SettingsWindow.ZOOM_ITERATIONS_ID).setText("70");
            confirm(pane);
            assertTrue(pane.lookup("#dialog-validation-error").isVisible());
            assertTrue(pane.getScene().getWindow().isShowing());
            assertEquals(initial, view.sceneSnapshot());
            input(pane, SettingsWindow.BASE_ITERATIONS_ID).setText("420");
            input(pane, SettingsWindow.ZOOM_ITERATIONS_ID).setText("-1");
            confirm(pane);
            assertTrue(pane.lookup("#dialog-validation-error").isVisible());
            assertEquals(initial, view.sceneSnapshot());
            input(pane, SettingsWindow.ZOOM_ITERATIONS_ID).setText("70");
            confirm(pane);
            return null;
        });
        action.get(10, TimeUnit.SECONDS);

        assertEquals(new IterationSettings(420, 70), fx(() -> view.sceneSnapshot().iterations()));
    }

    private void assertCompletedIterations(IterationSettings expected) throws Exception {
        await(() -> surface().completedRender() != null
                && surface().completedRender().scene().iterations().equals(expected)
                && !command("Color", "Edit Palette…").isDisable());
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
                settingsCommand().fire();
                assertNull(AppKitParameterProbe.settingsSheet(menuBar));
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
            assertFalse(settingsCommand().isDisable());
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

    /**
     * The window's Settings… command: the File item, or on macOS the action that the
     * application menu item routes to (see MacSystemMenuFxTest for the native item).
     */
    private MenuItem settingsCommand() {
        return command("File").stream().filter(item -> SettingsWindow.MENU_TITLE.equals(item.getText()))
                .findFirst().orElseGet(() -> {
                    MenuItem routed = new MenuItem(SettingsWindow.MENU_TITLE);
                    routed.setOnAction(event -> {
                        try {
                            var show = MainMenuBar.class.getDeclaredMethod("showSettings");
                            show.setAccessible(true);
                            show.invoke(menuBar);
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                    });
                    return routed;
                });
    }

    private List<MenuItem> command(String menu) {
        return menuBar.getMenus().stream().filter(item -> menu.equals(item.getText()))
                .findFirst().orElseThrow().getItems();
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
