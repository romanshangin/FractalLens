package com.shangin.fractal.render;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Boundary used until the Mandelbrot perturbation backend is implemented in
 * roadmap step 7.3. It makes backend selection explicit instead of allowing
 * the direct renderer to calculate collapsed coordinates.
 */
final class PendingDeepZoomBackend implements RenderBackend {

    @Override
    public RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) {
        throw new UnsupportedOperationException(
                "This scene requires the deep-zoom render backend planned for roadmap step 7.3");
    }

    @Override
    public void close() {
    }
}
