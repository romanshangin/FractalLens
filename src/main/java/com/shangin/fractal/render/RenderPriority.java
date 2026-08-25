package com.shangin.fractal.render;

public record RenderPriority(
        double x,
        double y
) {
    public RenderPriority {
        if (!Double.isFinite(x) || x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
            throw new IllegalArgumentException("Priority coordinates must be finite and between 0 and 1");
        }
    }

    public static RenderPriority center() {
        return new RenderPriority(0.5, 0.5);
    }
}