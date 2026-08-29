package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.Locale;

public class ControlPanel extends HBox {

    private static final double SPACING = 10.0;
    private static final double COMBO_BOX_WIDTH = 150.0;
    private final ZoomIndicator zoomIndicator = new ZoomIndicator();
    private final Button exportButton;
    private final TextField centerRealField = createCoordinateField("Re");
    private final TextField centerImaginaryField = createCoordinateField("Im");
    private double centerReal;
    private double centerImaginary;

    public ControlPanel(
            FractalPreset initialFractal,
            PalettePreset initialPalette,
            Consumer<FractalPreset> onFractalChanged,
            Consumer<PalettePreset> onPaletteChanged,
            Runnable onResetView,
            BiConsumer<Double, Double> onCenterChanged,
            Runnable onExport
    ) {
        super(SPACING);

        ComboBox<FractalPreset> fractalComboBox = createFractalComboBox(initialFractal, onFractalChanged);

        ComboBox<PalettePreset> paletteComboBox = createPaletteComboBox(initialPalette, onPaletteChanged);

        exportButton = createExportButton(onExport);
        Button resetViewButton = createResetViewButton(onResetView);
        configureCoordinateCommit(onCenterChanged);

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
                resetViewButton,
                new Label("Center:"),
                centerRealField,
                centerImaginaryField,
                spacer,
                exportButton,
                new Label("Zoom ×"),
                zoomIndicator);

        setPadding(new Insets(10));
        setAlignment(Pos.CENTER_LEFT);
    }

    private static TextField createCoordinateField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setPrefColumnCount(10);
        field.setTooltip(new Tooltip("Enter both center coordinates and press Enter"));
        return field;
    }

    private void configureCoordinateCommit(BiConsumer<Double, Double> onCenterChanged) {
        Runnable commit = () -> {
            try {
                double real = Double.parseDouble(centerRealField.getText().trim());
                double imaginary = Double.parseDouble(centerImaginaryField.getText().trim());

                if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
                    throw new NumberFormatException("Coordinates must be finite");
                }

                onCenterChanged.accept(real, imaginary);
            } catch (NumberFormatException exception) {
                updateCoordinateText();
            }
        };

        centerRealField.setOnAction(event -> commit.run());
        centerImaginaryField.setOnAction(event -> commit.run());
    }

    public void setCenter(Viewport viewport) {
        centerReal = viewport.centerReal();
        centerImaginary = viewport.centerImaginary();

        if (!centerRealField.isFocused() && !centerImaginaryField.isFocused()) {
            updateCoordinateText();
        }
    }

    private void updateCoordinateText() {
        centerRealField.setText(formatCoordinate(centerReal));
        centerImaginaryField.setText(formatCoordinate(centerImaginary));
    }

    static String formatCoordinate(double coordinate) {
        return String.format(Locale.ROOT, "%.15g", coordinate);
    }

    private Button createResetViewButton(Runnable onResetView) {
        Button button = new Button("Reset View");
        button.setOnAction(event -> onResetView.run());
        return button;
    }

    private Button createExportButton(Runnable onExport) {
        Button button = new Button("Export PNG (Adaptive AA)…");
        button.setOnAction(event -> onExport.run());
        return button;
    }

    public void setExportInProgress(boolean inProgress) {
        exportButton.setDisable(inProgress);
        exportButton.setText(inProgress
                ? "Exporting…"
                : "Export PNG (Adaptive AA)…");
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
