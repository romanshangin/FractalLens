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
    /*
     * Null when the frame's formula has no orbit trap: every distance is then NaN,
     * and a full-size NaN-filled array would only add allocation and fill time on
     * the thread that creates the frame.
     */
    private final double[] orbitTrapDistances;

    public FractalData(
            int width,
            int height,
            int maxIterations
    ) {
        this(width, height, maxIterations, true);
    }

    public FractalData(
            int width,
            int height,
            int maxIterations,
            boolean storesOrbitTrapDistances
    ) {
        this(maxIterations, Storage.allocate(width, height, storesOrbitTrapDistances));
    }

    /** Wraps storage that no other sample plane references; the caller hands over ownership. */
    FractalData(int maxIterations, Storage storage) {
        this.width = storage.width;
        this.height = storage.height;
        this.maxIterations = maxIterations;
        this.iterations = storage.iterations;
        this.smoothIterations = storage.smoothIterations;
        this.escaped = storage.escaped;
        this.orbitTrapDistances = storage.orbitTrapDistances;
    }

    /**
     * Newly allocated sample arrays in the state of an empty frame: zero samples and NaN
     * orbit-trap distances. Allocating them is the expensive part of creating a frame, so
     * it can run on another thread before one frame takes ownership of them.
     */
    static final class Storage {
        private final int width;
        private final int height;
        private final int[] iterations;
        private final double[] smoothIterations;
        private final boolean[] escaped;
        private final double[] orbitTrapDistances;

        private Storage(int width, int height, boolean storesOrbitTrapDistances) {
            this.width = width;
            this.height = height;
            int size = width * height;
            iterations = new int[size];
            smoothIterations = new double[size];
            escaped = new boolean[size];
            if (storesOrbitTrapDistances) {
                orbitTrapDistances = new double[size];
                java.util.Arrays.fill(orbitTrapDistances, Double.NaN);
            } else {
                orbitTrapDistances = null;
            }
        }

        static Storage allocate(int width, int height, boolean storesOrbitTrapDistances) {
            return new Storage(width, height, storesOrbitTrapDistances);
        }
    }

    /** True when both planes write to at least one common array. */
    boolean sharesStorageWith(FractalData other) {
        return iterations == other.iterations
                || smoothIterations == other.smoothIterations
                || escaped == other.escaped
                || orbitTrapDistances != null && orbitTrapDistances == other.orbitTrapDistances;
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
        return orbitTrapDistances == null ? Double.NaN : orbitTrapDistances[index];
    }

    private void setOrbitTrapDistance(int index, double distance) {
        if (orbitTrapDistances != null) {
            orbitTrapDistances[index] = distance;
        } else if (!Double.isNaN(distance)) {
            throw new IllegalStateException("Frame does not store orbit-trap distances");
        }
    }

    private void copyOrbitTrapDistances(FractalData source, int sourceIndex, int targetIndex, int length) {
        if (source.orbitTrapDistances != null && orbitTrapDistances != null) {
            System.arraycopy(source.orbitTrapDistances, sourceIndex, orbitTrapDistances, targetIndex, length);
        } else if (orbitTrapDistances != null) {
            java.util.Arrays.fill(orbitTrapDistances, targetIndex, targetIndex + length, Double.NaN);
        } else if (source.orbitTrapDistances != null) {
            for (int offset = 0; offset < length; offset++) {
                setOrbitTrapDistance(targetIndex + offset, source.orbitTrapDistances[sourceIndex + offset]);
            }
        }
    }

    public void set(
            int index,
            FractalSample sample
    ) {
        iterations[index] = sample.iterations();
        smoothIterations[index] = sample.smoothIterations();
        escaped[index] = sample.escaped();
        setOrbitTrapDistance(index, sample.orbitTrapDistance());
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
        setOrbitTrapDistance(index, orbitTrapDistance);
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
        copyOrbitTrapDistances(sourceData, sourceIndex, targetIndex, 1);
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
        copyOrbitTrapDistances(sourceData, sourceIndex, targetIndex, length);
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

            copyOrbitTrapDistances(sourceData, sourceIndex, targetIndex, regionWidth);
        }
    }
}
