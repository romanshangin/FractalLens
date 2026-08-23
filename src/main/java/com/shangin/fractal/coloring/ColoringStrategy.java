package com.shangin.fractal.coloring;

public interface ColoringStrategy {

    int color(
            int iterations,
            double smoothIterations,
            boolean escaped,
            int maxIterations
    );
}