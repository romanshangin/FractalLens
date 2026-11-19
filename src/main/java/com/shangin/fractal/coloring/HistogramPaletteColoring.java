package com.shangin.fractal.coloring;

import com.shangin.fractal.render.SamplePlane;

import java.util.Objects;

/**
 * Frame-dependent tonal mapping based on the cumulative distribution of
 * escaped smooth-iteration values. Geometry sampling remains the
 * responsibility of the antialiasing pipeline.
 *
 * <p>A completed frame's mapping also colors the pixels of the next frame
 * while that frame is calculated, see {@link #forProgressivePixels}.
 */
public final class HistogramPaletteColoring implements ColoringStrategy {

    static final int DEFAULT_BIN_COUNT = 2048;
    private static final int INSIDE_COLOR = 0xFF000000;

    private final Palette palette;
    private final double offset;
    private final double minimum;
    private final double scale;
    private final double[] cumulative;
    /** Whether values outside the distribution's range continue the mapping instead of clamping. */
    private final boolean extendsRange;
    /**
     * Palette positions per smooth iteration that continue a distribution with a single
     * escaped value, which has no range width to continue at; zero otherwise.
     */
    private final double singleValueSlope;

    public HistogramPaletteColoring(Palette palette, double offset, SamplePlane data) {
        this(palette, offset, data, DEFAULT_BIN_COUNT);
    }

    HistogramPaletteColoring(Palette palette, double offset, SamplePlane data, int binCount) {
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

        extendsRange = false;
        singleValueSlope = 0.0;
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

    private HistogramPaletteColoring(Palette palette, double offset, HistogramPaletteColoring distribution,
                                     double singleValueSlope) {
        this.palette = Objects.requireNonNull(palette);
        this.offset = offset;
        minimum = distribution.minimum;
        scale = distribution.scale;
        cumulative = distribution.cumulative;
        extendsRange = true;
        this.singleValueSlope = singleValueSlope;
    }

    /**
     * Colors the pixels of a frame that is still being calculated, such as progressive
     * tiles and wide-preview pixels after a pan or zoom, with this completed frame's
     * distribution under {@code palette} and {@code offset}, so they match the colors
     * shown until the new frame completes and computes its own distribution.
     *
     * <p>Values outside this distribution's range, such as the higher smooth iterations
     * of a deeper zoom, do not clamp to the palette ends: the position continues
     * linearly beyond 0 and 1 at the distribution's mean slope, one palette sweep per
     * width of its range, and wraps like any offset position. A frame whose escaped
     * pixels all share one value has no range width: that value keeps its completed
     * position, and other values continue from it at {@code colorScale} positions per
     * smooth iteration, the slope of the frame-independent smooth palette.
     *
     * @param colorScale palette positions per smooth iteration of the smooth palette
     * @return the progressive coloring, or {@code null} when this frame has no escaped
     *         pixel whose colors interaction could keep
     */
    public HistogramPaletteColoring forProgressivePixels(Palette palette, double offset, double colorScale) {
        if (cumulative[cumulative.length - 1] == 0.0) {
            return null;
        }
        return new HistogramPaletteColoring(palette, offset, this, scale == 0.0 ? colorScale : 0.0);
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
        if (!Double.isFinite(value)) {
            return cumulative[0];
        }
        if (scale == 0.0) {
            return cumulative[0] + (value - minimum) * singleValueSlope;
        }
        double coordinate = (value - minimum) * scale;
        if (coordinate <= 0.0) {
            return extendsRange ? coordinate / cumulative.length : 0.0;
        }
        if (coordinate >= cumulative.length) {
            return extendsRange
                    ? 1.0 + (coordinate - cumulative.length) / cumulative.length
                    : cumulative[cumulative.length - 1];
        }

        int bin = (int) coordinate;
        double before = bin == 0 ? 0.0 : cumulative[bin - 1];
        double mass = cumulative[bin] - before;
        return before + (coordinate - bin) * mass;
    }
}
