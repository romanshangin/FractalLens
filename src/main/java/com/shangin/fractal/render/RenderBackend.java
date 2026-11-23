package com.shangin.fractal.render;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Calculation backend with progressive publication and cooperative cancellation. */
public interface RenderBackend extends AutoCloseable {

    /** Whether this backend can calculate the job without losing coordinate precision. */
    default boolean supports(RenderJob job) {
        return true;
    }

    /**
     * Calculates the frame's missing pixels on the calling thread's behalf.
     *
     * <p>{@code regionCompleted} may be invoked concurrently from several worker threads,
     * so it must be thread-safe; it is not called after this method returns.
     */
    RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException;

    @Override
    void close();
}
