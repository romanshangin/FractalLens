package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.export.AdaptivePngExportService;
import com.shangin.fractal.formula.FractalPreset;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;

public class MainView extends BorderPane {

    private final FractalView fractalView;
    private final MainToolbar toolbar;
    private final FractalInspector inspector;
    private final AdaptivePngExportService exportService =
            new AdaptivePngExportService();

    public MainView() {
        FractalPreset initialFractal = FractalPreset.MANDELBROT;
        PalettePreset initialPalette = PalettePreset.ICE;

        this.fractalView = new FractalView(initialFractal, initialPalette);

        toolbar = new MainToolbar(
                fractalView::resetView,
                this::exportPng
        );
        inspector = new FractalInspector(
                initialFractal,
                initialPalette,
                fractalView::setFractal,
                fractalView::setPalette,
                fractalView::setPaletteStops,
                fractalView::setOrbitTrap,
                fractalView::setSamplingPattern,
                fractalView::setInteractiveRenderMode,
                fractalView::setDeepAntialiasing,
                fractalView::setHistogramColoring,
                fractalView::setColorCycling,
                fractalView::setCenter
        );
        fractalView.setOnZoomChanged(
                inspector::setZoom
        );
        fractalView.setOnDeepZoomChanged(active -> {
            toolbar.setDeepZoom(active);
            inspector.setDeepZoom(active);
        });
        fractalView.setOnViewportChanged(inspector::setCenter);
        fractalView.setOnRenderingChanged(inspector::setAppearanceDisabled);
        fractalView.setOnColorCyclingStopped(
                () -> inspector.setColorCyclingSelected(false));

        setTop(toolbar);
        setLeft(inspector);
        setCenter(fractalView);
    }

    private void exportPng() {
        if (!fractalView.hasCompletedFrame()) {
            showError(
                    "Export unavailable",
                    "Wait for the first image to finish rendering before exporting."
            );
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export fractal as PNG");
        chooser.setInitialFileName(fractalView.suggestedExportFileName());
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PNG image", "*.png")
        );

        File selected = chooser.showSaveDialog(getScene().getWindow());

        if (selected == null) {
            return;
        }

        Path target = withPngExtension(selected.toPath());

        toolbar.setExportInProgress(true);

        fractalView.exportAntialiasedPng(
                exportService,
                target,
                completedPath -> Platform.runLater(() -> exportCompleted(completedPath)),
                exception -> Platform.runLater(() -> exportFailed(exception))
        );
    }

    static Path withPngExtension(Path path) {
        String fileName = path.getFileName().toString();

        if (fileName.toLowerCase().endsWith(".png")) {
            return path;
        }

        return path.resolveSibling(fileName + ".png");
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(getScene().getWindow());
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void exportCompleted(Path path) {
        toolbar.setExportInProgress(false);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(getScene().getWindow());
        alert.setTitle("Export complete");
        alert.setHeaderText("PNG image saved");
        alert.setContentText(path.toAbsolutePath().toString());
        alert.showAndWait();
    }

    private void exportFailed(Throwable exception) {
        toolbar.setExportInProgress(false);
        showError(
                "Export failed",
                "The PNG image could not be saved: " + exception.getMessage()
        );
    }

    public void close() {
        exportService.close();
        fractalView.close();
    }
}
