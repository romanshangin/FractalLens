package com.shangin.fractal.config;

public record FractalSettings(int maxIterations) {

    public static final int DEFAULT_MAX_ITERATIONS = 300;

    public FractalSettings() {
        this(DEFAULT_MAX_ITERATIONS);
    }
}