package com.shangin.fractal.render;

/** Diagnostic timings and work counts for one Mandelbrot deep-zoom generation. */
public record DeepZoomTimingStats(
        double referenceOrbitMs,
        double coordinatePreparationMs,
        double timeToFirstRegionMs,
        int workerTaskCount,
        int queuedTileCount,
        int completedTileCount,
        int cancelledTileCount,
        long calculatedPixelCount,
        long highPrecisionFallbackPixelCount,
        double averageIterationsPerPixel
) {
    public DeepZoomTimingStats {
        if (referenceOrbitMs < 0.0 || coordinatePreparationMs < 0.0
                || timeToFirstRegionMs < -1.0 || workerTaskCount < 0
                || queuedTileCount < 0 || completedTileCount < 0
                || cancelledTileCount < 0 || calculatedPixelCount < 0
                || highPrecisionFallbackPixelCount < 0
                || averageIterationsPerPixel < 0.0) {
            throw new IllegalArgumentException("Deep-zoom diagnostics cannot be negative");
        }
    }
}
