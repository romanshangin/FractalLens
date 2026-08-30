package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.SamplingPattern;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Left-side editor for scene and navigation parameters. */
public final class FractalInspector extends ScrollPane {

    private static final double INSPECTOR_WIDTH = 290.0;
    private final TextField centerRealField = createCoordinateField();
    private final TextField centerImaginaryField = createCoordinateField();
    private final ZoomIndicator zoomIndicator = new ZoomIndicator();
    private double centerReal;
    private double centerImaginary;

    public FractalInspector(
            FractalPreset initialFractal,
            PalettePreset initialPalette,
            Consumer<FractalPreset> onFractalChanged,
            Consumer<PalettePreset> onPaletteChanged,
            Consumer<SamplingPattern> onSamplingPatternChanged,
            Consumer<InteractiveRenderMode> onRenderModeChanged,
            BiConsumer<Double, Double> onCenterChanged
    ) {
        ComboBox<FractalPreset> fractal = new ComboBox<>();
        fractal.getItems().setAll(FractalPreset.values());
        fractal.setValue(initialFractal);
        fractal.setMaxWidth(Double.MAX_VALUE);
        fractal.setOnAction(event -> onFractalChanged.accept(fractal.getValue()));

        ComboBox<PalettePreset> palette = new ComboBox<>();
        palette.getItems().setAll(PalettePreset.values());
        palette.setValue(initialPalette);
        palette.setMaxWidth(Double.MAX_VALUE);
        palette.setOnAction(event -> onPaletteChanged.accept(palette.getValue()));

        ComboBox<SamplingPattern> samplingPattern = new ComboBox<>();
        samplingPattern.getItems().setAll(SamplingPattern.values());
        samplingPattern.setValue(SamplingPattern.REGULAR);
        samplingPattern.setMaxWidth(Double.MAX_VALUE);
        samplingPattern.setOnAction(event ->
                onSamplingPatternChanged.accept(samplingPattern.getValue()));

        ComboBox<InteractiveRenderMode> renderMode = new ComboBox<>();
        renderMode.getItems().setAll(InteractiveRenderMode.values());
        renderMode.setValue(InteractiveRenderMode.REFINED);
        renderMode.setMaxWidth(Double.MAX_VALUE);
        renderMode.setOnAction(event ->
                onRenderModeChanged.accept(renderMode.getValue()));

        configureCoordinateCommit(onCenterChanged);

        VBox sections = new VBox(
                8.0,
                section("Fractal", singleControlGrid("Type", fractal)),
                section("Navigation", navigationGrid()),
                section("Appearance", appearanceGrid(palette, samplingPattern, renderMode))
        );
        sections.setPadding(new Insets(10));
        sections.setFillWidth(true);

        setContent(sections);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setPrefWidth(INSPECTOR_WIDTH);
        setMinWidth(240.0);
        setMaxWidth(340.0);
        setStyle("-fx-border-color: transparent -fx-box-border transparent transparent;");
    }

    private static TitledPane section(String title, GridPane content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setAnimated(false);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        return pane;
    }

    private static GridPane singleControlGrid(String label, ComboBox<?> control) {
        GridPane grid = createGrid();
        grid.addRow(0, new Label(label), control);
        GridPane.setHgrow(control, Priority.ALWAYS);
        return grid;
    }

    private static GridPane appearanceGrid(
            ComboBox<PalettePreset> palette,
            ComboBox<SamplingPattern> samplingPattern,
            ComboBox<InteractiveRenderMode> renderMode
    ) {
        GridPane grid = createGrid();
        grid.addRow(0, new Label("Palette"), palette);
        grid.addRow(1, new Label("AA pattern"), samplingPattern);
        grid.addRow(2, new Label("Display"), renderMode);
        GridPane.setHgrow(palette, Priority.ALWAYS);
        GridPane.setHgrow(samplingPattern, Priority.ALWAYS);
        GridPane.setHgrow(renderMode, Priority.ALWAYS);
        return grid;
    }

    private GridPane navigationGrid() {
        GridPane grid = createGrid();
        grid.addRow(0, new Label("Re"), centerRealField);
        grid.addRow(1, new Label("Im"), centerImaginaryField);
        grid.addRow(2, new Label("Zoom ×"), zoomIndicator);
        GridPane.setHgrow(centerRealField, Priority.ALWAYS);
        GridPane.setHgrow(centerImaginaryField, Priority.ALWAYS);
        GridPane.setHgrow(zoomIndicator, Priority.ALWAYS);
        return grid;
    }

    private static GridPane createGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(8.0);
        grid.setVgap(8.0);
        grid.setPadding(new Insets(10));
        return grid;
    }

    private static TextField createCoordinateField() {
        TextField field = new TextField();
        field.setTooltip(new Tooltip("Enter both center coordinates and press Enter"));
        field.setMaxWidth(Double.MAX_VALUE);
        return field;
    }

    private void configureCoordinateCommit(BiConsumer<Double, Double> onCenterChanged) {
        Runnable commit = () -> {
            try {
                double real = parseCoordinate(centerRealField.getText());
                double imaginary = parseCoordinate(centerImaginaryField.getText());
                onCenterChanged.accept(real, imaginary);
            } catch (NumberFormatException exception) {
                updateCoordinateText();
            }
        };

        centerRealField.setOnAction(event -> commit.run());
        centerImaginaryField.setOnAction(event -> commit.run());
    }

    private static double parseCoordinate(String text) {
        double coordinate = Double.parseDouble(text.trim());
        if (!Double.isFinite(coordinate)) {
            throw new NumberFormatException("Coordinates must be finite");
        }
        return coordinate;
    }

    public void setCenter(Viewport viewport) {
        centerReal = viewport.centerReal();
        centerImaginary = viewport.centerImaginary();

        if (!centerRealField.isFocused() && !centerImaginaryField.isFocused()) {
            updateCoordinateText();
        }
    }

    public void setZoom(double zoomFactor) {
        zoomIndicator.setZoom(zoomFactor);
    }

    private void updateCoordinateText() {
        centerRealField.setText(formatCoordinate(centerReal));
        centerImaginaryField.setText(formatCoordinate(centerImaginary));
    }

    static String formatCoordinate(double coordinate) {
        return String.format(Locale.ROOT, "%.15g", coordinate);
    }
}
