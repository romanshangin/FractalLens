package com.shangin.fractal.render;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** First backend: the existing tiled CPU renderer using hardware doubles. */
public final class DirectDoubleRenderBackend implements RenderBackend {

    private static final double DEFAULT_MINIMUM_ULPS_PER_PIXEL = 16.0;

    private final ParallelFractalCalculator renderer;

    public DirectDoubleRenderBackend() {
        this(new ParallelFractalCalculator());
    }

    DirectDoubleRenderBackend(ParallelFractalCalculator renderer) {
        this.renderer = Objects.requireNonNull(renderer);
    }

    @Override
    public boolean supports(RenderJob job) {
        Objects.requireNonNull(job);
        if (job.formula().preset() != null)
            return job.formula().preset().hasSufficientDirectPrecision(job.viewport(), job.width(), job.height());
        return job.viewport().hasSufficientPrecision(job.width(), job.height(), DEFAULT_MINIMUM_ULPS_PER_PIXEL);
    }

    @Override
    public RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException {
        if (!supports(frame.job())) {
            throw new IllegalArgumentException(
                    "The direct-double backend cannot represent this render grid");
        }
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
