package com.shangin.fractal.render;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Calculation backend with progressive publication and cooperative cancellation. */
public interface RenderBackend extends AutoCloseable {

    RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException;

    @Override
    void close();
}
