package com.shangin.fractal.config;

public class AdaptiveIterationPolicy implements IterationPolicy {

    private static final double LOG_2 = Math.log(2.0);
    private final int iterationsPerZoomLevel;

    public AdaptiveIterationPolicy(int iterationsPerZoomLevel) {
        if (iterationsPerZoomLevel < 0) {
            throw new IllegalArgumentException("Iterations per zoom level must not be negative");
        }

        this.iterationsPerZoomLevel = iterationsPerZoomLevel;
    }

    @Override
    public int maxIterations(
            int baseIterations,
            double defaultScale,
            double currentScale
    ) {
        if (baseIterations <= 0) {
            throw new IllegalArgumentException(
                    "Base iterations must be positive");
        }

        if (defaultScale <= 0.0 || currentScale <= 0.0) {
            throw new IllegalArgumentException(
                    "Scale must be positive");
        }

        double zoomFactor = defaultScale / currentScale;

        if (zoomFactor <= 1.0) {
            return baseIterations;
        }

        double zoomLevel = Math.log(zoomFactor) / LOG_2;
        int additionalIterations = (int) Math.floor(zoomLevel * iterationsPerZoomLevel);

        return baseIterations + additionalIterations;
    }
}
