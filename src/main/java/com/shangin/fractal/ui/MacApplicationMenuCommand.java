package com.shangin.fractal.ui;

import javafx.application.Platform;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A command in the system-owned macOS application menu, such as Settings… (⌘,).
 *
 * <p>JavaFX's public MenuBar API cannot add items to that first menu, so the AppKit bridge
 * inserts a native item and separator before the Hide commands. Each {@link MainMenuBar}
 * attaches its action and window; the item runs the action of the focused owner window,
 * otherwise of the most recently attached owner, and is removed with the last owner.
 * Elsewhere, without the bridge, or before the application menu exists, {@link #attach}
 * returns {@code null} and the caller keeps the command in its own menus.
 */
final class MacApplicationMenuCommand {
    private static final List<MacApplicationMenuCommand> OWNERS = new ArrayList<>();
    private static long handle;

    private final Window owner;
    private final Runnable action;

    private MacApplicationMenuCommand(Window owner, Runnable action) {
        this.owner = owner;
        this.action = action;
    }

    /**
     * Routes the application menu command {@code title} with ⌘{@code keyEquivalent} to
     * {@code action}, installing it for the first owner; returns the handle to detach, or
     * {@code null} when the command cannot be placed in the application menu.
     */
    static MacApplicationMenuCommand attach(String title, String keyEquivalent, Window owner, Runnable action) {
        MacParameterSheet.requireFxThread();
        Objects.requireNonNull(title);
        Objects.requireNonNull(keyEquivalent);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(action);
        if (!MacContextMenu.isAvailable()) return null;
        if (handle == 0) {
            try {
                handle = install(title, keyEquivalent);
            } catch (LinkageError | RuntimeException error) {
                System.getLogger(MacApplicationMenuCommand.class.getName()).log(System.Logger.Level.WARNING,
                        "Cannot add to the macOS application menu; keeping the command in the window menus", error);
                return null;
            }
            if (handle == 0) return null;
        }
        var command = new MacApplicationMenuCommand(owner, action);
        OWNERS.add(command);
        return command;
    }

    /** Stops routing to this owner; the native item is removed with its last owner. Idempotent. */
    void detach() {
        MacParameterSheet.requireFxThread();
        if (!OWNERS.remove(this) || !OWNERS.isEmpty() || handle == 0) return;
        long installed = handle;
        handle = 0;
        remove(installed);
    }

    // Called by AppKit on its main thread, which is the FX thread on macOS. Never run the
    // command inside native menu tracking or key-equivalent handling.
    @SuppressWarnings("unused")
    private static void activated() {
        Platform.runLater(() -> {
            if (OWNERS.isEmpty()) return;
            OWNERS.stream().filter(command -> command.owner.isFocused()).findFirst()
                    .orElse(OWNERS.getLast()).action.run();
        });
    }

    private static native long install(String title, String keyEquivalent);
    private static native void remove(long handle);
}
