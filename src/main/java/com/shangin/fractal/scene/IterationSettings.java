package com.shangin.fractal.scene;

import com.shangin.fractal.config.AdaptiveIterationPolicy;

import java.math.BigDecimal;

/** Immutable iteration policy parameters belonging to a fractal scene. */
public record IterationSettings(
        int baseIterations,
        int iterationsPerZoomLevel
) {
    public static final int DEFAULT_BASE_ITERATIONS = 300;
    public static final int DEFAULT_ITERATIONS_PER_ZOOM_LEVEL = 50;

    public IterationSettings {
        if (baseIterations <= 0) {
            throw new IllegalArgumentException("Base iterations must be positive");
        }
        if (iterationsPerZoomLevel < 0) {
            throw new IllegalArgumentException("Iterations per zoom level must not be negative");
        }
    }

    public IterationSettings() {
        this(DEFAULT_BASE_ITERATIONS, DEFAULT_ITERATIONS_PER_ZOOM_LEVEL);
    }

    public int maxIterations(double defaultScale, double currentScale) {
        return new AdaptiveIterationPolicy(iterationsPerZoomLevel)
                .maxIterations(baseIterations, defaultScale, currentScale);
    }

    public int maxIterations(BigDecimal defaultScale, BigDecimal currentScale) {
        return new AdaptiveIterationPolicy(iterationsPerZoomLevel)
                .maxIterations(baseIterations, defaultScale, currentScale);
    }
}
