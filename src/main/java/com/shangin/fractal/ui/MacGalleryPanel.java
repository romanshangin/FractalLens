package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.SceneGallery;
import javafx.application.Platform;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The native AppKit Interesting Places panel: a nonmodal child panel with a collection
 * view of places grouped by fractal. Java keeps the catalog, preview rendering and opening
 * a place; AppKit callbacks reach the listener through the FX event queue.
 */
final class MacGalleryPanel implements SceneGalleryWindow.Presentation {
    private final SceneGalleryWindow.Listener listener;
    private long handle;
    private boolean disposed;

    static boolean isAvailable() { return MacContextMenu.isAvailable(); }

    static MacGalleryPanel show(Window owner, SceneGalleryWindow.Listener listener) {
        MacParameterSheet.requireFxThread();
        if (!owner.isShowing()) throw new IllegalStateException("Gallery owner must be showing");
        var panel = new MacGalleryPanel(listener);
        panel.handle = panel.open(MacContextMenu.windowHandle(owner), SceneGalleryWindow.TITLE,
                SceneGalleryWindow.HINT, "Close", "Loading gallery", SceneGalleryWindow.PREVIEW_ERROR);
        if (panel.handle == 0) throw new IllegalStateException("Cannot create native gallery panel");
        return panel;
    }

    private MacGalleryPanel(SceneGalleryWindow.Listener listener) { this.listener = listener; }

    @Override
    public void showError(String message) {
        if (!disposed) showError(handle, message);
    }

    @Override
    public void showCatalog(SceneGallery.Catalog catalog) {
        if (disposed) return;
        var sections = new ArrayList<String>();
        var sizes = new ArrayList<Integer>();
        var entries = new ArrayList<SceneGallery.Entry>();
        for (Map.Entry<FractalPreset, List<SceneGallery.Entry>> group : catalog.groups().entrySet()) {
            sections.add(group.getKey().toString());
            sizes.add(group.getValue().size());
            entries.addAll(group.getValue());
        }
        showEntries(handle, catalog.problems().isEmpty() ? "" : SceneGalleryWindow.PARTIAL_HINT,
                sections.toArray(String[]::new), sizes.stream().mapToInt(Integer::intValue).toArray(),
                entries.stream().map(SceneGallery.Entry::id).toArray(String[]::new),
                entries.stream().map(SceneGallery.Entry::name).toArray(String[]::new),
                entries.stream().map(SceneGallery.Entry::description).toArray(String[]::new));
    }

    @Override
    public void showPreview(SceneGallery.Entry entry, int[] pixels, int width, int height) {
        if (!disposed) setPreview(handle, entry.id(), pixels, width, height);
    }

    @Override
    public void showPreviewFailed(SceneGallery.Entry entry) {
        if (!disposed) setPreviewFailed(handle, entry.id());
    }

    @Override
    public double outputScale() { return disposed ? 1 : backingScale(handle); }

    @Override
    public boolean isShowing() { return !disposed; }

    @Override
    public void toFront() {
        if (!disposed) toFront(handle);
    }

    /** Ends the panel without a callback; idempotent. */
    @Override
    public void close() {
        MacParameterSheet.requireFxThread();
        if (disposed) return;
        disposed = true;
        dispose(handle);
        handle = 0;
    }

    @SuppressWarnings("unused") // JNI: Return, Enter or a double click chose a place.
    private void opened(String id) {
        Platform.runLater(() -> {
            if (!disposed) listener.open(id);
        });
    }

    @SuppressWarnings("unused") // JNI: Close, Escape or the window close button ended the panel.
    private void closed() {
        Platform.runLater(() -> {
            if (disposed) return;
            close();
            listener.hidden();
        });
    }

    private native long open(long window, String title, String hint, String closeTitle,
                             String loadingText, String failureText);
    private static native void showError(long handle, String message);
    private static native void showEntries(long handle, String notice, String[] sections, int[] sectionSizes,
                                           String[] ids, String[] names, String[] descriptions);
    private static native void setPreview(long handle, String id, int[] pixels, int width, int height);
    private static native void setPreviewFailed(long handle, String id);
    private static native double backingScale(long handle);
    private static native void toFront(long handle);
    private static native void dispose(long handle);
}
