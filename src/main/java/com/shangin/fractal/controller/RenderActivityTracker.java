package com.shangin.fractal.controller;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Tracks the latest render generation and publishes only real busy-state changes. */
final class RenderActivityTracker {

    private Consumer<Boolean> listener = ignored -> {};
    private final LongSupplier clock;
    private Consumer<RenderStatus> statusListener = ignored -> {};
    private RenderStatus status = new RenderStatus(RenderStatus.State.IDLE, 0);
    private long startedAt;
    private long generation;

    RenderActivityTracker() {
        this(System::nanoTime);
    }

    RenderActivityTracker(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    void setStatusListener(Consumer<RenderStatus> listener) {
        statusListener = Objects.requireNonNull(listener);
        listener.accept(status);
    }

    private void publish(RenderStatus.State state, long elapsed) {
        status = new RenderStatus(state, elapsed);
        statusListener.accept(status);
    }
    private boolean active;

    long begin() {
        generation++;
        startedAt = clock.getAsLong();
        publish(RenderStatus.State.RENDERING, 0);
        setActive(true);
        return generation;
    }

    long beginRefinement() {
        long previousElapsed = status.state() == RenderStatus.State.COMPLETE
                ? status.elapsedNanos() : 0;
        long refinementGeneration = begin();
        startedAt -= previousElapsed;
        return refinementGeneration;
    }

    void finish(long renderGeneration) {
        if (active && renderGeneration == generation) {
            publish(RenderStatus.State.COMPLETE, clock.getAsLong() - startedAt);
            setActive(false);
        }
    }

    boolean fail(long renderGeneration) {
        if (active && renderGeneration == generation) {
            publish(RenderStatus.State.FAILED, 0);
            setActive(false);
            return true;
        }
        return false;
    }

    boolean isCurrent(long renderGeneration) {
        return active && renderGeneration == generation;
    }

    void cancel() {
        generation++;
        if (active) publish(RenderStatus.State.CANCELLED, 0);
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
