package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Compact editor for the positions and colors of an OKLab gradient. */
final class PaletteStopEditor extends VBox {

    private final VBox rows = new VBox(5.0);
    private final Consumer<List<ColorStop>> onChanged;
    private final List<StopRow> stopRows = new ArrayList<>();
    private boolean updating;

    PaletteStopEditor(List<ColorStop> initialStops, Consumer<List<ColorStop>> onChanged) {
        super(6.0);
        this.onChanged = Objects.requireNonNull(onChanged);
        Button add = new Button("Add stop");
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
    }

    private void appendRow(ColorStop stop) {
        StopRow row = new StopRow(stop);
        stopRows.add(row);
        rows.getChildren().add(row.container);
    }

    private List<ColorStop> currentStops() {
        return stopRows.stream().map(StopRow::value).toList();
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
        stopRows.remove(row);
        rows.getChildren().remove(row.container);
        updateRemoveButtons();
        fireChanged();
    }

    private void updateRemoveButtons() {
        boolean disabled = stopRows.size() <= 2;
        stopRows.forEach(row -> row.remove.setDisable(disabled));
    }

    private final class StopRow {
        private final HBox container = new HBox(5.0);
        private final ColorPicker color;
        private final Spinner<Double> position;
        private final Button remove = new Button("−");

        private StopRow(ColorStop stop) {
            color = new ColorPicker(toColor(stop.color()));
            color.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(color, Priority.ALWAYS);

            position = new Spinner<>();
            position.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                    0.0, 1.0, stop.position(), 0.01));
            position.setEditable(true);
            position.setPrefWidth(84.0);

            color.setOnAction(event -> fireChanged());
            position.valueProperty().addListener((observable, oldValue, newValue) -> fireChanged());
            remove.setOnAction(event -> remove(this));
            container.getChildren().addAll(color, position, remove);
        }

        private ColorStop value() {
            return new ColorStop(position.getValue(), toArgb(color.getValue()));
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
