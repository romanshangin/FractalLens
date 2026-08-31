package com.shangin.fractal.coloring;

public interface ColoringStrategy {

    int color(
            int iterations,
            double smoothIterations,
            boolean escaped,
            int maxIterations
    );

    default int color(
            int iterations,
            double smoothIterations,
            boolean escaped,
            int maxIterations,
            double orbitTrapDistance
    ) {
        return color(iterations, smoothIterations, escaped, maxIterations);
    }
}
