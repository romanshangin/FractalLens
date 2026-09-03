package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.export.AdaptivePngExportService;
import com.shangin.fractal.formula.FractalPreset;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

public class MainView extends BorderPane {

    private final FractalView fractalView;
    private final MainMenuBar menuBar;
    private boolean closed;
    private final AdaptivePngExportService exportService =
            new AdaptivePngExportService();

    public MainView(Stage stage) {
        FractalPreset initialFractal = FractalPreset.MANDELBROT;
        PalettePreset initialPalette = PalettePreset.ICE;
        fractalView = new FractalView(initialFractal, initialPalette);
        menuBar = new MainMenuBar(stage, fractalView, initialFractal, initialPalette, this::exportPng);
        // JavaFX gives this bar zero height when macOS installs it in the system menu bar.
        setTop(menuBar);
        setCenter(fractalView);
        stage.setTitle(initialFractal + " — FractalUI");
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

        menuBar.setExportInProgress(true);

        fractalView.exportAntialiasedPng(
                exportService,
                target,
                completedPath -> Platform.runLater(() -> exportCompleted(completedPath)),
                exception -> Platform.runLater(() -> exportFailed(exception))
        );
    }

    static Path withPngExtension(Path path) {
        String fileName = path.getFileName().toString();

        if (fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return path;
        }

        return path.resolveSibling(fileName + ".png");
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(getScene().getWindow());
        alert.initModality(Modality.WINDOW_MODAL);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void exportCompleted(Path path) {
        if (closed) {
            return;
        }
        menuBar.setExportInProgress(false);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(getScene().getWindow());
        alert.initModality(Modality.WINDOW_MODAL);
        alert.setTitle("Export complete");
        alert.setHeaderText("PNG image saved");
        alert.setContentText(path.toAbsolutePath().toString());
        alert.showAndWait();
    }

    private void exportFailed(Throwable exception) {
        if (closed) {
            return;
        }
        menuBar.setExportInProgress(false);
        showError(
                "Export failed",
                "The PNG image could not be saved: " + exception.getMessage()
        );
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        exportService.close();
        fractalView.close();
    }
}
