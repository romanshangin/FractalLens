package com.shangin.fractal.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.stage.Window;

import java.util.function.Consumer;

/** An asynchronous AppKit sheet; its owner and callback live only for the session. */
abstract class MacParameterSheet<T> implements AutoCloseable {
    private final Window owner;
    private final Consumer<T> apply;
    private final ChangeListener<Boolean> ownerShowing = (observable, oldValue, showing) -> {
        if (!showing) close();
    };
    private long handle;
    private boolean closed;

    static boolean isAvailable() { return MacContextMenu.isAvailable(); }

    final void showNative(String title, String hint, String firstLabel, String secondLabel,
                          String firstId, String secondId, String first, String second,
                          String defaultFirst, String defaultSecond, String error) throws ReflectiveOperationException {
        requireFxThread();
        if (!owner.isShowing()) throw new IllegalStateException("Sheet owner must be showing");
        long window = MacContextMenu.windowHandle(owner);
        handle = open(window, title, hint, firstLabel, secondLabel, firstId, secondId,
                first, second, defaultFirst, defaultSecond, error);
        if (handle == 0) throw new IllegalStateException("Cannot create parameter sheet");
        owner.showingProperty().addListener(ownerShowing);
    }

    MacParameterSheet(Window owner, Consumer<T> apply) {
        this.owner = owner;
        this.apply = apply;
    }

    /** Returns 0 for valid drafts, 1 for an invalid first field, or 2 for the second. */
    abstract int invalidField(String first, String second);

    abstract T parse(String first, String second);

    boolean isOpen() { return !closed; }

    @SuppressWarnings("unused")
    private void completed(boolean accepted, String first, String second) {
        Platform.runLater(() -> {
            if (closed) return;
            close();
            if (accepted && owner.isShowing()) apply.accept(parse(first, second));
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

    private native long open(long window, String title, String hint, String firstLabel, String secondLabel,
                             String firstId, String secondId, String first, String second,
                             String defaultFirst, String defaultSecond, String error);
    private static native void dispose(long handle);
}
