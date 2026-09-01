package com.shangin.fractal.config;

import java.math.BigDecimal;

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

    public int maxIterations(
            int baseIterations,
            BigDecimal defaultScale,
            BigDecimal currentScale
    ) {
        if (baseIterations <= 0) {
            throw new IllegalArgumentException("Base iterations must be positive");
        }
        if (defaultScale.signum() <= 0 || currentScale.signum() <= 0) {
            throw new IllegalArgumentException("Scale must be positive");
        }
        if (defaultScale.compareTo(currentScale) <= 0) {
            return baseIterations;
        }

        double zoomLevel = log2(defaultScale) - log2(currentScale);
        long additional = (long) Math.floor(zoomLevel * iterationsPerZoomLevel);
        return additional >= Integer.MAX_VALUE - baseIterations
                ? Integer.MAX_VALUE
                : baseIterations + (int) additional;
    }

    private static double log2(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int exponent = stripped.precision() - stripped.scale() - 1;
        BigDecimal mantissa = stripped.movePointLeft(exponent);
        return (exponent * Math.log(10.0) + Math.log(mantissa.doubleValue())) / LOG_2;
    }
}
