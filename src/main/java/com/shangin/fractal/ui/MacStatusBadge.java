package com.shangin.fractal.ui;

import javafx.application.Platform;
import javafx.stage.Window;

/**
 * AppKit presentation of the render status badge: Liquid Glass on macOS 26 and
 * later, a within-window visual effect material on earlier releases. The view is
 * owned by the window's content view; all calls and disposal belong to the FX thread.
 */
final class MacStatusBadge implements AutoCloseable {
    private long handle;

    private MacStatusBadge(long handle) { this.handle = handle; }

    static MacStatusBadge show(Window window) {
        requireFxThread();
        long created = create(MacContextMenu.windowHandle(window));
        if (created == 0) throw new IllegalStateException("Cannot create native status badge");
        return new MacStatusBadge(created);
    }

    void update(String text, String widestText, String tooltip, String accessibleText,
                int rgb, boolean rendering) {
        requireFxThread();
        if (handle != 0) update(handle, text, widestText, tooltip, accessibleText, rgb, rendering);
    }

    /** Margins are logical scene points from the window's left and bottom content edges. */
    void place(double left, double bottom) {
        requireFxThread();
        if (handle != 0) place(handle, left, bottom);
    }

    void raise() {
        requireFxThread();
        if (handle != 0) raise(handle);
    }

    /** Native state for tests: topmost (1/0), x, bottom, width and height in content-view points. */
    double[] state() {
        requireFxThread();
        if (handle == 0) throw new IllegalStateException("Native status badge is closed");
        return state(handle);
    }

    @Override public void close() {
        requireFxThread();
        if (handle != 0) {
            dispose(handle);
            handle = 0;
        }
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX thread required");
    }

    private static native long create(long window);
    private static native void update(long handle, String text, String widestText, String tooltip,
                                      String accessibleText, int rgb, boolean rendering);
    private static native void place(long handle, double left, double bottom);
    private static native void raise(long handle);
    private static native double[] state(long handle);
    private static native void dispose(long handle);
}
