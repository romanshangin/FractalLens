package com.shangin.fractal.config;

/** Speed presets for clicking the Mandelbrot parameter map. */
public enum JuliaClickSpeed {
    VERY_SLOW("Very slow", 0.25),
    SLOW("Slow", 0.5),
    DEFAULT("Default", 1.0),
    FAST("Fast", 2.0),
    VERY_FAST("Very fast", 4.0);

    private final String label;
    private final double multiplier;

    JuliaClickSpeed(String label, double multiplier) {
        this.label = label;
        this.multiplier = multiplier;
    }

    public double multiplier() {
        return multiplier;
    }

    @Override
    public String toString() {
        return label;
    }
}
