package com.shangin.fractal.gpu;

import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.RenderGrid;

/** Per-sample certificate for the direct Mandelbrot base pass. */
final class MandelbrotPrecisionGate {
    static final double MAX_COORDINATE_ERROR_PIXELS = 1.0 / 16.0;
    static final double MAX_SMOOTH_ERROR = 0.01;
    private static final double LOG_MARGIN = 1e-9;

    static boolean supportsGrid(RenderGrid grid, int width, int height) {
        if (width < 1 || height < 1) return false;
        for (int axis = 0; axis < 2; axis++) {
            int length = axis == 0 ? width : height;
            double step = axis == 0 ? grid.realStep() : grid.imaginaryStep();
            if (!Double.isFinite(step) || step <= 0) return false;
            float previous = Float.NaN;
            for (int i = 0; i < length; i++) {
                double value = axis == 0 ? grid.realAt(i) : grid.imaginaryAt(i);
                float rounded = (float) value;
                if (!Double.isFinite(value) || Math.abs(value) > 4 || rounded == previous
                        || Math.abs(rounded - value) / step > MAX_COORDINATE_ERROR_PIXELS) return false;
                previous = rounded;
            }
        }
        return true;
    }

    static void pack(float[] input, int index, double real, double imaginary) {
        if (!Double.isFinite(real) || !Double.isFinite(imaginary) || Math.abs(real) > 4 || Math.abs(imaginary) > 4) {
            throw new IllegalArgumentException("Probe supports finite coordinates in [-4, 4]");
        }
        int offset = index * VulkanMandelbrotKernel.INPUT_WORDS;
        input[offset] = (float) real;
        input[offset + 1] = (float) imaginary;
        input[offset + 2] = lower(real);
        input[offset + 3] = upper(real);
        input[offset + 4] = lower(imaginary);
        input[offset + 5] = upper(imaginary);
    }

    static float lower(double value) {
        float f = (float) value;
        f = f > value ? Math.nextDown(f) : f;
        return f != 0 && Math.abs(f) < Float.MIN_NORMAL ? -Float.MIN_NORMAL : f;
    }

    static float upper(double value) {
        float f = (float) value;
        f = f < value ? Math.nextUp(f) : f;
        return f != 0 && Math.abs(f) < Float.MIN_NORMAL ? Float.MIN_NORMAL : f;
    }

    static FractalSample sample(int[] output, int index) {
        int offset = index * VulkanMandelbrotKernel.OUTPUT_WORDS;
        return new FractalSample(output[offset], output[offset + 1] != 0,
                Float.intBitsToFloat(output[offset + 2]), Float.intBitsToFloat(output[offset + 3]));
    }

    /** Certifies and converts a sample once, without allocating an intermediate sample object. */
    static boolean certify(int[] output, int index, int limit, boolean gridAllowed,
                           MandelbrotStaging staging) {
        if (!gridAllowed) {
            if (staging != null) staging.reject(index);
            return false;
        }
        int o = index * VulkanMandelbrotKernel.OUTPUT_WORDS;
        int iteration = output[o], escaped = output[o + 1], status = output[o + 4], boundedIteration = output[o + 5];
        double zr = Float.intBitsToFloat(output[o + 2]);
        double zi = Float.intBitsToFloat(output[o + 3]);
        boolean accepted = iteration >= 0 && iteration <= limit && iteration == boundedIteration
                && Double.isFinite(zr) && Double.isFinite(zi);
        double candidateSmooth = iteration;
        if (accepted && status == 2) {
            accepted = iteration == limit && escaped == 0;
        } else if (accepted && status == 1 && escaped == 1 && iteration < limit) {
            double low = Float.intBitsToFloat(output[o + 6]);
            double high = Float.intBitsToFloat(output[o + 7]);
            candidateSmooth = smooth(iteration, zr * zr + zi * zi);
            double minimum = smooth(iteration, high), maximum = smooth(iteration, low);
            // CPU logs are outside the shader; the interval certificate is independent of the CPU orbit oracle.
            accepted = low > 4.0 && Double.isFinite(high) && low <= high
                    && Double.isFinite(candidateSmooth)
                    && Math.max(Math.abs(candidateSmooth - minimum), Math.abs(candidateSmooth - maximum))
                    <= MAX_SMOOTH_ERROR - LOG_MARGIN;
        } else {
            accepted = false;
        }
        if (staging != null) {
            if (accepted) staging.certify(index, iteration, candidateSmooth, escaped != 0);
            else staging.reject(index);
        }
        return accepted;
    }

    static boolean accepts(int[] output, int index, int limit, boolean gridAllowed) {
        return certify(output, index, limit, gridAllowed, null);
    }

    private static double smooth(int iteration, double squaredRadius) {
        return iteration + 1.0 - Math.log(Math.log(Math.sqrt(squaredRadius))) / Math.log(2.0);
    }

    static boolean conforms(FractalSample reference, FractalSample candidate) {
        if (reference.escaped() != candidate.escaped()) return false;
        if (!reference.escaped()) return reference.iterations() == candidate.iterations();
        return reference.iterations() == candidate.iterations()
                && Double.isFinite(candidate.smoothIterations())
                && Math.abs(reference.smoothIterations() - candidate.smoothIterations()) <= MAX_SMOOTH_ERROR;
    }
}
