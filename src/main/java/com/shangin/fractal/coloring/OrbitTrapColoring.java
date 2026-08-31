package com.shangin.fractal.coloring;

import java.util.Objects;

/** Maps the closest orbit-to-trap distance through the active palette. */
public final class OrbitTrapColoring implements ColoringStrategy {

    private static final int UNAVAILABLE_COLOR = 0xFF000000;
    private static final double DISTANCE_SCALE = 6.0;

    private final Palette palette;
    private final double offset;

    public OrbitTrapColoring(Palette palette, double offset) {
        this.palette = Objects.requireNonNull(palette);
        this.offset = offset;
    }

    @Override
    public int color(int iterations, double smoothIterations, boolean escaped, int maxIterations) {
        return UNAVAILABLE_COLOR;
    }

    @Override
    public int color(
            int iterations,
            double smoothIterations,
            boolean escaped,
            int maxIterations,
            double orbitTrapDistance
    ) {
        if (!Double.isFinite(orbitTrapDistance)) {
            return UNAVAILABLE_COLOR;
        }
        double position = 1.0 - Math.exp(-orbitTrapDistance * DISTANCE_SCALE);
        double phase = position + offset;
        double wrapped = phase - Math.floor(phase / 2.0) * 2.0;
        return palette.color(wrapped <= 1.0 ? wrapped : 2.0 - wrapped);
    }
}
