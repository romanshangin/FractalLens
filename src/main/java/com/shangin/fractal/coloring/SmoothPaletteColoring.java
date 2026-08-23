package com.shangin.fractal.coloring;

public class SmoothPaletteColoring implements ColoringStrategy {

    private static final int INSIDE_COLOR = 0xFF000000;
    private static final double DEFAULT_COLOR_SCALE = 0.02;

    private final Palette palette;
    private final double colorScale;
    private final double offset;

    public SmoothPaletteColoring(Palette palette) {
        this(palette, DEFAULT_COLOR_SCALE, 0.0);
    }

    public SmoothPaletteColoring(
            Palette palette,
            double colorScale,
            double offset
    ) {
        this.palette = palette;
        this.colorScale = colorScale;
        this.offset = offset;
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

        double position = (smoothIterations * colorScale + offset) % 1.0;

        if (position < 0.0) {
            position += 1.0;
        }

        return palette.color(position);
    }
}
