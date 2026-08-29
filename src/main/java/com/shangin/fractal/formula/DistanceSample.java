package com.shangin.fractal.formula;

import java.util.Objects;

/** Escape sample paired with an exterior distance estimate when available. */
public record DistanceSample(FractalSample sample, double distance) {

    public DistanceSample {
        Objects.requireNonNull(sample);
        if (!Double.isNaN(distance) && (!Double.isFinite(distance) || distance < 0.0)) {
            throw new IllegalArgumentException("Distance must be non-negative, finite, or unavailable");
        }
    }

    public boolean hasDistance() {
        return !Double.isNaN(distance);
    }

    public static DistanceSample unavailable(FractalSample sample) {
        return new DistanceSample(sample, Double.NaN);
    }
}
