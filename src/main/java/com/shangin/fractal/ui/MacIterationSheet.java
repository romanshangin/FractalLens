package com.shangin.fractal.ui;

import com.shangin.fractal.scene.IterationSettings;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.stage.Window;

import java.util.function.Consumer;

/** An asynchronous AppKit sheet; its owner and callback live only for the session. */
final class MacIterationSheet implements AutoCloseable {
    private final Window owner;
    private final Consumer<IterationSettings> apply;
    private final ChangeListener<Boolean> ownerShowing = (observable, oldValue, showing) -> {
        if (!showing) close();
    };
    private long handle;
    private boolean closed;

    static boolean isAvailable() { return MacContextMenu.isAvailable(); }

    static MacIterationSheet show(Window owner, IterationSettings current,
                                  Consumer<IterationSettings> apply) throws ReflectiveOperationException {
        requireFxThread();
        if (!owner.isShowing()) throw new IllegalStateException("Sheet owner must be showing");
        Object peer = Class.forName("com.sun.javafx.stage.WindowHelper")
                .getMethod("getPeer", Window.class).invoke(null, owner);
        long window = (long) Class.forName("com.sun.javafx.tk.TKStage")
                .getMethod("getRawHandle").invoke(peer);
        if (window == 0) throw new IllegalStateException("Window has no native handle");
        var session = new MacIterationSheet(owner, apply);
        session.handle = session.open(window, current.baseIterations(), current.iterationsPerZoomLevel(),
                IterationSettings.DEFAULT_BASE_ITERATIONS, IterationSettings.DEFAULT_ITERATIONS_PER_ZOOM_LEVEL);
        if (session.handle == 0) throw new IllegalStateException("Cannot create iteration sheet");
        owner.showingProperty().addListener(session.ownerShowing);
        return session;
    }

    private MacIterationSheet(Window owner, Consumer<IterationSettings> apply) {
        this.owner = owner;
        this.apply = apply;
    }

    boolean isOpen() { return !closed; }

    @SuppressWarnings("unused")
    private void completed(boolean accepted, int base, int zoom) {
        Platform.runLater(() -> {
            if (closed) return;
            close();
            if (accepted && owner.isShowing()) apply.accept(new IterationSettings(base, zoom));
        });
    }

    @Override public void close() {
        requireFxThread();
        if (closed) return;
        closed = true;
        owner.showingProperty().removeListener(ownerShowing);
        if (handle != 0) {
            dispose(handle);
            handle = 0;
        }
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX thread required");
    }

    private native long open(long window, int base, int zoom, int defaultBase, int defaultZoom);
    private static native void dispose(long handle);
}
