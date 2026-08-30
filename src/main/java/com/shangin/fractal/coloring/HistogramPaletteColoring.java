package com.shangin.fractal.coloring;

import com.shangin.fractal.render.FractalData;

import java.util.Objects;

/**
 * Frame-dependent tonal mapping based on the cumulative distribution of
 * escaped smooth-iteration values. Geometry sampling remains the
 * responsibility of the antialiasing pipeline.
 */
public final class HistogramPaletteColoring implements ColoringStrategy {

    static final int DEFAULT_BIN_COUNT = 2048;
    private static final int INSIDE_COLOR = 0xFF000000;

    private final Palette palette;
    private final double offset;
    private final double minimum;
    private final double scale;
    private final double[] cumulative;

    public HistogramPaletteColoring(Palette palette, double offset, FractalData data) {
        this(palette, offset, data, DEFAULT_BIN_COUNT);
    }

    HistogramPaletteColoring(Palette palette, double offset, FractalData data, int binCount) {
        this.palette = Objects.requireNonNull(palette);
        this.offset = offset;
        Objects.requireNonNull(data);
        if (binCount < 2) {
            throw new IllegalArgumentException("Histogram needs at least two bins");
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int escapedCount = 0;
        for (int index = 0; index < data.size(); index++) {
            if (data.escaped(index) && Double.isFinite(data.smoothIterations(index))) {
                double value = data.smoothIterations(index);
                min = Math.min(min, value);
                max = Math.max(max, value);
                escapedCount++;
            }
        }

        minimum = escapedCount == 0 ? 0.0 : min;
        scale = escapedCount == 0 || max <= min ? 0.0 : binCount / (max - min);
        cumulative = new double[binCount];
        if (escapedCount == 0) {
            return;
        }

        for (int index = 0; index < data.size(); index++) {
            if (data.escaped(index) && Double.isFinite(data.smoothIterations(index))) {
                cumulative[bin(data.smoothIterations(index))]++;
            }
        }
        double running = 0.0;
        for (int bin = 0; bin < cumulative.length; bin++) {
            running += cumulative[bin];
            cumulative[bin] = running / escapedCount;
        }
    }

    @Override
    public int color(int iterations, double smoothIterations, boolean escaped, int maxIterations) {
        if (!escaped) {
            return INSIDE_COLOR;
        }
        double position = cumulativePosition(smoothIterations);
        double phase = position + offset;
        double wrapped = phase - Math.floor(phase / 2.0) * 2.0;
        double shifted = wrapped <= 1.0 ? wrapped : 2.0 - wrapped;
        return palette.color(shifted);
    }

    private int bin(double value) {
        if (!Double.isFinite(value) || scale == 0.0) {
            return 0;
        }
        return Math.max(0, Math.min(cumulative.length - 1,
                (int) ((value - minimum) * scale)));
    }

    /**
     * Interpolates through the probability mass of a bin instead of assigning
     * one palette position to every sample in that bin. This keeps the CDF
     * continuous and avoids introducing visible tonal bands.
     */
    private double cumulativePosition(double value) {
        if (!Double.isFinite(value) || scale == 0.0) {
            return cumulative[0];
        }
        double coordinate = (value - minimum) * scale;
        if (coordinate <= 0.0) {
            return 0.0;
        }
        if (coordinate >= cumulative.length) {
            return cumulative[cumulative.length - 1];
        }

        int bin = (int) coordinate;
        double before = bin == 0 ? 0.0 : cumulative[bin - 1];
        double mass = cumulative[bin] - before;
        return before + (coordinate - bin) * mass;
    }
}
