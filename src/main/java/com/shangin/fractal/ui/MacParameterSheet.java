package com.shangin.fractal.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.stage.Window;
import javafx.scene.Node;

import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** An asynchronous AppKit sheet; its owner and callback live only for the session. */
abstract class MacParameterSheet<T> implements AutoCloseable {
    private static final Map<Window, MacParameterSheet<?>> ACTIVE = new IdentityHashMap<>();
    private final ArrayList<Runnable> waiting = new ArrayList<>();
    private final Window owner;
    private final Consumer<T> apply;
    private final Node previousFocus;
    private final ChangeListener<Boolean> ownerShowing = (observable, oldValue, showing) -> {
        if (!showing) close();
    };
    private long handle;
    private boolean closed;
    private Runnable onClosed = () -> {};

    static boolean isAvailable() { return MacContextMenu.isAvailable(); }

    final void showNative(String title, String hint, String firstLabel, String secondLabel,
                          String firstId, String secondId, String first, String second,
                          String defaultFirst, String defaultSecond, String error) throws ReflectiveOperationException {
        requireFxThread();
        if (!owner.isShowing()) throw new IllegalStateException("Sheet owner must be showing");
        long window = MacContextMenu.windowHandle(owner);
        handle = open(window, title, hint, firstLabel, secondLabel, firstId, secondId,
                first, second, defaultFirst, defaultSecond, error, 0, "Apply");
        finishOpening();
    }

    final void showSingleFieldNative(String title, String hint, String label, String id,
                                     String error, String confirmTitle) throws ReflectiveOperationException {
        requireFxThread();
        if (!owner.isShowing()) throw new IllegalStateException("Sheet owner must be showing");
        handle = open(MacContextMenu.windowHandle(owner), title, hint, label, "", id, "",
                "", "", "", "", error, 1, confirmTitle);
        finishOpening();
    }

    final void showFormNative(String title, String hint, String firstLabel, String secondLabel,
                              String firstId, String secondId, String first, String second,
                              String error, String confirm, boolean palette) {
        requireFxThread();
        handle = open(MacContextMenu.windowHandle(owner), title, hint, firstLabel, secondLabel,
                firstId, secondId, first, second, "", "", error, palette ? 3 : 2, confirm);
        if (palette) finishModelessOpening(); else finishOpening();
    }

    private void finishModelessOpening() {
        if (handle == 0) throw new IllegalStateException("Cannot create native window");
        owner.showingProperty().addListener(ownerShowing);
    }

    final void showNoticeNative(String title, String message, boolean confirmation, boolean modeless) {
        requireFxThread();
        handle = openNotice(MacContextMenu.windowHandle(owner), title, message, confirmation, modeless);
        if (modeless) finishModelessOpening(); else finishOpening();
    }

    static void closeOwner(Window owner) {
        var active = ACTIVE.get(owner);
        if (active != null) active.close();
    }

    static boolean hasOpenSheet(Window owner) { return ACTIVE.containsKey(owner); }

    /** Alerts wait for an editor rather than stacking native and fallback modal windows. */
    static void whenAvailable(Window owner, Runnable action) {
        requireFxThread();
        if (!owner.isShowing()) return;
        var active = ACTIVE.get(owner);
        if (active == null) action.run();
        else active.waiting.add(() -> whenAvailable(owner, action));
    }

    private void finishOpening() {
        if (handle == 0) throw new IllegalStateException("Cannot create parameter sheet");
        ACTIVE.put(owner, this);
        owner.showingProperty().addListener(ownerShowing);
    }

    MacParameterSheet(Window owner, Consumer<T> apply) {
        this.owner = owner;
        this.apply = apply;
        previousFocus = owner.getScene() == null ? null : owner.getScene().getFocusOwner();
    }

    /** Returns zero for valid drafts, otherwise the one-based index of the invalid field. */
    abstract int invalidField(String first, String second);

    abstract T parse(String first, String second);

    boolean isOpen() { return !closed; }

    void cancelled() {}

    final void onClosed(Runnable action) { onClosed = action; }

    String estimate(String first, String second) { return ""; }

    @SuppressWarnings("unused")
    private void completed(boolean accepted, String first, String second) {
        Platform.runLater(() -> {
            if (closed) return;
            close();
            if (owner.isShowing()) {
                if (accepted) apply.accept(parse(first, second));
                else cancelled();
            }
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
        ACTIVE.remove(owner, this);
        waiting.forEach(Platform::runLater);
        waiting.clear();
        onClosed.run();
        Platform.runLater(() -> {
            if (owner.isShowing() && owner.isFocused() && previousFocus != null
                    && previousFocus.getScene() == owner.getScene() && previousFocus.isVisible()
                    && !previousFocus.isDisabled()) previousFocus.requestFocus();
        });
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX thread required");
    }

    private native long open(long window, String title, String hint, String firstLabel, String secondLabel,
                             String firstId, String secondId, String first, String second,
                             String defaultFirst, String defaultSecond, String error,
                             int kind, String confirmTitle);
    private native long openNotice(long window, String title, String message,
                                   boolean confirmation, boolean modeless);
    private static native void dispose(long handle);
}
