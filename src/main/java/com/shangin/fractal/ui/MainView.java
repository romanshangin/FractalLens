package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.export.AdaptivePngExportService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderTarget;
import javafx.event.EventHandler;
import com.shangin.fractal.scene.FractalScene;
import javafx.application.Platform;
import javafx.scene.control.MenuBar;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.stage.WindowEvent;
import javafx.scene.control.skin.MenuBarSkin;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;

public class MainView extends BorderPane {

    private final Stage stage;
    private final EventHandler<WindowEvent> closeRequestHandler = event -> {
        if (!confirmClose()) event.consume();
    };
    private final FractalView fractalView;
    private final MainMenuBar menuBar;
    private final LastSessionStore sessionStore;
    private boolean closed;
    private boolean exporting;
    private long exportRequest;
    private final PauseTransition exportStatusTimeout = new PauseTransition(Duration.seconds(4));
    private boolean confirmingClose;
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
        this.stage = stage;
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
        setCenter(new StackPane(fractalView));
        exportStatusTimeout.setOnFinished(event -> fractalView.setExportStatus(""));
        stage.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequestHandler);
        stage.setTitle(initialScene.fractal() + " — FractalLens");
    }

    /** Directs application focus to the canvas that owns keyboard navigation. */
    @Override
    public void requestFocus() {
        fractalView.requestFocus();
    }

    private static InitialScene loadInitialScene(LastSessionStore sessionStore) {
        try {
            return sessionStore.load()
                    .map(scene -> new InitialScene(scene, true))
                    .orElseGet(MainView::defaultInitialScene);
        } catch (IOException exception) {
            System.err.println("Could not restore the last FractalLens session: "
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
        if (exporting) {
            exportRequest++;
            exportService.cancelCurrent();
            setExporting(false);
            setExportStatus("PNG export cancelled.");
            return;
        }
        if (!fractalView.hasCompletedFrame()) {
            showError(
                    "Export unavailable",
                    "Wait for the first image to finish rendering before exporting."
            );
            return;
        }

        FractalScene snapshot = fractalView.sceneSnapshot();
        FractalDialogs.exportSize(getScene().getWindow(), fractalView.exportTarget(),
                dimensions -> chooseExportFile(snapshot, dimensions));
    }

    private void chooseExportFile(FractalScene snapshot, RenderTarget dimensions) {
        if (closed || !stage.isShowing() || exporting) return;
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

        startExport(snapshot, dimensions, target);
    }

    void startExport(FractalScene snapshot, RenderTarget dimensions, Path target) {
        long request = ++exportRequest;
        setExporting(true);
        setExportStatus("Export PNG 0%");
        try {
            exportService.export(snapshot, dimensions, target,
                    percent -> Platform.runLater(() -> {
                        if (request == exportRequest && exporting && !closed) {
                            setExportStatus("Export PNG " + percent + "%");
                        }
                    }),
                    completedPath -> Platform.runLater(() -> {
                        if (request == exportRequest) exportCompleted(completedPath);
                    }),
                    exception -> Platform.runLater(() -> {
                        if (request == exportRequest) exportFailed(target, exception);
                    }));
        } catch (RuntimeException exception) {
            exportFailed(target, exception);
        }
    }

    private void setExporting(boolean active) {
        exporting = active;
        menuBar.setExportInProgress(active);
    }

    private void setExportStatus(String message) {
        exportStatusTimeout.stop();
        fractalView.setExportStatus(message);
        if (!exporting) exportStatusTimeout.playFromStart();
    }

    private boolean confirmClose() {
        if (!exporting || closed) return true;
        if (confirmingClose) return false;
        confirmingClose = true;
        FractalDialogs.confirmExportClose(getScene().getWindow(), accepted -> {
            confirmingClose = false;
            if (accepted && !closed && stage.isShowing()) {
                close();
                stage.close();
            }
        });
        return false;
    }

    static Path withPngExtension(Path path) {
        String fileName = path.getFileName().toString();

        if (fileName.toLowerCase(Locale.ROOT).endsWith(".png")) {
            return path;
        }

        return path.resolveSibling(fileName + ".png");
    }

    private void showError(String title, String message) {
        FractalDialogs.message(getScene().getWindow(), title, message);
    }

    private void exportCompleted(Path path) {
        if (closed) {
            return;
        }
        setExporting(false);

        setExportStatus("PNG saved");
    }

    private void exportFailed(Path target, Throwable exception) {
        if (closed) {
            return;
        }
        setExporting(false);
        setExportStatus("PNG export failed.");
        showError("Export failed", exportFailureMessage(target, exception));
    }

    /**
     * Describes a failed export in terms of the file the user chose. File system exceptions
     * name the internal staging file, so their path is never shown.
     */
    static String exportFailureMessage(Path target, Throwable exception) {
        Throwable cause = exception instanceof UncheckedIOException unchecked
                && unchecked.getCause() != null ? unchecked.getCause() : exception;
        Path folder = target.toAbsolutePath().getParent();
        String reason = switch (cause) {
            case AccessDeniedException ignored ->
                    "FractalLens does not have permission to write to the folder \"" + folder
                            + "\". Choose another folder.";
            case NoSuchFileException ignored ->
                    "The folder \"" + folder + "\" does not exist.";
            case FileSystemException fileSystem -> sentence(fileSystem.getReason(),
                    "A file system error occurred.");
            default -> sentence(cause.getMessage(), "An unexpected error occurred.");
        };
        return "The PNG image could not be saved as \"" + target.getFileName() + "\". " + reason;
    }

    private static String sentence(String text, String fallback) {
        if (text == null || text.isBlank()) return fallback;
        String trimmed = text.strip();
        String capitalized = Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
        return capitalized.endsWith(".") ? capitalized : capitalized + ".";
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
        stage.removeEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequestHandler);
        exportStatusTimeout.stop();
        exportRequest++;
        if (System.getProperty("os.name", "").startsWith("Mac")) {
            MenuBarSkin.setDefaultSystemMenuBar(new MenuBar());
        }
        try {
            sessionStore.save(fractalView.sceneSnapshot());
        } catch (IOException exception) {
            System.err.println("Could not save the last FractalLens session: "
                    + exception.getMessage());
        }
        exportService.close();
        menuBar.close();
        fractalView.close();
    }
}
