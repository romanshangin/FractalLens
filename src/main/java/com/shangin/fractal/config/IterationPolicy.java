package com.shangin.fractal.config;

public interface IterationPolicy {
    int maxIterations(
            int baseIterations,
            double defaultScale,
            double currentScale
    );
}
