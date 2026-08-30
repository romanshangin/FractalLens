package com.shangin.fractal.coloring;

public class SmoothPaletteColoring implements ColoringStrategy {

    private static final int INSIDE_COLOR = 0xFF000000;
    public static final double DEFAULT_COLOR_SCALE = 0.0075;

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

        return colorFromBasePhase(basePhase(smoothIterations));
    }

    /** Palette-independent phase before the animated offset is applied. */
    public double basePhase(double smoothIterations) {
        double phase = smoothIterations * colorScale;
        return phase - Math.floor(phase / 2.0) * 2.0;
    }

    /** Colors a cached base phase using this strategy's current offset. */
    public int colorFromBasePhase(double basePhase) {
        double phase = basePhase + offset;
        double wrapped = phase - Math.floor(phase / 2.0) * 2.0;
        double position = wrapped <= 1.0
                ? wrapped
                : 2.0 - wrapped;

        return palette.color(position);
    }

    public double colorScale() {
        return colorScale;
    }
}
