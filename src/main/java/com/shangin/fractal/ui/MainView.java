package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.export.AdaptivePngExportService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.scene.FractalScene;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.MenuBar;
import javafx.scene.control.skin.MenuBarSkin;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public class MainView extends BorderPane {

    private final FractalView fractalView;
    private final MainMenuBar menuBar;
    private final LastSessionStore sessionStore;
    private boolean closed;
    private final AdaptivePngExportService exportService =
            new AdaptivePngExportService();

    public MainView(Stage stage) {
        this(stage, LastSessionStore.systemDefault());
    }

    MainView(Stage stage, LastSessionStore sessionStore) {
        this(stage, sessionStore, new FractalRenderService());
    }

    MainView(Stage stage, LastSessionStore sessionStore, FractalRenderService renderService) {
        this(stage, sessionStore, renderService, null);
    }

    MainView(Stage stage, LastSessionStore sessionStore, FractalRenderService renderService,
             FractalRenderController.RecolorOperation recolorOperation) {
        this.sessionStore = java.util.Objects.requireNonNull(sessionStore);
        InitialScene initial = loadInitialScene(sessionStore);
        FractalScene initialScene = initial.scene();
        fractalView = recolorOperation == null
                ? new FractalView(initialScene, initial.restored(), renderService)
                : new FractalView(initialScene, initial.restored(), renderService, recolorOperation);
        fractalView.setOnRenderError(this::renderFailed);
        menuBar = new MainMenuBar(stage, fractalView, initialScene, this::exportPng);
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            // Glass falls back to this menu while no stage is focused, including
            // the activation gap when returning from another macOS Space.
            MenuBarSkin.setDefaultSystemMenuBar(menuBar);
        }
        // JavaFX gives this bar zero height when macOS installs it in the system menu bar.
        setTop(menuBar);
        setCenter(fractalView);
        stage.setTitle(initialScene.fractal() + " — FractalUI");
    }

    private static InitialScene loadInitialScene(LastSessionStore sessionStore) {
        try {
            return sessionStore.load()
                    .map(scene -> new InitialScene(scene, true))
                    .orElseGet(MainView::defaultInitialScene);
        } catch (IOException exception) {
            System.err.println("Could not restore the last FractalUI session: "
                    + exception.getMessage());
            return defaultInitialScene();
        }
    }

    private static InitialScene defaultInitialScene() {
        return new InitialScene(
                FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE), false);
    }

    private record InitialScene(FractalScene scene, boolean restored) {}

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
        FractalDialogs.messageDialog(getScene().getWindow(), Alert.AlertType.ERROR,
                title, title, message).showAndWait();
    }

    private void exportCompleted(Path path) {
        if (closed) {
            return;
        }
        menuBar.setExportInProgress(false);

        FractalDialogs.messageDialog(getScene().getWindow(), Alert.AlertType.INFORMATION,
                "Export complete", "PNG image saved", path.toAbsolutePath().toString()).showAndWait();
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

    private void renderFailed(Throwable exception) {
        if (closed) return;
        String detail = exception.getMessage();
        showError("Render failed", detail == null || detail.isBlank()
                ? "The image could not be rendered. Try changing the view or settings."
                : "The image could not be rendered: " + detail);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            MenuBarSkin.setDefaultSystemMenuBar(new MenuBar());
        }
        try {
            sessionStore.save(fractalView.sceneSnapshot());
        } catch (IOException exception) {
            System.err.println("Could not save the last FractalUI session: "
                    + exception.getMessage());
        }
        exportService.close();
        fractalView.close();
    }
}
