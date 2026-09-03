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

    private static float lower(double value) {
        float f = (float) value;
        f = f > value ? Math.nextDown(f) : f;
        return f != 0 && Math.abs(f) < Float.MIN_NORMAL ? -Float.MIN_NORMAL : f;
    }

    private static float upper(double value) {
        float f = (float) value;
        f = f < value ? Math.nextUp(f) : f;
        return f != 0 && Math.abs(f) < Float.MIN_NORMAL ? Float.MIN_NORMAL : f;
    }

    static FractalSample sample(int[] output, int index) {
        int offset = index * VulkanMandelbrotKernel.OUTPUT_WORDS;
        return new FractalSample(output[offset], output[offset + 1] != 0,
                Float.intBitsToFloat(output[offset + 2]), Float.intBitsToFloat(output[offset + 3]));
    }

    static boolean accepts(int[] output, int index, int limit, boolean gridAllowed) {
        if (!gridAllowed) return false;
        int o = index * VulkanMandelbrotKernel.OUTPUT_WORDS;
        int iteration = output[o], escaped = output[o + 1], status = output[o + 4], boundedIteration = output[o + 5];
        if (iteration < 0 || iteration > limit || iteration != boundedIteration) return false;
        if (!Float.isFinite(Float.intBitsToFloat(output[o + 2])) || !Float.isFinite(Float.intBitsToFloat(output[o + 3]))) return false;
        if (status == 2) return iteration == limit && escaped == 0;
        if (status != 1 || escaped != 1 || iteration >= limit) return false;
        double low = Float.intBitsToFloat(output[o + 6]), high = Float.intBitsToFloat(output[o + 7]);
        if (!(low > 4.0) || !Double.isFinite(high) || low > high) return false;
        double smooth = sample(output, index).smoothIterations();
        double minimum = smooth(iteration, high), maximum = smooth(iteration, low);
        // CPU logs are outside the shader; the interval certificate is independent of the CPU orbit oracle.
        return Double.isFinite(smooth) && Math.max(Math.abs(smooth - minimum), Math.abs(smooth - maximum))
                <= MAX_SMOOTH_ERROR - LOG_MARGIN;
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
