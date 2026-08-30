package com.shangin.fractal.controller;

import java.util.Objects;
import java.util.function.Consumer;

/** Tracks the latest render generation and publishes only real busy-state changes. */
final class RenderActivityTracker {

    private Consumer<Boolean> listener = ignored -> {};
    private long generation;
    private boolean active;

    long begin() {
        generation++;
        setActive(true);
        return generation;
    }

    void finish(long renderGeneration) {
        if (renderGeneration == generation) {
            setActive(false);
        }
    }

    void cancel() {
        generation++;
        setActive(false);
    }

    void setListener(Consumer<Boolean> listener) {
        this.listener = Objects.requireNonNull(listener);
        listener.accept(active);
    }

    private void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        listener.accept(active);
    }
}
