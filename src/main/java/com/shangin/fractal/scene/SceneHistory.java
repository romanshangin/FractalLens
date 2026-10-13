package com.shangin.fractal.scene;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Bounded session history of complete scenes, independent of render targets. */
public final class SceneHistory {
    private static final int LIMIT = 100;
    private final Deque<FractalScene> undo = new ArrayDeque<>();
    private final Deque<FractalScene> redo = new ArrayDeque<>();
    private FractalScene current;

    public SceneHistory(FractalScene initial) {
        current = Objects.requireNonNull(initial);
    }

    public void record(FractalScene next) {
        Objects.requireNonNull(next);
        if (current.equals(next)) return;
        undo.addLast(current);
        if (undo.size() > LIMIT) undo.removeFirst();
        redo.clear();
        current = next;
    }

    /** Resize and palette animation update the current scene without adding an action. */
    public void replaceCurrent(FractalScene next) {
        current = Objects.requireNonNull(next);
    }

    public FractalScene current() { return current; }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public FractalScene undo() {
        if (canUndo()) {
            redo.addLast(current);
            current = undo.removeLast();
        }
        return current;
    }

    public FractalScene redo() {
        if (canRedo()) {
            undo.addLast(current);
            current = redo.removeLast();
        }
        return current;
    }
}
