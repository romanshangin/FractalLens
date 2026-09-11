package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.css.PseudoClass;
import javafx.util.StringConverter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.math.BigDecimal;

/** Compact editor for the positions and colors of an OKLab gradient. */
final class PaletteStopEditor extends VBox {

    private final VBox rows = new VBox(8.0);
    private final Consumer<List<ColorStop>> onChanged;
    private final List<StopRow> stopRows = new ArrayList<>();
    private boolean updating;
    private Runnable onConfirm = () -> {};
    private Runnable onValidationChanged = () -> {};

    PaletteStopEditor(List<ColorStop> initialStops, Consumer<List<ColorStop>> onChanged) {
        super(12.0);
        this.onChanged = Objects.requireNonNull(onChanged);
        Button add = new Button("Add Color Stop");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> addStop());
        getChildren().addAll(rows, add);
        setPadding(new Insets(2.0, 0.0, 0.0, 0.0));
        setStops(initialStops);
    }

    void setStops(List<ColorStop> stops) {
        updating = true;
        try {
            stopRows.clear();
            rows.getChildren().clear();
            stops.stream()
                    .sorted(Comparator.comparingDouble(ColorStop::position))
                    .forEach(this::appendRow);
            updateRemoveButtons();
        } finally {
            updating = false;
        }
    }

    private void addStop() {
        ColorStop stop = PaletteStopOperations.createAddedStop(currentStops());
        appendRow(stop);
        updateRemoveButtons();
        fireChanged();
        TextField editor = stopRows.getLast().position.getEditor();
        editor.requestFocus();
        editor.selectAll();
        onValidationChanged.run();
    }

    private void appendRow(ColorStop stop) {
        StopRow row = new StopRow(stop);
        stopRows.add(row);
        rows.getChildren().add(row.container);
    }

    private List<ColorStop> currentStops() {
        return stopRows.stream().map(StopRow::value).toList();
    }

    List<ColorStop> getStops() {
        return currentStops();
    }

    TextField initialFocus() { return stopRows.getFirst().position.getEditor(); }
    void setOnConfirm(Runnable action) { onConfirm = Objects.requireNonNull(action); }
    void setOnValidationChanged(Runnable action) { onValidationChanged = Objects.requireNonNull(action); }

    boolean hasValidEdits() {
        return stopRows.stream().allMatch(row -> parsePosition(row.position.getEditor().getText()) != null);
    }

    /** Validate every draft before committing any; invalid input remains visible. */
    TextField commitEdits() {
        TextField invalid = null;
        for (StopRow row : stopRows) {
            row.updateValidation();
            if (parsePosition(row.position.getEditor().getText()) == null && invalid == null)
                invalid = row.position.getEditor();
        }
        if (invalid != null) return invalid;
        stopRows.forEach(row -> row.position.commitValue());
        return null;
    }

    private static Double parsePosition(String text) {
        try {
            BigDecimal value = new BigDecimal(text.trim());
            return value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0 ? null : value.doubleValue();
        } catch (NumberFormatException exception) { return null; }
    }

    private void fireChanged() {
        if (!updating) {
            onChanged.accept(currentStops());
        }
    }

    private void remove(StopRow row) {
        if (stopRows.size() <= 2) {
            return;
        }
        int index = stopRows.indexOf(row);
        stopRows.remove(row);
        rows.getChildren().remove(row.container);
        updateRemoveButtons();
        fireChanged();
        TextField editor = stopRows.get(Math.min(index, stopRows.size() - 1)).position.getEditor();
        editor.requestFocus();
        editor.selectAll();
        onValidationChanged.run();
    }

    private void updateRemoveButtons() {
        boolean disabled = stopRows.size() <= 2;
        stopRows.forEach(row -> row.remove.setDisable(disabled));
    }

    private final class StopRow {
        private final HBox container = new HBox(8.0);
        private final ColorPicker color;
        private final Spinner<Double> position;
        private final Button remove = new Button("−");

        private StopRow(ColorStop stop) {
            color = new ColorPicker(toColor(stop.color()));
            color.setAccessibleText("Color stop color");
            color.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(color, Priority.ALWAYS);

            position = new Spinner<>();
            position.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                    0.0, 1.0, stop.position(), 0.01));
            // Spinner normally discards invalid text on focus loss. Retain the
            // draft for inline validation instead of silently accepting an old value.
            position.getValueFactory().setConverter(new StringConverter<>() {
                @Override public String toString(Double value) { return Double.toString(value); }
                @Override public Double fromString(String text) {
                    Double value = parsePosition(text);
                    return value == null ? position.getValue() : value;
                }
            });
            position.setEditable(true);
            position.setPrefWidth(100.0);
            position.setMinWidth(100.0);
            position.setAccessibleText("Color stop position, from 0 to 1");
            position.getEditor().setAccessibleText("Color stop position, from 0 to 1");
            position.getEditor().setOnAction(event -> { onConfirm.run(); event.consume(); });
            position.getEditor().textProperty().addListener(ignored -> {
                updateValidation();
                onValidationChanged.run();
            });
            remove.setAccessibleText("Remove color stop");

            color.setOnAction(event -> fireChanged());
            position.valueProperty().addListener((observable, oldValue, newValue) -> fireChanged());
            remove.setOnAction(event -> remove(this));
            container.getChildren().addAll(color, position, remove);
        }

        private ColorStop value() {
            return new ColorStop(position.getValue(), toArgb(color.getValue()));
        }

        private void updateValidation() {
            boolean invalid = parsePosition(position.getEditor().getText()) == null;
            position.getEditor().pseudoClassStateChanged(PseudoClass.getPseudoClass("invalid"), invalid);
            position.getEditor().setAccessibleHelp(invalid ? "Enter a position from 0 to 1." : null);
        }
    }

    private static Color toColor(int argb) {
        return Color.rgb(
                (argb >>> 16) & 0xFF,
                (argb >>> 8) & 0xFF,
                argb & 0xFF,
                ((argb >>> 24) & 0xFF) / 255.0
        );
    }

    private static int toArgb(Color color) {
        int alpha = (int) Math.round(color.getOpacity() * 255.0);
        int red = (int) Math.round(color.getRed() * 255.0);
        int green = (int) Math.round(color.getGreen() * 255.0);
        int blue = (int) Math.round(color.getBlue() * 255.0);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
}
