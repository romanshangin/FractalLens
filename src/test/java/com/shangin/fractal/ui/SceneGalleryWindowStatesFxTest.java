package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.SceneGallery;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Window states with a substituted catalog and a preview renderer controlled by each test. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class SceneGalleryWindowStatesFxTest {
    private static final String CATALOG = """
            {"catalogVersion": 1, "entries": [
              {"id": "first", "name": "First", "description": "Renders.", "fractal": "MANDELBROT",
               "viewport": {"centerReal": "-0.75", "centerImaginary": "0.1", "scale": "0.15"},
               "coloring": {"palette": "ICE"}},
              {"id": "second", "name": "Second", "description": "Renders too.", "fractal": "TRICORN",
               "viewport": {"centerReal": "-1.25", "centerImaginary": "0", "scale": "0.625"},
               "coloring": {"palette": "FIRE"}},
              {"id": "too-deep", "name": "Too Deep", "description": "Beyond Tricorn precision.",
               "fractal": "TRICORN",
               "viewport": {"centerReal": "-0.1", "centerImaginary": "-0.45", "scale": "1e-30"},
               "coloring": {"palette": "ICE"}},
              {"id": "deep-trap", "name": "Deep Trap", "description": "Deep Mandelbrot has no orbit traps.",
               "fractal": "MANDELBROT",
               "viewport": {"centerReal": "0", "centerImaginary": "1", "scale": "2.4e-20"},
               "coloring": {"palette": "ICE", "orbitTrap": "POINT"}},
              {"id": "broken", "name": "Broken", "description": "Unknown palette.", "fractal": "TRICORN",
               "viewport": {"centerReal": "0", "centerImaginary": "0", "scale": "1"},
               "coloring": {"palette": "NEON"}}
            ]}""";

    private Stage stage;
    private FractalView view;
    private SceneGalleryWindow gallery;
    private final AtomicBoolean rendering = new AtomicBoolean(true);
    private final java.util.concurrent.atomic.AtomicReference<Double> scale =
            new java.util.concurrent.atomic.AtomicReference<>(1.0);
    private final java.util.concurrent.atomic.AtomicReference<javafx.stage.Window> scaleWindow =
            new java.util.concurrent.atomic.AtomicReference<>();

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

    private static void fxRun(Runnable action) throws Exception {
        fx(() -> {
            action.run();
            return null;
        });
    }

    /** Waits for a concrete FX-thread state rather than a fixed delay. */
    private static void awaitFx(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!fx(condition::getAsBoolean)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + description);
            Thread.sleep(20);
        }
    }

    private static void await(String description, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for " + description);
            Thread.sleep(10);
        }
    }

    @BeforeEach
    void openMainWindow() throws Exception {
        fxRun(() -> {
            stage = new Stage();
            view = new FractalView(FractalPreset.MANDELBROT, PalettePreset.values()[0], false);
            view.setOnRenderingChanged(rendering::set);
            stage.setScene(new Scene(new BorderPane(view), 640, 420));
            stage.show();
        });
        awaitFx("initial render", () -> !rendering.get());
    }

    @AfterEach
    void closeMainWindow() throws Exception {
        fxRun(() -> {
            if (gallery != null) gallery.close();
            FractalDialogs.close(stage);
            view.close();
            stage.close();
        });
    }

    private void createGallery(SceneGalleryWindow.CatalogSource source, ControlledRenderer renderer)
            throws Exception {
        fxRun(() -> gallery = new SceneGalleryWindow(stage, view, () -> false, source, () -> renderer,
                (owner, listener) -> new JavaFxGalleryPresentation(owner, listener, window -> {
                    scaleWindow.set(window);
                    return scale.get();
                })));
    }

    private Node node(String selector) {
        var dialog = gallery.dialog();
        return dialog == null ? null : dialog.getDialogPane().lookup(selector);
    }

    private StackPane preview(String id) {
        Node card = node("#gallery-" + id);
        return card == null ? null : (StackPane) card.lookup(".gallery-preview");
    }

    private static boolean shows(StackPane preview, Class<?> type) {
        return preview != null && preview.getChildren().size() == 1 && type.isInstance(preview.getChildren().getFirst());
    }

    private boolean hasLabel(String text) {
        return gallery.dialog().getDialogPane().lookupAll(".label").stream()
                .anyMatch(label -> label instanceof Label value && text.equals(value.getText()));
    }

    @Test
    void showsLoadingUntilTheCatalogArrivesThenReportsSkippedEntries() throws Exception {
        var release = new CountDownLatch(1);
        createGallery(() -> {
            try {
                assertTrue(release.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return SceneGallery.read(CATALOG);
        }, ControlledRenderer.immediate());
        fxRun(() -> gallery.show());
        fxRun(() -> {
            assertTrue(gallery.isShowing());
            assertInstanceOf(ProgressIndicator.class, node("#scene-gallery-loading"));
            assertNull(node(".gallery-card"));
        });
        release.countDown();
        awaitFx("catalog cards", () -> node("#gallery-first") != null);
        fxRun(() -> {
            assertNull(node("#scene-gallery-loading"));
            assertEquals(4, gallery.dialog().getDialogPane().lookupAll(".gallery-card").size());
            assertNull(node("#gallery-broken"));
            assertTrue(hasLabel(SceneGalleryWindow.PARTIAL_HINT));
        });
    }

    @Test
    void catalogFailureShowsAnErrorInsteadOfPlaces() throws Exception {
        createGallery(() -> { throw new IOException("missing"); }, ControlledRenderer.immediate());
        fxRun(() -> gallery.show());
        awaitFx("load error", () -> node("#scene-gallery-error") != null);
        fxRun(() -> {
            assertEquals(SceneGalleryWindow.LOAD_ERROR, ((Label) node("#scene-gallery-error")).getText());
            assertNull(node(".gallery-card"));
            assertNull(node("#scene-gallery-loading"));
            ((Button) gallery.dialog().getDialogPane().lookupButton(ButtonType.CLOSE)).fire();
            assertFalse(gallery.isShowing());
        });
    }

    @Test
    void previewIndicatorIsReplacedByTheImageOrAFailureNotice() throws Exception {
        var renderer = new ControlledRenderer();
        // Only the "second" entry fails: the Tricorn place at a wide scale.
        renderer.fails = scene -> scene.fractal() == FractalPreset.TRICORN && scene.viewport().scale() > 0.1;
        createGallery(() -> SceneGallery.read(CATALOG), renderer);
        fxRun(() -> gallery.show());
        awaitFx("cards", () -> preview("first") != null);
        renderer.awaitStarted(1);
        fxRun(() -> {
            assertTrue(shows(preview("first"), ProgressIndicator.class));
            assertTrue(shows(preview("second"), ProgressIndicator.class));
        });
        renderer.release();
        awaitFx("first image", () -> shows(preview("first"), ImageView.class));
        awaitFx("second failure", () -> shows(preview("second"), Label.class));
        fxRun(() -> {
            Label failure = (Label) preview("second").getChildren().getFirst();
            assertEquals(SceneGalleryWindow.PREVIEW_ERROR, failure.getText());
            assertTrue(failure.getStyleClass().contains("gallery-preview-error"));
        });
    }

    @Test
    void hidingTheWindowCancelsPendingPreviewsAndReopeningRequestsThemAgain() throws Exception {
        var renderer = new ControlledRenderer();
        createGallery(() -> SceneGallery.read(CATALOG), renderer);
        fxRun(() -> gallery.show());
        renderer.awaitStarted(1);
        fxRun(() -> gallery.dialog().close());
        // The running preview observes cancellation; queued ones never start.
        await("cancellation", () -> renderer.cancelledRenders.get() == 1);
        await("worker idle", () -> renderer.active.get() == 0);
        int afterHide = renderer.calls.get();
        assertEquals(1, afterHide);
        fxRun(() -> assertFalse(gallery.isShowing()));

        renderer.release();
        fxRun(() -> gallery.show());
        awaitFx("reopened images", () -> shows(preview("first"), ImageView.class)
                && shows(preview("second"), ImageView.class) && shows(preview("too-deep"), ImageView.class)
                && shows(preview("deep-trap"), ImageView.class));
        assertEquals(afterHide + 4, renderer.calls.get());
    }

    @Test
    void closingDuringAPreviewDropsItsResultAndClosesTheRendererAfterIt() throws Exception {
        var renderer = new ControlledRenderer();
        renderer.ignoreCancellation = true;
        createGallery(() -> SceneGallery.read(CATALOG), renderer);
        fxRun(() -> gallery.show());
        renderer.awaitStarted(1);
        // Cards reach the scene graph with the scroll pane's skin, possibly after the preview starts.
        awaitFx("first card", () -> preview("first") != null);
        StackPane first = fx(() -> preview("first"));
        fxRun(() -> {
            gallery.close();
            assertFalse(gallery.isShowing());
        });
        assertFalse(renderer.closed.get(), "The renderer must outlive the running preview");
        renderer.release();
        await("renderer closed", renderer.closed::get);
        assertTrue(renderer.closedAfterLastRender.get());
        assertEquals(1, renderer.calls.get());
        fxRun(() -> {
            // The late pixels are never shown, and a closed gallery stays closed.
            assertTrue(shows(first, ProgressIndicator.class));
            gallery.show();
            assertFalse(gallery.isShowing());
        });
    }

    @Test
    void placeBeyondPrecisionKeepsTheSceneAndHistory() throws Exception {
        assertUnavailablePlaceKeepsSceneAndHistory("too-deep");
    }

    @Test
    void placeTheRenderersCannotDrawKeepsTheSceneAndHistory() throws Exception {
        // The camera accepts deep Mandelbrot coordinates, but no backend renders them with an orbit trap.
        assertUnavailablePlaceKeepsSceneAndHistory("deep-trap");
    }

    private void assertUnavailablePlaceKeepsSceneAndHistory(String id) throws Exception {
        createGallery(() -> SceneGallery.read(CATALOG), ControlledRenderer.immediate());
        fxRun(() -> gallery.show());
        awaitFx("cards", () -> node("#gallery-" + id) != null);
        fxRun(() -> {
            FractalScene before = view.sceneSnapshot();
            boolean couldUndo = view.canUndo();
            ((Button) node("#gallery-" + id)).fire();
            assertEquals(before, view.sceneSnapshot());
            assertEquals(couldUndo, view.canUndo());
            // The window stays open so another place can be chosen.
            assertTrue(gallery.isShowing());
        });
    }

    /** The window shows its whole preferred content, unless the screen is too small for it. */
    private boolean windowFitsContent() {
        var pane = gallery.dialog().getDialogPane();
        var window = pane.getScene().getWindow();
        var root = pane.getScene().getRoot();
        double wanted = root.prefHeight(root.getLayoutBounds().getWidth());
        var bounds = javafx.stage.Screen.getScreensForRectangle(window.getX(), window.getY(),
                window.getWidth(), window.getHeight()).getFirst().getVisualBounds();
        double sceneHeight = pane.getScene().getHeight();
        boolean onScreen = window.getY() >= bounds.getMinY() - 1
                && window.getY() + window.getHeight() <= bounds.getMaxY() + 1;
        return onScreen && (sceneHeight + 1 >= wanted || window.getHeight() + 1 >= bounds.getHeight());
    }

    @Test
    void windowGrowsToItsCardsOnFirstOpenAndOnReopen() throws Exception {
        var release = new CountDownLatch(1);
        createGallery(() -> {
            try {
                assertTrue(release.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return SceneGallery.read(CATALOG);
        }, ControlledRenderer.immediate());
        fxRun(() -> gallery.show());
        release.countDown();
        awaitFx("cards", () -> node("#gallery-first") != null);
        fxRun(() -> {
            assertTrue(windowFitsContent(), "first open");
            gallery.dialog().close();
            // The catalog is cached now, so reopening shows cards at once.
            gallery.show();
        });
        awaitFx("reopened cards", () -> node("#gallery-first") != null);
        fxRun(() -> assertTrue(windowFitsContent(), "reopen"));
    }

    @Test
    void failedNativePanelFallsBackToTheJavaFxWindow() throws Exception {
        SceneGalleryWindow.PresentationFactory failingNative = (owner, listener) -> {
            throw new IllegalStateException("Cannot create native gallery panel");
        };
        fxRun(() -> gallery = new SceneGalleryWindow(stage, view, () -> false, () -> SceneGallery.read(CATALOG),
                () -> ControlledRenderer.immediate(),
                (owner, listener) -> SceneGalleryWindow.platformPresentation(owner, listener, true, failingNative)));
        fxRun(() -> {
            gallery.show();
            assertInstanceOf(JavaFxGalleryPresentation.class, gallery.presentation());
            assertTrue(gallery.isShowing());
        });
        awaitFx("fallback cards", () -> node("#gallery-first") != null);
        fxRun(() -> {
            ((Button) node("#gallery-first")).fire();
            assertFalse(gallery.isShowing());
            assertEquals(FractalPreset.MANDELBROT, view.sceneSnapshot().fractal());
            assertTrue(view.canUndo());
        });
    }

    @Test
    void previewRastersFollowTheGalleryWindowScale() throws Exception {
        var renderer = ControlledRenderer.immediate();
        createGallery(() -> SceneGallery.read(CATALOG), renderer);
        fxRun(() -> gallery.show());
        awaitFx("1x previews", () -> shows(preview("first"), ImageView.class));
        await("all 1x previews", () -> renderer.calls.get() == 4);
        fxRun(() -> {
            // The scale comes from the gallery's own window, not from the main window.
            assertTrue(scaleWindow.get() == gallery.dialog().getDialogPane().getScene().getWindow());
            assertEquals(240, ((ImageView) preview("first").getChildren().getFirst()).getImage().getWidth());
            gallery.dialog().close();
        });
        assertTrue(renderer.widths.stream().allMatch(width -> width == 240), renderer.widths.toString());

        scale.set(2.0);
        fxRun(() -> gallery.show());
        // Cached 1x previews are not reused on a 2x display.
        await("2x previews", () -> renderer.calls.get() == 8);
        awaitFx("2x image", () -> shows(preview("first"), ImageView.class)
                && ((ImageView) preview("first").getChildren().getFirst()).getImage().getWidth() == 480);
        assertEquals(java.util.List.of(480, 480, 480, 480), renderer.widths.subList(4, 8));
    }

    /** Blocks each preview until released and records cancellation, calls and closing. */
    private static final class ControlledRenderer implements SceneGalleryWindow.PreviewRenderer {
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.List<Integer> widths = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger cancelledRenders = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean closedAfterLastRender = new AtomicBoolean();
        private volatile Predicate<FractalScene> fails = scene -> false;
        private volatile boolean ignoreCancellation;

        static ControlledRenderer immediate() {
            var renderer = new ControlledRenderer();
            renderer.release();
            return renderer;
        }

        void release() { released.countDown(); }

        void awaitStarted(int count) throws InterruptedException {
            await("preview start", () -> calls.get() >= count);
        }

        @Override
        public int[] render(FractalScene scene, int width, int height, BooleanSupplier cancelled)
                throws InterruptedException {
            widths.add(width);
            calls.incrementAndGet();
            active.incrementAndGet();
            try {
                while (!released.await(5, TimeUnit.MILLISECONDS)) {
                    if (!ignoreCancellation && cancelled.getAsBoolean()) {
                        cancelledRenders.incrementAndGet();
                        return null;
                    }
                }
                if (fails.test(scene)) throw new IllegalArgumentException("Preview failed");
                int[] pixels = new int[width * height];
                Arrays.fill(pixels, 0xFF336699);
                return pixels;
            } finally {
                closedAfterLastRender.set(false);
                active.decrementAndGet();
            }
        }

        @Override
        public void close() {
            closedAfterLastRender.set(active.get() == 0);
            closed.set(true);
        }
    }
}
