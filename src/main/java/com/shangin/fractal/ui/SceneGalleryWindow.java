package com.shangin.fractal.ui;

import com.shangin.fractal.render.ScenePreviewRenderer;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.SceneGallery;
import javafx.application.Platform;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Interesting Places: the bundled scene catalog grouped by fractal, with previews.
 * On macOS the places are shown in a native AppKit panel ({@link MacGalleryPanel}); elsewhere,
 * or when the bridge is unavailable, in a JavaFX window ({@link JavaFxGalleryPresentation}).
 * Catalog loading and preview rendering run on one background thread; results reach the
 * presentation only through the FX thread and only for the presentation session that asked.
 * Opening a place goes through {@link FractalView#applyScene}, like restoring a bookmark.
 */
final class SceneGalleryWindow implements AutoCloseable {
    static final String TITLE = "Interesting Places";
    static final String MENU_TITLE = "Interesting Places…";
    static final String HINT = "Open a curated place in its fractal. Each place sets its own view, iterations "
            + "and colors; your antialiasing settings and bookmarks are kept. Undo returns to the previous scene.";
    static final String LOAD_ERROR = "The gallery could not be loaded. Restart FractalLens; if this persists, "
            + "reinstall it.";
    static final String PARTIAL_HINT = "Some places could not be loaded and are not shown.";
    static final String PREVIEW_ERROR = "Preview unavailable";
    static final double PREVIEW_WIDTH = 240;
    static final double PREVIEW_HEIGHT = 150;
    private static final System.Logger LOG = System.getLogger(SceneGalleryWindow.class.getName());

    private final Stage owner;
    private final FractalView view;
    private final BooleanSupplier blocked;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("scene-gallery").daemon().factory());
    private final CatalogSource catalogSource;
    private final Supplier<PreviewRenderer> rendererFactory;
    private final PresentationFactory presentationFactory;
    /** Created lazily on the worker thread and closed there after queued work drains. */
    private PreviewRenderer renderer;
    /** Completed previews by entry id and raster size, so another display scale renders anew. */
    private final Map<String, Preview> previews = new HashMap<>();
    private SceneGallery.Catalog catalog;
    private boolean catalogFailed;
    private boolean catalogLoading;
    private Presentation presentation;
    /** Identifies the shown presentation; hiding or closing it discards pending results. */
    private volatile long session;
    private volatile boolean closed;

    /** Supplies the catalog on the gallery thread. */
    @FunctionalInterface
    interface CatalogSource {
        SceneGallery.Catalog load() throws IOException;
    }

    /** Renders one preview on the gallery thread; returns {@code null} when cancelled. */
    interface PreviewRenderer extends AutoCloseable {
        int[] render(FractalScene scene, int width, int height, BooleanSupplier cancelled)
                throws InterruptedException;

        @Override
        void close();
    }

    /**
     * One shown gallery window; it starts in the loading state. All calls run on the FX
     * thread. After {@link #close} the window may still report {@link Listener#hidden};
     * the gallery ignores callbacks from a presentation it has already dismissed.
     */
    interface Presentation {
        void showError(String message);

        void showCatalog(SceneGallery.Catalog catalog);

        void showPreview(SceneGallery.Entry entry, int[] pixels, int width, int height);

        void showPreviewFailed(SceneGallery.Entry entry);

        /** The display scale of the gallery window itself. */
        double outputScale();

        boolean isShowing();

        void toFront();

        void close();
    }

    /** What a presentation reports, on the FX thread. */
    interface Listener {
        void open(String id);

        /** The user closed the window. */
        void hidden();
    }

    /** Shows a new presentation over the owner. */
    @FunctionalInterface
    interface PresentationFactory {
        Presentation show(Stage owner, Listener listener);
    }

    private record Preview(int[] pixels, int width, int height) {}

    SceneGalleryWindow(Stage owner, FractalView view, BooleanSupplier blocked) {
        this(owner, view, blocked, SceneGallery::loadBundled, SceneGalleryWindow::cpuPreviewRenderer,
                SceneGalleryWindow::platformPresentation);
    }

    /** Tests substitute the catalog, preview renderer and presentation; production uses the defaults. */
    SceneGalleryWindow(Stage owner, FractalView view, BooleanSupplier blocked, CatalogSource catalogSource,
                       Supplier<PreviewRenderer> rendererFactory, PresentationFactory presentationFactory) {
        this.owner = owner;
        this.view = view;
        this.blocked = blocked;
        this.catalogSource = catalogSource;
        this.rendererFactory = rendererFactory;
        this.presentationFactory = presentationFactory;
    }

    /** The JavaFX window with the gallery window's real display scale. */
    static Presentation javaFxPresentation(Stage owner, Listener listener) {
        return new JavaFxGalleryPresentation(owner, listener,
                window -> Math.max(window.getOutputScaleX(), window.getOutputScaleY()));
    }

    private static Presentation platformPresentation(Stage owner, Listener listener) {
        return platformPresentation(owner, listener, MacGalleryPanel.isAvailable(), MacGalleryPanel::show);
    }

    /** The native panel when available and creatable, otherwise the JavaFX window. */
    static Presentation platformPresentation(Stage owner, Listener listener, boolean nativeAvailable,
                                             PresentationFactory nativePresentation) {
        if (nativeAvailable) {
            try {
                return nativePresentation.show(owner, listener);
            } catch (LinkageError | RuntimeException error) {
                LOG.log(System.Logger.Level.WARNING, "Native gallery panel unavailable; using JavaFX window", error);
            }
        }
        return javaFxPresentation(owner, listener);
    }

    /** Previews from the CPU render backends. */
    static PreviewRenderer cpuPreviewRenderer() {
        ScenePreviewRenderer renderer = new ScenePreviewRenderer();
        return new PreviewRenderer() {
            @Override
            public int[] render(FractalScene scene, int width, int height, BooleanSupplier cancelled)
                    throws InterruptedException {
                return renderer.render(scene, width, height, cancelled);
            }

            @Override
            public void close() {
                renderer.close();
            }
        };
    }

    boolean isShowing() { return presentation != null && presentation.isShowing(); }

    /** The shown presentation, for tests. */
    Presentation presentation() { return presentation; }

    /** The JavaFX window when that presentation is shown; {@code null} otherwise. */
    Dialog<Void> dialog() {
        return presentation instanceof JavaFxGalleryPresentation javaFx ? javaFx.dialog() : null;
    }

    /** Whether the catalog has loaded, so places can be opened by id. */
    boolean hasCatalog() { return catalog != null; }

    void show() {
        if (closed || blocked.getAsBoolean()) return;
        if (isShowing()) {
            presentation.toFront();
            return;
        }
        long current = ++session;
        presentation = presentationFactory.show(owner, new Listener() {
            @Override public void open(String id) {
                if (session == current) SceneGalleryWindow.this.open(id);
            }

            @Override public void hidden() {
                if (session != current) return;
                presentation = null;
                session++;
            }
        });
        if (catalogFailed) presentation.showError(LOAD_ERROR);
        else if (catalog != null) showCatalog(current);
        else if (!catalogLoading) loadCatalog();
    }

    private void loadCatalog() {
        catalogLoading = true;
        worker.execute(() -> {
            SceneGallery.Catalog loaded = null;
            try {
                loaded = catalogSource.load();
                for (String problem : loaded.problems()) {
                    LOG.log(System.Logger.Level.WARNING, "Skipped gallery entry. " + problem);
                }
            } catch (IOException | RuntimeException failure) {
                LOG.log(System.Logger.Level.ERROR, "Gallery catalog unavailable", failure);
            }
            SceneGallery.Catalog result = loaded;
            Platform.runLater(() -> {
                if (closed) return;
                catalogLoading = false;
                catalog = result;
                catalogFailed = result == null;
                if (!isShowing()) return;
                if (catalogFailed) presentation.showError(LOAD_ERROR);
                else showCatalog(session);
            });
        });
    }

    private void showCatalog(long current) {
        presentation.showCatalog(catalog);
        int[] size = previewSize();
        for (SceneGallery.Entry entry : catalog.entries()) {
            Preview cached = previews.get(previewKey(entry, size));
            if (cached != null) presentation.showPreview(entry, cached.pixels(), cached.width(), cached.height());
            else requestPreview(entry, current, size);
        }
    }

    /** Physical preview size on the gallery window's display, from 1x to 2x. */
    private int[] previewSize() {
        double scale = Math.clamp(presentation.outputScale(), 1.0, 2.0);
        return new int[] {(int) Math.round(PREVIEW_WIDTH * scale), (int) Math.round(PREVIEW_HEIGHT * scale)};
    }

    private static String previewKey(SceneGallery.Entry entry, int[] size) {
        return entry.id() + "@" + size[0] + "x" + size[1];
    }

    private void requestPreview(SceneGallery.Entry entry, long requested, int[] size) {
        int width = size[0];
        int height = size[1];
        worker.execute(() -> {
            if (session != requested || closed) return;
            int[] pixels;
            try {
                if (renderer == null) renderer = rendererFactory.get();
                pixels = renderer.render(entry.scene(), width, height, () -> session != requested || closed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Gallery preview failed for " + entry.id(), failure);
                Platform.runLater(() -> {
                    if (session == requested && !closed && presentation != null) presentation.showPreviewFailed(entry);
                });
                return;
            }
            if (pixels == null) return;
            Platform.runLater(() -> {
                if (closed) return;
                // A completed preview stays valid for later sessions; publish only to the requester.
                previews.put(previewKey(entry, size), new Preview(pixels, width, height));
                if (session == requested && presentation != null) presentation.showPreview(entry, pixels, width, height);
            });
        });
    }

    /** Opens the place with this id, as a click on its card does. */
    void open(String id) {
        if (closed || blocked.getAsBoolean() || catalog == null) return;
        SceneGallery.Entry entry = catalog.entries().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
        if (entry == null) return;
        try {
            view.applyScene(entry.sceneWith(view.sceneSnapshot().antialiasing()));
        } catch (IllegalArgumentException unavailable) {
            FractalDialogs.message(owner, "Place unavailable", unavailable.getMessage());
            return;
        }
        dismiss();
    }

    private void dismiss() {
        Presentation shown = presentation;
        presentation = null;
        session++;
        if (shown != null) shown.close();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        dismiss();
        // Close the renderer only after queued previews observe cancellation and finish.
        worker.execute(() -> {
            if (renderer != null) renderer.close();
        });
        worker.shutdown();
    }
}
