package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalSample;

import java.util.Objects;

/** Structure-of-arrays storage for the calculated sample values of one frame. */
public class FractalData implements SamplePlane {
    private final int width;
    private final int height;
    private final int maxIterations;

    private final int[] iterations;
    private final double[] smoothIterations;
    private final boolean[] escaped;
    private final double[] orbitTrapDistances;

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
        this.orbitTrapDistances = new double[size];
        java.util.Arrays.fill(orbitTrapDistances, Double.NaN);
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

    public double orbitTrapDistance(int index) {
        return orbitTrapDistances[index];
    }

    public void set(
            int index,
            FractalSample sample
    ) {
        iterations[index] = sample.iterations();
        smoothIterations[index] = sample.smoothIterations();
        escaped[index] = sample.escaped();
        orbitTrapDistances[index] = sample.orbitTrapDistance();
    }

    public void set(
            int x,
            int y,
            FractalSample sample
    ) {
        set(y * width + x, sample);
    }

    @Override
    public void setValues(
            int index,
            int iterations,
            double smoothIterations,
            boolean escaped,
            double orbitTrapDistance
    ) {
        this.iterations[index] = iterations;
        this.smoothIterations[index] = smoothIterations;
        this.escaped[index] = escaped;
        this.orbitTrapDistances[index] = orbitTrapDistance;
    }

    public void copyPixelFrom(
            SamplePlane source,
            int sourceX,
            int sourceY,
            int targetX,
            int targetY
    ) {
        Objects.requireNonNull(source);

        if (!(source instanceof FractalData sourceData)) {
            SamplePlane.super.copyPixelFrom(
                    source, sourceX, sourceY, targetX, targetY);
            return;
        }

        int sourceIndex = sourceY * sourceData.width + sourceX;
        int targetIndex = targetY * width + targetX;

        iterations[targetIndex] = sourceData.iterations[sourceIndex];
        smoothIterations[targetIndex] = sourceData.smoothIterations[sourceIndex];
        escaped[targetIndex] = sourceData.escaped[sourceIndex];
        orbitTrapDistances[targetIndex] = sourceData.orbitTrapDistances[sourceIndex];
    }

    /** Copies one horizontal sample span to another row. */
    public void copyRowFrom(
            SamplePlane source,
            int sourceY,
            int targetY,
            int xFrom,
            int xTo
    ) {
        Objects.requireNonNull(source);

        if (source.width() != width
                || sourceY < 0
                || sourceY >= source.height()
                || targetY < 0
                || targetY >= height
                || xFrom < 0
                || xTo > width
                || xFrom >= xTo) {
            throw new IllegalArgumentException("Row span is outside fractal data");
        }

        int length = xTo - xFrom;
        if (!(source instanceof FractalData sourceData)) {
            SamplePlane.super.copyRowFrom(source, sourceY, targetY, xFrom, xTo);
            return;
        }

        int sourceIndex = sourceY * sourceData.width + xFrom;
        int targetIndex = targetY * width + xFrom;

        System.arraycopy(sourceData.iterations, sourceIndex, iterations, targetIndex, length);
        System.arraycopy(sourceData.smoothIterations, sourceIndex, smoothIterations, targetIndex, length);
        System.arraycopy(sourceData.escaped, sourceIndex, escaped, targetIndex, length);
        System.arraycopy(sourceData.orbitTrapDistances, sourceIndex, orbitTrapDistances, targetIndex, length);
    }

    /** Copies a rectangular sample region without reallocating either frame. */
    public void copyRegionFrom(
            SamplePlane source,
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

        if (!(source instanceof FractalData sourceData)) {
            SamplePlane.super.copyRegionFrom(source, sourceX, sourceY,
                    targetX, targetY, regionWidth, regionHeight);
            return;
        }

        if (sourceX < 0
                || sourceY < 0
                || sourceX + regionWidth > sourceData.width
                || sourceY + regionHeight > sourceData.height) {

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
                    (sourceY + row) * sourceData.width
                            + sourceX;

            int targetIndex =
                    (targetY + row) * width
                            + targetX;

            System.arraycopy(
                    sourceData.iterations,
                    sourceIndex,
                    iterations,
                    targetIndex,
                    regionWidth
            );

            System.arraycopy(
                    sourceData.smoothIterations,
                    sourceIndex,
                    smoothIterations,
                    targetIndex,
                    regionWidth
            );

            System.arraycopy(
                    sourceData.escaped,
                    sourceIndex,
                    escaped,
                    targetIndex,
                    regionWidth
            );

            System.arraycopy(
                    sourceData.orbitTrapDistances,
                    sourceIndex,
                    orbitTrapDistances,
                    targetIndex,
                    regionWidth
            );
        }
    }
}
