package com.shangin.fractal.coloring;

public class PaletteColoring implements ColoringStrategy {

    private static final int INSIDE_COLOR = 0xFF000000;

    private final Palette palette;

    public PaletteColoring(Palette palette) {
        this.palette = palette;
    }

    @Override
    public int color(
            int iterations,
            double smoothIterations,
            boolean escaped,
            int maxIterations
    ) {
        if (!escaped) {
            return INSIDE_COLOR;
        }

        double position = (double) iterations / maxIterations;

        return palette.color(position);
    }
}
