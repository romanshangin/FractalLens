package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.JuliaParameters;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalDialogsFxTest {
    private Stage owner;
    private TextField ownerField;

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
    @BeforeEach void owner() throws Exception {
        fx(() -> {
            ownerField = new TextField("Owner focus");
            owner = new Stage();
            owner.setScene(new Scene(new StackPane(ownerField), 640, 440));
            owner.show(); owner.toFront(); ownerField.requestFocus(); return null;
        });
    }
    @AfterEach void closeOwner() throws Exception { fx(() -> { owner.close(); return null; }); }

    private static Button primary(Dialog<?> dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .map(type -> (Button) dialog.getDialogPane().lookupButton(type))
                .filter(Button::isDefaultButton).findFirst().orElseThrow();
    }

    @Test void bookmarkFallbackUsesCharacterLimitAndPreservesInvalidDraft() throws Exception {
        var dialog = fx(() -> FractalDialogs.bookmarkNameDialog(owner));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var name = field(dialog, "bookmark-name");
                assertSame(name, dialog.getDialogPane().getScene().getFocusOwner());
                String invalid = "🌌".repeat(81);
                name.setText(invalid);
                primary(dialog).fire();
                assertTrue(dialog.isShowing());
                assertEquals(invalid, name.getText());
                assertEquals(com.shangin.fractal.app.BookmarkStore.NAME_ERROR,
                        ((Label) dialog.getDialogPane().lookup("#dialog-validation-error")).getText());
                name.setText("🌌".repeat(80));
                name.fireEvent(new ActionEvent());
                assertFalse(dialog.isShowing());
                assertEquals("🌌".repeat(80), dialog.getResult());
                return null;
            });
        } finally { fx(() -> { dialog.close(); return null; }); }
    }
    private static TextField field(Dialog<?> dialog, String id) {
        return (TextField) dialog.getDialogPane().lookup("#" + id);
    }
    private static void escape(Dialog<?> dialog) {
        dialog.getDialogPane().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                false, false, false, false));
    }
    private static void snapshot(Dialog<?> dialog, String name) throws Exception {
        var pane = dialog.getDialogPane();
        pane.applyCss(); pane.layout();
        var image = pane.snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        var output = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        Path directory = Path.of("target/dialog-qa");
        Files.createDirectories(directory);
        javax.imageio.ImageIO.write(output, "png", directory.resolve(name + ".png").toFile());
    }

    @Test void coordinatesThemeFocusOrderValidationAndExactResult() throws Exception {
        var editor = new RecordingCoordinateEditor(target -> target.zoom() == null
                || target.zoom().compareTo(new BigDecimal("1E12")) <= 0);
        var dialog = fx(() -> FractalDialogs.coordinateDialog(owner, new CoordinateText("-0.75", "0", "1.25"),
                false, editor));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var pane = dialog.getDialogPane();
                var real = field(dialog, "coordinate-real");
                var imaginary = field(dialog, "coordinate-imaginary");
                var zoom = field(dialog, "coordinate-zoom");
                assertEquals("1.25", zoom.getText());
                assertTrue(zoom.isEditable());
                assertSame(real, pane.getScene().getFocusOwner());
                assertEquals(real.getText(), real.getSelectedText());
                pane.getScene().getPreferences().setColorScheme(ColorScheme.LIGHT);
                pane.applyCss(); pane.layout();
                assertEquals(Color.web("#f5f5f5"), pane.getBackground().getFills().getFirst().getFill());
                assertEquals(Color.WHITE, real.getBackground().getFills().getFirst().getFill());
                Button go = primary(dialog);
                Color actualAccent = (Color) go.getBackground().getFills().getFirst().getFill();
                Color systemAccent = Platform.getPreferences().getAccentColor();
                assertEquals(systemAccent.getRed(), actualAccent.getRed(), 1.0 / 255);
                assertEquals(systemAccent.getGreen(), actualAccent.getGreen(), 1.0 / 255);
                assertEquals(systemAccent.getBlue(), actualAccent.getBlue(), 1.0 / 255);
                var cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
                assertTrue(go.localToScene(go.getBoundsInLocal()).getMinX()
                        > cancel.localToScene(cancel.getBoundsInLocal()).getMinX());
                assertTrue(cancel.isCancelButton());
                snapshot(dialog, "coordinates-light");

                pane.getScene().getPreferences().setColorScheme(ColorScheme.DARK);
                pane.applyCss();
                assertEquals(Color.web("#292929"), pane.getBackground().getFills().getFirst().getFill());
                real.setText("bad value");
                real.fireEvent(new ActionEvent());
                assertTrue(dialog.isShowing());
                assertTrue(pane.lookup("#dialog-validation-error").isVisible());
                assertSame(real, pane.getScene().getFocusOwner());
                assertEquals("bad value", real.getText());
                snapshot(dialog, "coordinates-dark-validation");
                var error = pane.lookup("#dialog-validation-error");
                assertTrue(go.localToScene(go.getBoundsInLocal()).getMinY()
                        - error.localToScene(error.getBoundsInLocal()).getMaxY() >= 16,
                        "Wrapped validation text must leave space above the actions");

                real.setText("-0.743643887037151000000000000000000001");
                imaginary.setText("1.25e-30");
                assertFalse(pane.lookup("#dialog-validation-error").isVisible());
                for (String invalid : List.of("0.5", "zoom", "1e13")) {
                    zoom.setText(invalid);
                    zoom.fireEvent(new ActionEvent());
                    assertTrue(dialog.isShowing(), invalid);
                    assertSame(zoom, pane.getScene().getFocusOwner());
                    assertEquals(invalid, zoom.getText());
                    assertTrue(((Label) pane.lookup("#dialog-validation-error")).getText()
                            .contains(invalid.equals("1e13") ? "precisely" : "at least 1"), invalid);
                }
                zoom.setText("1e12");
                assertFalse(pane.lookup("#dialog-validation-error").isVisible());
                assertTrue(editor.applied.isEmpty(), "Failed or live validation must not apply");
                imaginary.fireEvent(new ActionEvent());
                assertFalse(dialog.isShowing());
                assertEquals(List.of(new CoordinateTarget(new BigDecimal(real.getText()),
                        new BigDecimal(imaginary.getText()), new BigDecimal("1e12"))), editor.applied);
                return null;
            });
            fx(() -> { assertSame(ownerField, owner.getScene().getFocusOwner()); return null; });
        } finally { fx(() -> { dialog.close(); return null; }); }
    }

    @Test void paletteCommitsDraftAndRejectsInvalidTextWithoutLosingIt() throws Exception {
        var dialog = fx(() -> FractalDialogs.paletteDialog(owner, List.of(
                new ColorStop(0, 0xff000000), new ColorStop(1, 0xffffffff))));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var editor = (PaletteStopEditor) ((ScrollPane) dialog.getDialogPane().lookup(".scroll-pane")).getContent();
                TextField position = editor.initialFocus();
                var focusedSpinner = assertInstanceOf(Spinner.class, dialog.getDialogPane().getScene().getFocusOwner());
                assertSame(position, focusedSpinner.getEditor());
                assertEquals(position.getText(), position.getSelectedText());
                dialog.getDialogPane().getScene().getPreferences().setColorScheme(ColorScheme.LIGHT);
                snapshot(dialog, "palette-light");
                for (String invalid : List.of("NaN", "Infinity", "1.1", "-0.1", "bad", "")) {
                    position.setText(invalid);
                    position.fireEvent(new ActionEvent());
                    assertTrue(dialog.isShowing(), invalid);
                    assertEquals(invalid, position.getText());
                    assertTrue(dialog.getDialogPane().lookup("#dialog-validation-error").isVisible());
                }
                // Spinner's focus-loss commit must not revert invalid text to the previous value.
                position.setText("invalid draft");
                primary(dialog).requestFocus();
                assertEquals("invalid draft", position.getText());
                dialog.getDialogPane().getScene().getPreferences().setColorScheme(ColorScheme.DARK);
                snapshot(dialog, "palette-dark-validation");
                position.setText("0.375");
                primary(dialog).fire(); // Apply without pressing Enter or leaving the editor first.
                assertFalse(dialog.isShowing());
                assertEquals(.375, dialog.getResult().getFirst().position(), 1e-12);
                return null;
            });
        } finally { fx(() -> { dialog.close(); return null; }); }
    }

    @Test void palettePreviewRetainsLastValidGradientUntilAllDraftsAreCorrected() throws Exception {
        var dialog = fx(() -> FractalDialogs.paletteDialog(owner, List.of(
                new ColorStop(0, 0xff000000), new ColorStop(1, 0xffffffff))));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var pane = dialog.getDialogPane();
                var editor = (PaletteStopEditor) ((ScrollPane) pane.lookup(".scroll-pane")).getContent();
                var preview = (javafx.scene.image.ImageView) pane.lookup("#palette-preview");
                var picker = (ColorPicker) editor.lookup(".color-picker");
                var position = editor.initialFocus();
                int[] validPixels = palettePreviewPixels(preview);
                for (String invalid : List.of("invalid", "NaN", "Infinity", "-0.1", "1.1", "")) {
                    position.setText(invalid);
                    primary(dialog).fire();
                    picker.setValue(Color.RED);
                    picker.fireEvent(new ActionEvent());
                    assertEquals(invalid, position.getText());
                    assertFalse(editor.hasValidEdits());
                    assertTrue(pane.lookup("#dialog-validation-error").isVisible());
                    assertArrayEquals(validPixels, palettePreviewPixels(preview),
                            "Changing color with an invalid position must retain the last valid gradient");
                }
                editor.getChildren().stream().filter(Button.class::isInstance)
                        .map(Button.class::cast).findFirst().orElseThrow().fire();
                assertArrayEquals(validPixels, palettePreviewPixels(preview),
                        "Adding a stop must not publish an invalid draft");
                var rows = (javafx.scene.layout.VBox) editor.getChildren().getFirst();
                var addedRow = (javafx.scene.layout.HBox) rows.getChildren().getLast();
                ((Button) addedRow.getChildren().getLast()).fire();
                assertArrayEquals(validPixels, palettePreviewPixels(preview),
                        "Removing a stop must not publish a remaining invalid draft");

                position.setText("0.25");
                assertFalse(pane.lookup("#dialog-validation-error").isVisible());
                var expected = com.shangin.fractal.coloring.GradientPalette.cached(List.of(
                        new ColorStop(.25, 0xffff0000), new ColorStop(1, 0xffffffff)));
                int[] expectedPixels = java.util.stream.IntStream.range(0, 400)
                        .map(i -> expected.color(i / 399.0)).toArray();
                assertArrayEquals(expectedPixels, palettePreviewPixels(preview));
                picker.setValue(Color.BLUE);
                picker.fireEvent(new ActionEvent());
                var correctedPalette = com.shangin.fractal.coloring.GradientPalette.cached(List.of(
                        new ColorStop(.25, 0xff0000ff), new ColorStop(1, 0xffffffff)));
                assertArrayEquals(java.util.stream.IntStream.range(0, 400)
                        .map(i -> correctedPalette.color(i / 399.0)).toArray(), palettePreviewPixels(preview),
                        "Color changes must use the current valid position draft before Apply");
                primary(dialog).fire();
                assertFalse(dialog.isShowing());
                assertEquals(List.of(new ColorStop(.25, 0xff0000ff), new ColorStop(1, 0xffffffff)),
                        dialog.getResult());
                return null;
            });
        } finally { fx(() -> { dialog.close(); return null; }); }
    }

    private static int[] palettePreviewPixels(javafx.scene.image.ImageView preview) {
        int[] pixels = new int[400];
        preview.getImage().getPixelReader().getPixels(0, 0, 400, 1,
                PixelFormat.getIntArgbInstance(), pixels, 0, 400);
        return pixels;
    }

    @Test void juliaEditorResetsBothFieldsAndAppliesOnlyValidValues() throws Exception {
        JuliaParameters custom = new JuliaParameters(-0.4, 0.6);
        var dialog = fx(() -> FractalDialogs.juliaParametersDialog(owner, custom));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var pane = dialog.getDialogPane();
                TextField real = field(dialog, "julia-real");
                TextField imaginary = field(dialog, "julia-imaginary");
                assertSame(real, pane.getScene().getFocusOwner());
                assertEquals("-0.4", real.getText());
                assertEquals("0.6", imaginary.getText());
                pane.getScene().getPreferences().setColorScheme(ColorScheme.LIGHT);
                snapshot(dialog, "julia-light");
                real.setText("NaN");
                imaginary.setText("1e309");
                primary(dialog).fire();
                assertTrue(dialog.isShowing());
                assertEquals("NaN", real.getText());
                assertEquals("1e309", imaginary.getText());
                assertTrue(pane.lookup("#dialog-validation-error").isVisible());
                pane.getScene().getPreferences().setColorScheme(ColorScheme.DARK);
                snapshot(dialog, "julia-dark-validation");
                Button reset = pane.getButtonTypes().stream()
                        .filter(type -> "Reset to Defaults".equals(type.getText()))
                        .map(type -> (Button) pane.lookupButton(type)).findFirst().orElseThrow();
                reset.fire();
                assertTrue(dialog.isShowing());
                assertEquals("-0.8", real.getText());
                assertEquals("0.156", imaginary.getText());
                assertFalse(pane.lookup("#dialog-validation-error").isVisible());
                primary(dialog).fire();
                assertEquals(new JuliaParameters(), dialog.getResult());
                return null;
            });
        } finally { fx(() -> { dialog.close(); return null; }); }

        var cancelled = fx(() -> FractalDialogs.juliaParametersDialog(owner, custom));
        try {
            fx(() -> { cancelled.show(); return null; });
            fx(() -> {
                Button reset = cancelled.getDialogPane().getButtonTypes().stream()
                        .filter(type -> "Reset to Defaults".equals(type.getText()))
                        .map(type -> (Button) cancelled.getDialogPane().lookupButton(type))
                        .findFirst().orElseThrow();
                reset.fire();
                ((Button) cancelled.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
                assertNull(cancelled.getResult());
                return null;
            });
        } finally { fx(() -> { cancelled.close(); return null; }); }
    }

    @Test void settingsShowRenderSectionWithStandardAndDeepZoomDropdowns() throws Exception {
        var current = new AntialiasSettings(InteractiveRenderMode.REFINED, InteractiveRenderMode.BASE_ONLY);
        var dialog = fx(() -> FractalDialogs.settingsDialog(owner, current));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var pane = dialog.getDialogPane();
                assertEquals("Settings", ((Stage) pane.getScene().getWindow()).getTitle());
                assertTrue(pane.lookupAll(".dialog-section").stream()
                        .anyMatch(node -> "Render Settings".equals(((Label) node).getText())));
                var standard = settingsChoice(pane, SettingsWindow.STANDARD_ID);
                var deep = settingsChoice(pane, SettingsWindow.DEEP_ID);
                assertSame(standard, pane.getScene().getFocusOwner());
                var titles = List.of("Base only", "Base, then antialiasing", "Antialiased display");
                assertEquals(titles, standard.getItems());
                assertEquals(titles, deep.getItems());
                assertEquals("Antialiased display", standard.getValue());
                assertEquals("Base only", deep.getValue());
                assertFalse(deep.isDisabled(), "The deep zoom mode is editable at any zoom");
                pane.getScene().getPreferences().setColorScheme(ColorScheme.LIGHT);
                snapshot(dialog, "settings-light");
                pane.getScene().getPreferences().setColorScheme(ColorScheme.DARK);
                pane.applyCss();
                assertEquals(Color.web("#292929"), pane.getBackground().getFills().getFirst().getFill());
                snapshot(dialog, "settings-dark");
                for (var node : pane.lookupAll(".label")) {
                    if (node instanceof Label label && label.getLabelFor() instanceof ComboBox<?>) {
                        assertEquals(label.prefWidth(-1), label.getWidth(), 0.5,
                                "Field labels must not be truncated: " + label.getText());
                    }
                }
                standard.getSelectionModel().select("Base only");
                deep.getSelectionModel().select("Antialiased display");
                primary(dialog).fire();
                assertFalse(dialog.isShowing());
                assertEquals(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.REFINED),
                        dialog.getResult());
                return null;
            });
        } finally { fx(() -> { dialog.close(); return null; }); }
    }

    @Test void settingsEnterAppliesAndEscapeOrCancelDiscardDraft() throws Exception {
        var current = new AntialiasSettings(InteractiveRenderMode.FAST, InteractiveRenderMode.BASE_ONLY);
        for (String dismissal : List.of("escape", "cancel", "enter")) {
            var dialog = fx(() -> FractalDialogs.settingsDialog(owner, current));
            try {
                fx(() -> { dialog.show(); return null; });
                fx(() -> {
                    var pane = dialog.getDialogPane();
                    settingsChoice(pane, SettingsWindow.STANDARD_ID).getSelectionModel().select("Antialiased display");
                    settingsChoice(pane, SettingsWindow.DEEP_ID).getSelectionModel().select("Base, then antialiasing");
                    switch (dismissal) {
                        case "escape" -> escape(dialog);
                        case "cancel" -> ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                        default -> pane.getScene().getFocusOwner().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
                                "\r", "", KeyCode.ENTER, false, false, false, false));
                    }
                    assertFalse(dialog.isShowing(), dismissal);
                    assertEquals(dismissal.equals("enter")
                            ? new AntialiasSettings(InteractiveRenderMode.REFINED, InteractiveRenderMode.FAST) : null,
                            dialog.getResult(), dismissal);
                    return null;
                });
            } finally { fx(() -> { dialog.close(); return null; }); }
        }
    }

    @Test void settingsResetToDefaultsChangesOnlyTheDraft() throws Exception {
        var current = new AntialiasSettings(InteractiveRenderMode.FAST, InteractiveRenderMode.REFINED);
        var dialog = fx(() -> FractalDialogs.settingsDialog(owner, current));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var pane = dialog.getDialogPane();
                var reset = pane.getButtonTypes().stream()
                        .filter(type -> SettingsWindow.RESET_TITLE.equals(type.getText())).findFirst().orElseThrow();
                ((Button) pane.lookupButton(reset)).fire();
                assertTrue(dialog.isShowing(), "Reset must not close the window");
                var standard = settingsChoice(pane, SettingsWindow.STANDARD_ID);
                assertEquals("Antialiased display", standard.getValue());
                assertEquals("Base only", settingsChoice(pane, SettingsWindow.DEEP_ID).getValue());
                assertSame(standard, pane.getScene().getFocusOwner());
                primary(dialog).fire();
                assertEquals(new AntialiasSettings(), dialog.getResult());
                return null;
            });
        } finally { fx(() -> { dialog.close(); return null; }); }
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> settingsChoice(DialogPane pane, String id) {
        return (ComboBox<String>) pane.lookup("#" + id);
    }

    @Test void iterationEditorResetsBothFieldsAndAppliesOnlyValidValues() throws Exception {
        var current = new IterationSettings(420, 70);
        var dialog = fx(() -> FractalDialogs.iterationSettingsDialog(owner, current));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var pane = dialog.getDialogPane();
                TextField base = field(dialog, "iteration-base");
                TextField zoom = field(dialog, "iteration-zoom");
                assertSame(base, pane.getScene().getFocusOwner());
                assertEquals("420", base.getText());
                assertEquals("70", zoom.getText());
                pane.getScene().getPreferences().setColorScheme(ColorScheme.LIGHT);
                snapshot(dialog, "iterations-light");
                base.setText("0");
                zoom.setText("-1");
                primary(dialog).fire();
                assertTrue(dialog.isShowing());
                assertEquals("0", base.getText());
                assertEquals("-1", zoom.getText());
                assertTrue(pane.lookup("#dialog-validation-error").isVisible());
                pane.getScene().getPreferences().setColorScheme(ColorScheme.DARK);
                snapshot(dialog, "iterations-dark-validation");
                Button reset = pane.getButtonTypes().stream()
                        .filter(type -> "Reset to Defaults".equals(type.getText()))
                        .map(type -> (Button) pane.lookupButton(type)).findFirst().orElseThrow();
                reset.fire();
                assertEquals("300", base.getText());
                assertEquals("50", zoom.getText());
                assertFalse(pane.lookup("#dialog-validation-error").isVisible());
                primary(dialog).fire();
                assertEquals(new IterationSettings(), dialog.getResult());
                return null;
            });
        } finally { fx(() -> { dialog.close(); return null; }); }
    }

    @Test void escapeCancelsInvalidDraftAndHelpAndMessagesShareTheme() throws Exception {
        var coordinates = new RecordingCoordinateEditor(target -> true);
        var coordinate = fx(() -> FractalDialogs.coordinateDialog(owner, new CoordinateText("-0.75", "0", "1"),
                false, coordinates));
        fx(() -> { coordinate.show(); return null; });
        fx(() -> {
            field(coordinate, "coordinate-real").setText("invalid");
            primary(coordinate).fire();
            escape(coordinate);
            assertFalse(coordinate.isShowing());
            assertNull(coordinate.getResult());
            assertTrue(coordinates.applied.isEmpty());
            return null;
        });
        for (var type : Alert.AlertType.values()) {
            if (type != Alert.AlertType.ERROR && type != Alert.AlertType.INFORMATION) continue;
            var alert = fx(() -> FractalDialogs.messageDialog(owner, type, "Export", "PNG image saved",
                    "/Users/example/Pictures/fractal.png"));
            try {
                fx(() -> { alert.show(); return null; });
                fx(() -> {
                    alert.getDialogPane().getScene().getPreferences().setColorScheme(ColorScheme.DARK);
                    assertTrue(primary(alert).isCancelButton());
                    snapshot(alert, "alert-" + type);
                    escape(alert);
                    assertFalse(alert.isShowing()); return null;
                });
            } finally { fx(() -> { alert.close(); return null; }); }
        }
        var help = fx(() -> FractalDialogs.helpDialog(owner));
        try {
            fx(() -> { help.show(); return null; });
            fx(() -> {
                help.getDialogPane().getScene().getPreferences().setColorScheme(ColorScheme.LIGHT);
                snapshot(help, "help-light");
                assertTrue(primary(help).isCancelButton());
                escape(help); assertFalse(help.isShowing()); return null;
            });
        } finally { fx(() -> { help.close(); return null; }); }
    }
}
