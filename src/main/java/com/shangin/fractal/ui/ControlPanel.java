package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Consumer;

public class ControlPanel extends HBox {

    private static final double SPACING = 10.0;
    private static final double COMBO_BOX_WIDTH = 150.0;
    private final ZoomIndicator zoomIndicator = new ZoomIndicator();

    public ControlPanel(
            FractalPreset initialFractal,
            PalettePreset initialPalette,
            Consumer<FractalPreset> onFractalChanged,
            Consumer<PalettePreset> onPaletteChanged
    ) {
        super(SPACING);

        ComboBox<FractalPreset> fractalComboBox = createFractalComboBox(initialFractal, onFractalChanged);

        ComboBox<PalettePreset> paletteComboBox = createPaletteComboBox(initialPalette, onPaletteChanged);

        Region spacer =
                new Region();

        HBox.setHgrow(
                spacer,
                Priority.ALWAYS
        );

        getChildren().addAll(
                new Label("Fractal:"),
                fractalComboBox,
                new Label("Palette:"),
                paletteComboBox,
                spacer,
                zoomIndicator);

        setPadding(new Insets(10));
        setAlignment(Pos.CENTER_LEFT);
    }

    public void setZoom(double zoomFactor) {
        zoomIndicator.setZoom(
                zoomFactor
        );
    }

    private ComboBox<FractalPreset> createFractalComboBox(
            FractalPreset initialValue,
            Consumer<FractalPreset> onChanged
    ) {
        ComboBox<FractalPreset> comboBox = new ComboBox<>();

        comboBox.getItems().setAll(FractalPreset.values());

        comboBox.setValue(initialValue);
        comboBox.setPrefWidth(COMBO_BOX_WIDTH);

        comboBox.setOnAction(event -> onChanged.accept(comboBox.getValue()));

        return comboBox;
    }

    private ComboBox<PalettePreset> createPaletteComboBox(
            PalettePreset initialValue,
            Consumer<PalettePreset> onChanged
    ) {
        ComboBox<PalettePreset> comboBox = new ComboBox<>();

        comboBox.getItems().setAll(PalettePreset.values());

        comboBox.setValue(initialValue);
        comboBox.setPrefWidth(COMBO_BOX_WIDTH);

        comboBox.setOnAction(event -> onChanged.accept(comboBox.getValue()));

        return comboBox;
    }
}