package com.shangin.fractal.coloring;

public record ColorStop(double position, int color) {

    public ColorStop {
        if (position < 0.0 || position > 1.0) {
            throw new IllegalArgumentException("Position must be between 0.0 and 1.0");
        }
    }
}
