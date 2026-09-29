package com.shangin.fractal.scene;

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
}
