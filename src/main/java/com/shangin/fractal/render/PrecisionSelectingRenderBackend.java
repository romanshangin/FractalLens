package com.shangin.fractal.render;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Selects hardware-double rendering when safe and a separate deep backend otherwise. */
public final class PrecisionSelectingRenderBackend implements RenderBackend {

    private final RenderBackend directBackend;
    private final RenderBackend deepZoomBackend;

    public PrecisionSelectingRenderBackend(
            RenderBackend directBackend,
            RenderBackend deepZoomBackend
    ) {
        this.directBackend = Objects.requireNonNull(directBackend);
        this.deepZoomBackend = Objects.requireNonNull(deepZoomBackend);
    }

    @Override
    public boolean supports(RenderJob job) {
        return directBackend.supports(job) || deepZoomBackend.supports(job);
    }

    public RenderBackend select(RenderJob job) {
        Objects.requireNonNull(job);
        if (directBackend.supports(job)) {
            return directBackend;
        }
        if (deepZoomBackend.supports(job)) {
            return deepZoomBackend;
        }
        throw new IllegalArgumentException(
                "No render backend supports the requested coordinate precision and formula");
    }

    @Override
    public RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException {
        return select(frame.job()).render(
                frame, cancelled, regionCompleted, timingCompleted);
    }

    @Override
    public void close() {
        try {
            directBackend.close();
        } finally {
            if (deepZoomBackend != directBackend) {
                deepZoomBackend.close();
            }
        }
    }
}
