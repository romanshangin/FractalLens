package com.shangin.fractal.render;

/**
 * Diagnostic timings and work counts for one Mandelbrot deep-zoom generation.
 * Average iterations include BLA-covered iterations and reference retries;
 * subtract skipped iterations to recover the scalar recurrence work.
 */
public record DeepZoomTimingStats(
        double referenceOrbitMs,
        double coordinatePreparationMs,
        double blaPreparationMs,
        double timeToFirstRegionMs,
        int workerTaskCount,
        int queuedTileCount,
        int completedTileCount,
        int cancelledTileCount,
        long calculatedPixelCount,
        long highPrecisionFallbackPixelCount,
        long modifiedRebaseCount,
        long blaStepCount,
        long blaSkippedIterationCount,
        int additionalReferenceOrbitCount,
        double additionalReferenceOrbitMs,
        double averageIterationsPerPixel
) {
    public DeepZoomTimingStats {
        if (referenceOrbitMs < 0.0 || coordinatePreparationMs < 0.0 || blaPreparationMs < 0.0
                || timeToFirstRegionMs < -1.0 || workerTaskCount < 0
                || queuedTileCount < 0 || completedTileCount < 0
                || cancelledTileCount < 0 || calculatedPixelCount < 0
                || highPrecisionFallbackPixelCount < 0
                || modifiedRebaseCount < 0 || blaStepCount < 0 || blaSkippedIterationCount < 0
                || additionalReferenceOrbitCount < 0 || additionalReferenceOrbitMs < 0.0
                || averageIterationsPerPixel < 0.0) {
            throw new IllegalArgumentException("Deep-zoom diagnostics cannot be negative");
        }
    }
}
