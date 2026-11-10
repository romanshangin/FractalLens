package com.shangin.fractal.ui;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.stage.Window;
import javafx.scene.Node;

import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** An asynchronous AppKit sheet; its owner and callback live only for the session. */
abstract class MacParameterSheet<T> implements AutoCloseable {
    private static final Map<Window, MacParameterSheet<?>> ACTIVE = new IdentityHashMap<>();
    /** ASCII unit separator: not typeable, so it cannot be confused with draft text. */
    static final String THIRD_DRAFT_SEPARATOR = "\u001f";
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
        handle = open(window, title, hint, firstLabel, secondLabel, "", firstId, secondId, "",
                first, second, "", defaultFirst, defaultSecond, error, 0, "Apply");
        finishOpening();
    }

    final void showSingleFieldNative(String title, String hint, String label, String id,
                                     String error, String confirmTitle) throws ReflectiveOperationException {
        requireFxThread();
        if (!owner.isShowing()) throw new IllegalStateException("Sheet owner must be showing");
        handle = open(MacContextMenu.windowHandle(owner), title, hint, label, "", "", id, "", "",
                "", "", "", "", "", error, 1, confirmTitle);
        finishOpening();
    }

    final void showFormNative(String title, String hint, String firstLabel, String secondLabel,
                              String firstId, String secondId, String first, String second,
                              String error, String confirm, boolean palette) {
        requireFxThread();
        handle = open(MacContextMenu.windowHandle(owner), title, hint, firstLabel, secondLabel, "",
                firstId, secondId, "", first, second, "", "", "", error, palette ? 3 : 2, confirm);
        if (palette) finishModelessOpening(); else finishOpening();
    }

    /**
     * A three-field form. Native callbacks still pass two drafts: the second carries the
     * second and third fields joined by {@link #THIRD_DRAFT_SEPARATOR}.
     */
    final void showFormNative(String title, String hint, String firstLabel, String secondLabel,
                              String thirdLabel, String firstId, String secondId, String thirdId,
                              String first, String second, String third, String error, String confirm) {
        requireFxThread();
        handle = open(MacContextMenu.windowHandle(owner), title, hint, firstLabel, secondLabel, thirdLabel,
                firstId, secondId, thirdId, first, second, third, "", "", error, 2, confirm);
        finishOpening();
    }

    /**
     * Sections of labelled pop-up buttons and text fields. Native callbacks pass the
     * comma-separated selected pop-up indices first and the text drafts, joined by
     * {@link #THIRD_DRAFT_SEPARATOR}, second; validation indexes the text fields.
     */
    final void showChoicesNative(String title, String hint, List<SettingsWindow.Field> fields,
                                 int[] defaults, List<String> defaultTexts, String error,
                                 String resetTitle, String confirmTitle) {
        requireFxThread();
        if (!owner.isShowing()) throw new IllegalStateException("Sheet owner must be showing");
        int[] selected = new int[fields.size()];
        int[] choiceDefaults = new int[fields.size()];
        String[] texts = new String[fields.size()];
        String[] textDefaults = new String[fields.size()];
        String[][] choices = new String[fields.size()][];
        int choice = 0;
        int text = 0;
        for (int i = 0; i < fields.size(); i++) {
            texts[i] = "";
            textDefaults[i] = "";
            choices[i] = new String[0];
            switch (fields.get(i)) {
                case SettingsWindow.ChoiceField field -> {
                    choices[i] = field.choices().toArray(String[]::new);
                    selected[i] = field.selected();
                    choiceDefaults[i] = defaults[choice++];
                }
                case SettingsWindow.TextField field -> {
                    texts[i] = field.text();
                    textDefaults[i] = defaultTexts.get(text++);
                }
            }
        }
        handle = openChoices(MacContextMenu.windowHandle(owner), title, hint,
                fields.stream().map(SettingsWindow.Field::section).toArray(String[]::new),
                fields.stream().map(SettingsWindow.Field::label).toArray(String[]::new),
                fields.stream().map(SettingsWindow.Field::id).toArray(String[]::new),
                fields.stream().map(SettingsWindow.Field::hint).toArray(String[]::new),
                choices, selected, choiceDefaults, texts, textDefaults, error, resetTitle, confirmTitle);
        finishOpening();
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

    /**
     * Native Apply and Enter: zero lets the sheet close, otherwise the one-based field to
     * correct stays open with its draft. Forms that apply only on completion just validate.
     */
    int submit(String first, String second) { return invalidField(first, second); }

    /** Whether {@link #submit} already applied, so completion only ends the session. */
    boolean appliesOnSubmit() { return false; }

    boolean isOpen() { return !closed; }

    void cancelled() {}

    final void onClosed(Runnable action) { onClosed = action; }

    String estimate(String first, String second) { return ""; }

    @SuppressWarnings("unused")
    private void completed(boolean accepted, String first, String second) {
        Platform.runLater(() -> {
            if (closed) return;
            close();
            if (owner.isShowing() && !appliesOnSubmit()) {
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

    static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX thread required");
    }

    private native long open(long window, String title, String hint, String firstLabel, String secondLabel,
                             String thirdLabel, String firstId, String secondId, String thirdId,
                             String first, String second, String third,
                             String defaultFirst, String defaultSecond, String error,
                             int kind, String confirmTitle);
    private native long openChoices(long window, String title, String hint, String[] sections,
                                    String[] labels, String[] ids, String[] hints, String[][] choices,
                                    int[] selected, int[] defaults, String[] texts,
                                    String[] defaultTexts, String error, String resetTitle,
                                    String confirmTitle);
    private native long openNotice(long window, String title, String message,
                                   boolean confirmation, boolean modeless);
    private static native void dispose(long handle);
}
