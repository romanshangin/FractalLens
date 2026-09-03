package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.math.Viewport;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Focused editors reached from the menu bar; none occupies canvas space. */
final class FractalDialogs {
    private FractalDialogs() {}

    static Optional<PreciseComplex> coordinates(Window owner, Viewport viewport,
                                                BigDecimal zoom, boolean deepZoom) {
        Dialog<PreciseComplex> dialog = dialog(owner, "Go to Coordinates");
        TextField real = new TextField(viewport.center().real().toString());
        TextField imaginary = new TextField(viewport.center().imaginary().toString());
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
        Label error = new Label();
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        VBox content = new VBox(16, hint, fields, error);
        content.setPadding(new Insets(20));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(480);
        ButtonType go = new ButtonType("Go", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, go);
        dialog.getDialogPane().lookupButton(go).addEventFilter(ActionEvent.ACTION, event -> {
            TextField invalid = !isCoordinate(real.getText()) ? real
                    : !isCoordinate(imaginary.getText()) ? imaginary : null;
            if (invalid != null) {
                event.consume();
                error.setText("Enter a number for each coordinate. Scientific notation, such as 1.5e-12, is supported.");
                error.setVisible(true);
                error.setManaged(true);
                invalid.requestFocus();
                invalid.selectAll();
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        });
        dialog.setResultConverter(button -> button == go
                ? new PreciseComplex(new BigDecimal(real.getText().trim()),
                        new BigDecimal(imaginary.getText().trim())) : null);
        dialog.setOnShown(event -> Platform.runLater(() -> {
            real.requestFocus();
            real.selectAll();
        }));
        return dialog.showAndWait();
    }

    static Optional<List<ColorStop>> palette(Window owner, List<ColorStop> stops) {
        Dialog<List<ColorStop>> dialog = dialog(owner, "Edit Palette");
        PaletteStopEditor editor = new PaletteStopEditor(stops, ignored -> {});
        ScrollPane scroll = new ScrollPane(editor);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(280);
        Label hint = new Label("Choose colors and their positions along the gradient, from 0 to 1.");
        hint.setWrapText(true);
        VBox content = new VBox(16, hint, scroll);
        content.setPadding(new Insets(20));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(460);
        ButtonType apply = new ButtonType("Apply", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, apply);
        dialog.setResultConverter(button -> button == apply ? editor.getStops() : null);
        return dialog.showAndWait();
    }

    static void help(Window owner) {
        Dialog<Void> dialog = dialog(owner, "FractalUI Help");
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
        content.setPadding(new Insets(20));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(520);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private static <T> Dialog<T> dialog(Window owner, String title) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(title);
        dialog.setResizable(true);
        return dialog;
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
