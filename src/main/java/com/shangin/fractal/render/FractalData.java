package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalSample;

import java.util.Objects;

/** Structure-of-arrays storage for the calculated sample values of one frame. */
public class FractalData {
    private final int width;
    private final int height;
    private final int maxIterations;

    private final int[] iterations;
    private final double[] smoothIterations;
    private final boolean[] escaped;

    public FractalData(
            int width,
            int height,
            int maxIterations
    ) {
        this.width = width;
        this.height = height;
        this.maxIterations = maxIterations;

        int size = width * height;

        this.iterations = new int[size];
        this.smoothIterations = new double[size];
        this.escaped = new boolean[size];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int maxIterations() {
        return maxIterations;
    }

    public int size() {
        return width * height;
    }

    public int iterations(int index) {
        return iterations[index];
    }

    public double smoothIterations(int index) {
        return smoothIterations[index];
    }

    public boolean escaped(int index) {
        return escaped[index];
    }

    public void set(
            int index,
            FractalSample sample
    ) {
        iterations[index] = sample.iterations();
        smoothIterations[index] = sample.smoothIterations();
        escaped[index] = sample.escaped();
    }

    public void set(
            int x,
            int y,
            FractalSample sample
    ) {
        set(y * width + x, sample);
    }

    public void copyPixelFrom(
            FractalData source,
            int sourceX,
            int sourceY,
            int targetX,
            int targetY
    ) {
        Objects.requireNonNull(source);

        int sourceIndex = sourceY * source.width + sourceX;
        int targetIndex = targetY * width + targetX;

        iterations[targetIndex] = source.iterations[sourceIndex];
        smoothIterations[targetIndex] = source.smoothIterations[sourceIndex];
        escaped[targetIndex] = source.escaped[sourceIndex];
    }

    /** Copies a rectangular sample region without reallocating either frame. */
    public void copyRegionFrom(
            FractalData source,
            int sourceX,
            int sourceY,
            int targetX,
            int targetY,
            int regionWidth,
            int regionHeight
    ) {
        Objects.requireNonNull(source);

        if (regionWidth <= 0 || regionHeight <= 0) {
            throw new IllegalArgumentException(
                    "Region dimensions must be positive"
            );
        }

        if (sourceX < 0
                || sourceY < 0
                || sourceX + regionWidth > source.width
                || sourceY + regionHeight > source.height) {

            throw new IllegalArgumentException(
                    "Source region is outside source data"
            );
        }

        if (targetX < 0
                || targetY < 0
                || targetX + regionWidth > width
                || targetY + regionHeight > height) {

            throw new IllegalArgumentException(
                    "Target region is outside target data"
            );
        }

        for (int row = 0; row < regionHeight; row++) {

            int sourceIndex =
                    (sourceY + row) * source.width
                            + sourceX;

            int targetIndex =
                    (targetY + row) * width
                            + targetX;

            System.arraycopy(
                    source.iterations,
                    sourceIndex,
                    iterations,
                    targetIndex,
                    regionWidth
            );

            System.arraycopy(
                    source.smoothIterations,
                    sourceIndex,
                    smoothIterations,
                    targetIndex,
                    regionWidth
            );

            System.arraycopy(
                    source.escaped,
                    sourceIndex,
                    escaped,
                    targetIndex,
                    regionWidth
            );
        }
    }
}
