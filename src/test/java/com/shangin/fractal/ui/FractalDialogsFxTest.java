package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.formula.FractalPreset;
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
        var dialog = fx(() -> FractalDialogs.coordinateDialog(owner, FractalPreset.MANDELBROT.defaultViewport(),
                new BigDecimal("1.25"), false));
        try {
            fx(() -> { dialog.show(); return null; });
            fx(() -> {
                var pane = dialog.getDialogPane();
                var real = field(dialog, "coordinate-real");
                var imaginary = field(dialog, "coordinate-imaginary");
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
                imaginary.fireEvent(new ActionEvent());
                assertFalse(dialog.isShowing());
                assertEquals(new BigDecimal(real.getText()), dialog.getResult().real());
                assertEquals(new BigDecimal(imaginary.getText()), dialog.getResult().imaginary());
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

    @Test void escapeCancelsInvalidDraftAndHelpAndMessagesShareTheme() throws Exception {
        var coordinate = fx(() -> FractalDialogs.coordinateDialog(owner, FractalPreset.MANDELBROT.defaultViewport(),
                BigDecimal.ONE, false));
        fx(() -> { coordinate.show(); return null; });
        fx(() -> {
            field(coordinate, "coordinate-real").setText("invalid");
            primary(coordinate).fire();
            escape(coordinate);
            assertFalse(coordinate.isShowing());
            assertNull(coordinate.getResult());
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
