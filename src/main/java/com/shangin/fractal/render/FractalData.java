package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalSample;

import java.util.Objects;

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
}
