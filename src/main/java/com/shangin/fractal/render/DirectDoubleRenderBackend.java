package com.shangin.fractal.render;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** First backend: the existing tiled CPU renderer using hardware doubles. */
public final class DirectDoubleRenderBackend implements RenderBackend {

    private final ParallelFractalCalculator renderer;

    public DirectDoubleRenderBackend() {
        this(new ParallelFractalCalculator());
    }

    DirectDoubleRenderBackend(ParallelFractalCalculator renderer) {
        this.renderer = Objects.requireNonNull(renderer);
    }

    @Override
    public RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException {
        if (timingCompleted == null) {
            return renderer.calculate(frame, cancelled, regionCompleted);
        }
        return renderer.calculate(
                frame, cancelled, regionCompleted, timingCompleted);
    }

    @Override
    public void close() {
        renderer.close();
    }
}
