package com.shangin.fractal.render;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Restores an empty frame from a {@link PersistentFrameStore} instead of calculating it,
 * and stores the frames the wrapped backend completes. Restoring runs inside the normal
 * render call, so generations, cancellation and atomic publication are unchanged: a
 * restored frame is published as one completed region exactly like a calculated one.
 * Frames that already hold reused pixels are calculated by the wrapped backend.
 */
public final class PersistentFrameRenderBackend implements RenderBackend {

    private final RenderBackend delegate;
    private final PersistentFrameStore store;

    /** Takes ownership of both the backend and the store and closes them. */
    public PersistentFrameRenderBackend(RenderBackend delegate, PersistentFrameStore store) {
        this.delegate = Objects.requireNonNull(delegate);
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public boolean supports(RenderJob job) {
        return delegate.supports(job);
    }

    @Override
    public RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException {
        if (store.load(frame, cancelled)) {
            regionCompleted.accept(new RenderRegion(0, 0, frame.request().width(), frame.request().height()));
            return frame;
        }
        if (cancelled.getAsBoolean()) {
            return frame;
        }
        RenderFrame result = delegate.render(frame, cancelled, regionCompleted, timingCompleted);
        if (result.isComplete() && !cancelled.getAsBoolean()) {
            store.saveWhenSettled(result);
        }
        return result;
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            store.close();
        }
    }
}
