package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.math.Viewport;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.css.PseudoClass;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Focused editors reached from the menu bar; none occupies canvas space. */
final class FractalDialogs {
    private FractalDialogs() {}

    static Optional<PreciseComplex> coordinates(Window owner, Viewport viewport,
                                                BigDecimal zoom, boolean deepZoom) {
        return coordinateDialog(owner, viewport, zoom, deepZoom).showAndWait();
    }

    static Dialog<PreciseComplex> coordinateDialog(Window owner, Viewport viewport,
                                                   BigDecimal zoom, boolean deepZoom) {
        Dialog<PreciseComplex> dialog = new Dialog<>();
        dialog.setTitle("Go to Coordinates");
        TextField real = new TextField(viewport.center().real().toString());
        TextField imaginary = new TextField(viewport.center().imaginary().toString());
        real.setId("coordinate-real");
        imaginary.setId("coordinate-imaginary");
        real.setPromptText("For example, -0.75");
        imaginary.setPromptText("For example, 0");
        ZoomIndicator scale = new ZoomIndicator();
        scale.setZoom(zoom);
        GridPane fields = new GridPane(12, 12);
        fields.addRow(0, label("Real", real), real);
        fields.addRow(1, label("Imaginary", imaginary), imaginary);
        fields.addRow(2, label("Zoom ×", scale), scale);
        for (Node field : List.of(real, imaginary, scale)) {
            GridPane.setHgrow(field, Priority.ALWAYS);
        }
        Label hint = new Label(deepZoom ? "High-precision deep zoom is active."
                : "Move the center of the view without changing its zoom.");
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        Label error = errorLabel();
        VBox content = new VBox(14, hint, fields, error);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType go = new ButtonType("Go", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, () -> real);
        Button confirm = DialogAppearance.buttons(dialog, go, ButtonType.CANCEL);
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            if (!validateCoordinates(dialog, real, imaginary, error, true)) event.consume();
        });
        real.setOnAction(event -> { confirm.fire(); event.consume(); });
        imaginary.setOnAction(event -> { confirm.fire(); event.consume(); });
        for (TextField field : List.of(real, imaginary)) {
            field.textProperty().addListener(ignored -> {
                if (error.isVisible()) validateCoordinates(dialog, real, imaginary, error, false);
            });
        }
        dialog.setResultConverter(button -> button == go
                ? new PreciseComplex(new BigDecimal(real.getText().trim()),
                        new BigDecimal(imaginary.getText().trim())) : null);
        return dialog;
    }

    static Optional<List<ColorStop>> palette(Window owner, List<ColorStop> stops) {
        return paletteDialog(owner, stops).showAndWait();
    }

    static Dialog<List<ColorStop>> paletteDialog(Window owner, List<ColorStop> stops) {
        Dialog<List<ColorStop>> dialog = new Dialog<>();
        dialog.setTitle("Edit Palette");
        PaletteStopEditor editor = new PaletteStopEditor(stops, ignored -> {});
        ScrollPane scroll = new ScrollPane(editor);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(Math.min(280, Math.max(150, stops.size() * 46 + 42)));
        Label hint = new Label("Choose colors and their positions along the gradient, from 0 to 1.");
        hint.setWrapText(true);
        hint.getStyleClass().add("dialog-hint");
        Label error = errorLabel();
        VBox content = new VBox(14, hint, scroll, error);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(460);
        ButtonType apply = new ButtonType("Apply", ButtonData.OK_DONE);
        DialogAppearance.install(dialog, owner, editor::initialFocus);
        Button confirm = DialogAppearance.buttons(dialog, apply, ButtonType.CANCEL);
        editor.setOnConfirm(confirm::fire);
        confirm.addEventFilter(ActionEvent.ACTION, event -> {
            TextField invalid = editor.commitEdits();
            if (invalid != null) {
                event.consume();
                showError(dialog, error, "Enter a position from 0 to 1 for every color stop.");
                invalid.requestFocus();
                invalid.selectAll();
            }
        });
        editor.setOnValidationChanged(() -> {
            if (error.isVisible() && editor.hasValidEdits()) clearError(dialog, error);
        });
        dialog.setResultConverter(button -> button == apply ? editor.getStops() : null);
        return dialog;
    }

    static void help(Window owner) {
        helpDialog(owner).showAndWait();
    }

    static Dialog<Void> helpDialog(Window owner) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("FractalUI Help");
        Label content = new Label("Explore the fractal\n\n"
                + "Drag to move. Use the arrow keys to pan with the keyboard.\n"
                + "Scroll with a mouse to zoom toward the pointer.\n"
                + "On a trackpad, pinch to zoom and scroll with two fingers to pan.\n\n"
                + "Use View to zoom, reset the view, enter coordinates, or go full screen.\n"
                + "Choose a formula in Fractal, a palette in Color, and antialiasing in Render.\n"
                + "Deep Zoom Antialiasing becomes available when zooming deeply into Mandelbrot.\n"
                + "Animate Palette is available when Histogram Coloring and Orbit Trap are off.\n\n"
                + "Use File → Export PNG… to save the rendered image.");
        content.setWrapText(true);
        content.getStyleClass().add("dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(520);
        DialogAppearance.install(dialog, owner, () -> dialog.getDialogPane().lookupButton(ButtonType.CLOSE));
        DialogAppearance.buttons(dialog, ButtonType.CLOSE);
        return dialog;
    }

    static Alert messageDialog(Window owner, Alert.AlertType type, String title, String header, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message);
        alert.getDialogPane().setPrefWidth(480);
        DialogAppearance.install(alert, owner, () -> alert.getDialogPane().lookupButton(ButtonType.OK));
        DialogAppearance.buttons(alert, ButtonType.OK);
        return alert;
    }

    private static Label errorLabel() {
        Label error = new Label();
        error.setId("dialog-validation-error");
        error.getStyleClass().add("dialog-error");
        error.setWrapText(true);
        error.setMinHeight(Region.USE_PREF_SIZE);
        error.setVisible(false);
        error.setManaged(false);
        return error;
    }

    private static boolean validateCoordinates(Dialog<?> dialog, TextField real, TextField imaginary,
                                               Label error, boolean focus) {
        TextField firstInvalid = null;
        for (TextField field : List.of(real, imaginary)) {
            boolean invalid = !isCoordinate(field.getText());
            field.pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), invalid);
            field.setAccessibleHelp(invalid ? "Enter a number; scientific notation is supported." : null);
            if (invalid && firstInvalid == null) firstInvalid = field;
        }
        if (firstInvalid == null) {
            clearError(dialog, error);
            return true;
        }
        showError(dialog, error, "Enter a number for each coordinate. Scientific notation, such as 1.5e-12, is supported.");
        if (focus) { firstInvalid.requestFocus(); firstInvalid.selectAll(); }
        return false;
    }

    private static void showError(Dialog<?> dialog, Label error, String message) {
        boolean changed = !error.isVisible();
        error.setText(message);
        error.setVisible(true);
        error.setManaged(true);
        if (changed) resizeForValidation(dialog);
    }

    private static void clearError(Dialog<?> dialog, Label error) {
        boolean changed = error.isVisible();
        error.setVisible(false);
        error.setManaged(false);
        if (changed) resizeForValidation(dialog);
    }

    private static void resizeForValidation(Dialog<?> dialog) {
        if (!dialog.isShowing()) return;
        // Establish the wrapped label's width before measuring the window.
        dialog.getDialogPane().applyCss();
        dialog.getDialogPane().layout();
        dialog.getDialogPane().getScene().getWindow().sizeToScene();
    }

    private static Label label(String text, Node field) {
        Label label = new Label(text);
        label.setLabelFor(field);
        return label;
    }

    private static boolean isCoordinate(String text) {
        try {
            new BigDecimal(text.trim());
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
