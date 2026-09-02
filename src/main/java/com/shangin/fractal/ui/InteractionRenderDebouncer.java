package com.shangin.fractal.ui;

import java.util.Objects;
import java.util.function.LongConsumer;

/** Coalesces camera changes and flushes a pending render at a gesture boundary. */
final class InteractionRenderDebouncer {

    private static final long ZOOM_DELAY_MS = 30;
    private static final long GESTURE_DELAY_MS = 75;

    private final LongConsumer restartTimer;
    private final Runnable stopTimer;
    private final Runnable render;
    private boolean pending;
    private boolean zoomGestureActive;

    InteractionRenderDebouncer(LongConsumer restartTimer, Runnable stopTimer, Runnable render) {
        this.restartTimer = Objects.requireNonNull(restartTimer);
        this.stopTimer = Objects.requireNonNull(stopTimer);
        this.render = Objects.requireNonNull(render);
    }

    void requestZoomRender() {
        // Keep the existing coalescing during a live pinch; its end flushes immediately.
        request(zoomGestureActive ? GESTURE_DELAY_MS : ZOOM_DELAY_MS);
    }

    void requestPanRender() {
        request(GESTURE_DELAY_MS);
    }

    void zoomStarted() {
        zoomGestureActive = true;
    }

    void zoomFinished() {
        zoomGestureActive = false;
        finish();
    }

    private void request(long delayMs) {
        pending = true;
        restartTimer.accept(delayMs);
    }

    /** Used both by timer expiry and by an explicit gesture-finished event. */
    void finish() {
        if (!pending) {
            return;
        }
        cancel();
        render.run();
    }

    void cancel() {
        pending = false;
        stopTimer.run();
    }
}
