package com.shangin.fractal.scene;

import com.shangin.fractal.math.PreciseComplex;

import java.util.Objects;

/** Immutable Julia constant edited as part of a reproducible scene. */
public record JuliaParameters(double real, double imaginary) {
    public JuliaParameters {
        if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
            throw new IllegalArgumentException("Julia parameters must be finite");
        }
    }

    public JuliaParameters() {
        this(-0.8, 0.156);
    }

    /**
     * The Julia constant for an exact complex-plane point. The constant is binary64, so
     * each component is rounded once, to nearest with ties to even; this is the only
     * precision loss between a selected coordinate and the Julia scene. Signed zero is
     * normalized to {@code +0.0}.
     *
     * @throws IllegalArgumentException if a component is outside the finite binary64 range
     */
    public static JuliaParameters nearest(PreciseComplex point) {
        Objects.requireNonNull(point, "Point must not be null");
        double real = point.real().doubleValue();
        double imaginary = point.imaginary().doubleValue();
        if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
            throw new IllegalArgumentException("Point is outside the Julia parameter range");
        }
        return new JuliaParameters(real + 0.0, imaginary + 0.0);
    }
}
