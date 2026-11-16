package com.shangin.fractal.render;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Restores an empty frame from a {@link PersistentFrameStore} instead of calculating it,
 * and stores the frames the wrapped backend completes. Restoring runs inside the normal
 * render call, so generations, cancellation and atomic publication are unchanged: a
 * restored frame is published as one completed region exactly like a calculated one.
 * Frames that already hold reused pixels are calculated by the wrapped backend.
 *
 * <p>The antialiasing stored with a restored frame is read on the render thread too, after
 * the frame's region is reported, and kept for the caller of the completed render to
 * {@linkplain #takeRestoredRefinement take}; a newer render discards it. A completed
 * frame that was not restored can {@linkplain #readStoredSamples read} the AA samples
 * stored for it the same way.
 */
public final class PersistentFrameRenderBackend implements RenderBackend {

    private final RenderBackend delegate;
    private final PersistentFrameStore store;
    private final Object restoredLock = new Object();
    private RenderFrame restoredFrame;
    private RetainedRefinement restoredRefinement;

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
        keepRestored(null, null);
        if (store.load(frame, cancelled)) {
            regionCompleted.accept(new RenderRegion(0, 0, frame.request().width(), frame.request().height()));
            RetainedRefinement refinement = store.loadRefinement(frame, cancelled);
            if (refinement != null && !cancelled.getAsBoolean()) {
                keepRestored(frame, refinement);
            }
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

    /**
     * Reads the AA samples stored for {@code frame}, a frame this backend just completed
     * without restoring it, such as a cached frame shown again after the in-memory frame
     * cache dropped its samples. They are handed out by {@link #takeRestoredRefinement} as
     * AA without colors; nothing is kept on a miss or cancellation. Render thread only.
     */
    public void readStoredSamples(RenderFrame frame, BooleanSupplier cancelled) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(cancelled);
        AntialiasSampleCache.Retained samples = store.loadStoredSamples(frame, cancelled);
        if (samples != null && !cancelled.getAsBoolean()) {
            keepRestored(frame, new RetainedRefinement(null, null, samples));
        }
    }

    private void keepRestored(RenderFrame frame, RetainedRefinement refinement) {
        synchronized (restoredLock) {
            restoredFrame = frame;
            restoredRefinement = refinement;
        }
    }

    /**
     * The antialiasing stored with {@code frame} when the latest render restored that frame,
     * handed out once.
     */
    public Optional<RetainedRefinement> takeRestoredRefinement(RenderFrame frame) {
        Objects.requireNonNull(frame);
        synchronized (restoredLock) {
            if (restoredFrame != frame) {
                return Optional.empty();
            }
            RetainedRefinement refinement = restoredRefinement;
            restoredFrame = null;
            restoredRefinement = null;
            return Optional.of(refinement);
        }
    }

    /** Stores a completed frame's finished antialiasing beside its stored base frame. */
    public void saveRefinementWhenSettled(RenderFrame frame, RetainedRefinement refinement) {
        store.saveRefinementWhenSettled(frame, refinement);
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            keepRestored(null, null);
            store.close();
        }
    }
}
