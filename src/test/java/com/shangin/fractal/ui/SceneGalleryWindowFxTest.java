package com.shangin.fractal.ui;

import com.shangin.fractal.app.BookmarkStore;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.SceneGallery;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Menu;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class SceneGalleryWindowFxTest {
    @TempDir
    Path temporaryDirectory;

    private Stage stage;
    private FractalView view;
    private MainMenuBar menu;
    /** The JavaFX presentation; the native panel has its own test. */
    private SceneGalleryWindow gallery;
    private final AtomicBoolean rendering = new AtomicBoolean(true);
    private final AtomicBoolean deepZoom = new AtomicBoolean();

    @BeforeAll
    static void startToolkit() throws InterruptedException {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopToolkit() {
        Platform.exit();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    /** Waits for a concrete FX-thread state rather than a fixed delay. */
    private static void awaitFx(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!fx(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + description);
            Thread.sleep(20);
        }
    }

    private void open(Path bookmarks) throws Exception {
        fx(() -> {
            stage = new Stage();
            view = new FractalView(FractalPreset.MANDELBROT, PalettePreset.values()[0], false);
            menu = new MainMenuBar(stage, view, FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.values()[0]),
                    () -> {}, new BookmarkStore(bookmarks));
            // Replaces the menu's own observers; these tests only track render state.
            view.setOnRenderingChanged(rendering::set);
            view.setOnDeepZoomChanged(deepZoom::set);
            var root = new BorderPane(view);
            root.setTop(menu);
            stage.setScene(new Scene(root, 640, 420));
            stage.show();
            gallery = new SceneGalleryWindow(stage, view, () -> false, SceneGallery::loadBundled,
                    SceneGalleryWindow::cpuPreviewRenderer, SceneGalleryWindow::javaFxPresentation);
            return null;
        });
        awaitFx("initial render", () -> !rendering.get());
    }

    private void closeWindow() throws Exception {
        fx(() -> {
            gallery.close();
            menu.close();
            view.close();
            stage.close();
            return null;
        });
    }

    private Node node(String selector) {
        var dialog = gallery.dialog();
        return dialog == null ? null : dialog.getDialogPane().lookup(selector);
    }

    private boolean previewsReady() {
        var dialog = gallery.dialog();
        if (dialog == null) return false;
        var previews = dialog.getDialogPane().lookupAll(".gallery-preview");
        return !previews.isEmpty() && previews.stream().allMatch(preview ->
                ((StackPane) preview).getChildren().stream().allMatch(ImageView.class::isInstance));
    }

    @Test
    void opensPlacesThroughSceneHistoryWithoutTouchingBookmarks() throws Exception {
        Path bookmarks = temporaryDirectory.resolve("bookmarks");
        open(bookmarks);
        try {
            var catalog = SceneGallery.loadBundled();
            var rabbit = catalog.entries().stream()
                    .filter(entry -> entry.id().equals("julia-douady-rabbit")).findFirst().orElseThrow();
            var viewerModes = new AntialiasSettings(InteractiveRenderMode.BASE_ONLY, InteractiveRenderMode.FAST);
            FractalScene before = fx(() -> {
                view.setAntialiasSettings(viewerModes);
                Menu go = menu.getMenus().stream().filter(m -> m.getText().equals("Go to")).findFirst().orElseThrow();
                assertEquals(SceneGalleryWindow.MENU_TITLE, go.getItems().getFirst().getText());
                gallery.show();
                assertTrue(gallery.isShowing());
                assertEquals(SceneGalleryWindow.TITLE, gallery.dialog().getTitle());
                return view.sceneSnapshot();
            });
            awaitFx("catalog cards", () -> node("#gallery-julia-douady-rabbit") != null);
            fx(() -> {
                var pane = gallery.dialog().getDialogPane();
                assertEquals(catalog.entries().size(), pane.lookupAll(".gallery-card").size());
                assertEquals(catalog.groups().size(), pane.lookupAll(".dialog-section").size());
                assertTrue(pane.lookup("#scene-gallery-error") == null);
                // Enter opens the focused place; Close must not be the default button.
                assertFalse(((Button) pane.lookupButton(ButtonType.CLOSE)).isDefaultButton());
                return null;
            });
            awaitFx("all previews", this::previewsReady);

            fx(() -> {
                ((Button) node("#gallery-julia-douady-rabbit")).fire();
                assertFalse(gallery.isShowing());
                FractalScene applied = view.sceneSnapshot();
                assertEquals(rabbit.sceneWith(viewerModes), applied);
                assertEquals(rabbit.scene().juliaParameters(), applied.juliaParameters());
                return null;
            });
            awaitFx("Julia render", () -> !rendering.get());
            fx(() -> {
                assertEquals(rabbit.sceneWith(viewerModes), view.sceneSnapshot());
                assertTrue(view.hasCompletedFrame());
                view.undoScene();
                assertEquals(before, view.sceneSnapshot());
                view.redoScene();
                assertEquals(rabbit.sceneWith(viewerModes), view.sceneSnapshot());
                return null;
            });
            awaitFx("redo render", () -> !rendering.get());
            assertFalse(Files.exists(bookmarks), "Opening a gallery place must not create bookmarks");

            // Previews are kept for the session: reopening shows them without rendering again.
            fx(() -> {
                gallery.show();
                return null;
            });
            awaitFx("reopened cards", () -> node("#gallery-julia-douady-rabbit") != null);
            assertTrue(fx(this::previewsReady));
            fx(() -> {
                var pane = gallery.dialog().getDialogPane();
                assertTrue(pane.lookupAll(".gallery-preview").stream().noneMatch(preview ->
                        ((StackPane) preview).getChildren().stream().anyMatch(ProgressIndicator.class::isInstance)));
                ((Button) pane.lookupButton(ButtonType.CLOSE)).fire();
                assertFalse(gallery.isShowing());
                return null;
            });
        } finally {
            closeWindow();
        }
    }

    @Test
    void deepZoomPlaceRendersWithHighPrecision() throws Exception {
        open(temporaryDirectory.resolve("bookmarks"));
        try {
            var entry = SceneGallery.loadBundled().entries().stream()
                    .filter(candidate -> candidate.id().equals("mandelbrot-misiurewicz-i")).findFirst().orElseThrow();
            fx(() -> {
                gallery.show();
                return null;
            });
            awaitFx("deep card", () -> node("#gallery-mandelbrot-misiurewicz-i") != null);
            FractalScene before = fx(() -> {
                FractalScene previous = view.sceneSnapshot();
                ((Button) node("#gallery-mandelbrot-misiurewicz-i")).fire();
                return previous;
            });
            awaitFx("deep render", () -> !rendering.get() && view.hasCompletedFrame());
            fx(() -> {
                assertTrue(deepZoom.get());
                assertEquals(entry.scene().viewport(), view.sceneSnapshot().viewport());
                assertNotEquals(before, view.sceneSnapshot());
                return null;
            });
        } finally {
            closeWindow();
        }
    }

    @Test
    void closingTheGalleryEndsItsWindowForGood() throws Exception {
        open(temporaryDirectory.resolve("bookmarks"));
        try {
            fx(() -> {
                gallery.show();
                assertTrue(gallery.isShowing());
                assertNotNull(gallery.dialog());
                return null;
            });
        } finally {
            closeWindow();
        }
        fx(() -> {
            assertFalse(gallery.isShowing());
            // A closed gallery never reopens.
            gallery.show();
            assertFalse(gallery.isShowing());
            return null;
        });
    }
}
