package com.shangin.fractal.render;

import com.shangin.fractal.formula.MandelbrotFormula;

import java.util.Objects;

/**
 * Estimates how far a Mandelbrot parameter lies from the set's boundary, in pixels of a sampled
 * grid. Outside the set it uses the exterior distance estimate, which also sees filaments thinner
 * than a pixel; inside, where that estimate is unavailable, it uses the distance to the nearest
 * escaping sample of the grid. Distances are capped at {@link #range()}.
 */
public final class MandelbrotBoundaryDistance {
    private static final int EXTERIOR_ITERATIONS = 1_000;
    private final MandelbrotFormula formula = new MandelbrotFormula();
    private final RenderGrid grid;
    private final int width;
    private final int height;
    private final int range;
    private final float[] interior;

    private MandelbrotBoundaryDistance(RenderGrid grid, int width, int height, int range, float[] interior) {
        this.grid = grid;
        this.width = width;
        this.height = height;
        this.range = range;
        this.interior = interior;
    }

    /**
     * Builds the interior distance field from a completed Mandelbrot sample plane rendered on
     * {@code grid}. Points beyond the plane count as escaping.
     */
    public static MandelbrotBoundaryDistance of(SamplePlane samples, RenderGrid grid, int range) {
        Objects.requireNonNull(samples);
        Objects.requireNonNull(grid);
        if (range < 1) throw new IllegalArgumentException("Range must be positive");
        int width = samples.width();
        int height = samples.height();
        float[] interior = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                interior[index] = samples.escaped(index) ? 0 : nearestEscaped(samples, x, y, range);
            }
        }
        return new MandelbrotBoundaryDistance(grid, width, height, range, interior);
    }

    // The boundary lies between a sample and its escaping neighbor, half a pixel from the sample.
    private static float nearestEscaped(SamplePlane samples, int x, int y, int range) {
        int nearest = (range + 1) * (range + 1);
        for (int dy = -range; dy <= range; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                int squared = dx * dx + dy * dy;
                if (squared >= nearest) continue;
                int nx = x + dx;
                int ny = y + dy;
                boolean outside = nx < 0 || ny < 0 || nx >= samples.width() || ny >= samples.height();
                if (outside || samples.escaped(ny * samples.width() + nx)) nearest = squared;
            }
        }
        return (float) Math.min(range, Math.sqrt(nearest) - 0.5);
    }

    /** Distance from {@code (real, imaginary)} to the set's boundary in grid pixels, at most {@link #range()}. */
    public double pixels(double real, double imaginary) {
        var exterior = formula.calculateDistance(real, imaginary, EXTERIOR_ITERATIONS);
        if (exterior.hasDistance()) return Math.min(range, exterior.distance() / grid.realStep());
        double x = Math.clamp((real - grid.originReal()) / grid.realStep(), 0, width - 1);
        double y = Math.clamp((grid.originImaginary() - imaginary) / grid.imaginaryStep(), 0, height - 1);
        int left = Math.min((int) x, width - 2);
        int top = Math.min((int) y, height - 2);
        double fx = x - left;
        double fy = y - top;
        double upper = interior[top * width + left] * (1 - fx) + interior[top * width + left + 1] * fx;
        double lower = interior[(top + 1) * width + left] * (1 - fx) + interior[(top + 1) * width + left + 1] * fx;
        return upper * (1 - fy) + lower * fy;
    }

    public int range() {
        return range;
    }
}
