package com.shangin.fractal.coloring;

public record ColorStop(double position, int color) {

    public ColorStop {
        if (!Double.isFinite(position) || position < 0.0 || position > 1.0) {
            throw new IllegalArgumentException("Position must be finite and between 0.0 and 1.0");
        }
    }
}
