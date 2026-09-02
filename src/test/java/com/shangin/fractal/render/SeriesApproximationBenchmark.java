package com.shangin.fractal.render;

import java.util.Arrays;
import java.util.Locale;

/**
 * Measures a cubic perturbation series as a way to skip the first orbit
 * iterations. This is intentionally benchmark-only until accuracy and
 * end-to-end throughput justify production integration.
 */
public final class SeriesApproximationBenchmark {

    private static final int WIDTH = Integer.getInteger("series.width", 480);
    private static final int HEIGHT = Integer.getInteger("series.height", 270);
    private static final int MAX_ITERATIONS = Integer.getInteger("series.iterations", 964);
    private static final int WARMUPS = Integer.getInteger("series.warmups", 1);
    private static final int RUNS = Integer.getInteger("series.runs", 3);
    private static final double CENTER_REAL = -0.743643887037151;
    private static final double CENTER_IMAGINARY = 0.13182590420533;
    private static final double SMOOTH_TOLERANCE = 1e-6;

    private SeriesApproximationBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.printf(
                "Series approximation benchmark: %dx%d, maxIterations=%d, warmups=%d, runs=%d%n",
                WIDTH, HEIGHT, MAX_ITERATIONS, WARMUPS, RUNS);
        System.out.println(
                "zoom skip perturb-p50 series-p50 speedup iteration-mismatches "
                        + "smooth-mismatches max-smooth-error");

        for (double zoom : new double[]{1e8, 1e12, 1e16}) {
            for (int skip : new int[]{16, 32, 64}) {
                benchmark(zoom, skip);
            }
        }
    }

    private static void benchmark(double zoom, int skip) {
        double scale = 2.4 / zoom;
        ReferenceOrbit reference = ReferenceOrbit.calculate(MAX_ITERATIONS);
        SeriesCoefficients coefficients = SeriesCoefficients.calculate(reference, skip);

        for (int warmup = 0; warmup < WARMUPS; warmup++) {
            calculatePerturbation(WIDTH, HEIGHT, scale, reference, MAX_ITERATIONS);
            calculateSeries(WIDTH, HEIGHT, scale, reference, coefficients, MAX_ITERATIONS);
        }

        long[] perturbationTimes = new long[RUNS];
        long[] seriesTimes = new long[RUNS];
        Result perturbation = null;
        Result series = null;
        for (int run = 0; run < RUNS; run++) {
            long started = System.nanoTime();
            perturbation = calculatePerturbation(
                    WIDTH, HEIGHT, scale, reference, MAX_ITERATIONS);
            perturbationTimes[run] = System.nanoTime() - started;

            started = System.nanoTime();
            series = calculateSeries(
                    WIDTH, HEIGHT, scale, reference, coefficients, MAX_ITERATIONS);
            seriesTimes[run] = System.nanoTime() - started;
        }

        Accuracy accuracy = compare(perturbation, series, SMOOTH_TOLERANCE);
        Arrays.sort(perturbationTimes);
        Arrays.sort(seriesTimes);
        double perturbationMs = toMs(perturbationTimes[RUNS / 2]);
        double seriesMs = toMs(seriesTimes[RUNS / 2]);
        System.out.printf(
                "%8.0e %4d %15.2f %10.2f %7.3fx %,20d %,17d %.3e%n",
                zoom, skip, perturbationMs, seriesMs, perturbationMs / seriesMs,
                accuracy.iterationMismatches(), accuracy.smoothMismatches(),
                accuracy.maxSmoothError());
    }

    static Result calculatePerturbation(
            int width,
            int height,
            double scale,
            ReferenceOrbit reference,
            int maxIterations
    ) {
        Result result = new Result(width * height);
        double viewportWidth = scale * width / height;
        for (int y = 0; y < height; y++) {
            double deltaCi = scale / 2.0 - scale * y / (height - 1);
            for (int x = 0; x < width; x++) {
                double deltaCr = -viewportWidth / 2.0
                        + viewportWidth * x / (width - 1);
                iterate(result, y * width + x, reference, deltaCr, deltaCi,
                        0.0, 0.0, 0, maxIterations);
            }
        }
        return result;
    }

    static Result calculateSeries(
            int width,
            int height,
            double scale,
            ReferenceOrbit reference,
            SeriesCoefficients coefficients,
            int maxIterations
    ) {
        Result result = new Result(width * height);
        double viewportWidth = scale * width / height;
        for (int y = 0; y < height; y++) {
            double deltaCi = scale / 2.0 - scale * y / (height - 1);
            for (int x = 0; x < width; x++) {
                double deltaCr = -viewportWidth / 2.0
                        + viewportWidth * x / (width - 1);
                Complex delta = coefficients.evaluate(deltaCr, deltaCi);
                iterate(result, y * width + x, reference, deltaCr, deltaCi,
                        delta.real(), delta.imaginary(), coefficients.iteration(),
                        maxIterations);
            }
        }
        return result;
    }

    private static void iterate(
            Result result,
            int index,
            ReferenceOrbit reference,
            double deltaCr,
            double deltaCi,
            double initialDeltaReal,
            double initialDeltaImaginary,
            int initialIteration,
            int maxIterations
    ) {
        double deltaReal = initialDeltaReal;
        double deltaImaginary = initialDeltaImaginary;
        double zr = reference.real()[initialIteration] + deltaReal;
        double zi = reference.imaginary()[initialIteration] + deltaImaginary;
        int iteration = initialIteration;
        while (iteration < maxIterations && zr * zr + zi * zi <= 4.0) {
            double referenceReal = reference.real()[iteration];
            double referenceImaginary = reference.imaginary()[iteration];
            double nextDeltaReal = 2.0 * (referenceReal * deltaReal
                    - referenceImaginary * deltaImaginary)
                    + deltaReal * deltaReal - deltaImaginary * deltaImaginary + deltaCr;
            deltaImaginary = 2.0 * (referenceReal * deltaImaginary
                    + referenceImaginary * deltaReal + deltaReal * deltaImaginary)
                    + deltaCi;
            deltaReal = nextDeltaReal;
            iteration++;
            zr = reference.real()[iteration] + deltaReal;
            zi = reference.imaginary()[iteration] + deltaImaginary;
        }
        result.iterations()[index] = iteration;
        result.smoothIterations()[index] = iteration < maxIterations
                ? iteration + 1.0 - Math.log(Math.log(Math.hypot(zr, zi))) / Math.log(2.0)
                : iteration;
    }

    static Accuracy compare(Result expected, Result actual, double smoothTolerance) {
        int iterationMismatches = 0;
        int smoothMismatches = 0;
        double maxSmoothError = 0.0;
        for (int index = 0; index < expected.iterations().length; index++) {
            if (expected.iterations()[index] != actual.iterations()[index]) {
                iterationMismatches++;
                continue;
            }
            double error = Math.abs(expected.smoothIterations()[index]
                    - actual.smoothIterations()[index]);
            maxSmoothError = Math.max(maxSmoothError, error);
            if (error > smoothTolerance) {
                smoothMismatches++;
            }
        }
        return new Accuracy(iterationMismatches, smoothMismatches, maxSmoothError);
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    record Result(int[] iterations, double[] smoothIterations) {
        Result(int size) {
            this(new int[size], new double[size]);
        }
    }

    record Accuracy(
            int iterationMismatches,
            int smoothMismatches,
            double maxSmoothError
    ) {}

    record Complex(double real, double imaginary) {}

    record ReferenceOrbit(double[] real, double[] imaginary) {
        static ReferenceOrbit calculate(int maxIterations) {
            double[] real = new double[maxIterations + 1];
            double[] imaginary = new double[maxIterations + 1];
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                real[iteration + 1] = real[iteration] * real[iteration]
                        - imaginary[iteration] * imaginary[iteration] + CENTER_REAL;
                imaginary[iteration + 1] = 2.0 * real[iteration]
                        * imaginary[iteration] + CENTER_IMAGINARY;
            }
            return new ReferenceOrbit(real, imaginary);
        }
    }

    /** Coefficients A, B, and C in delta-z = A*d + B*d^2 + C*d^3. */
    record SeriesCoefficients(
            int iteration,
            double aReal,
            double aImaginary,
            double bReal,
            double bImaginary,
            double cReal,
            double cImaginary
    ) {
        static SeriesCoefficients calculate(ReferenceOrbit reference, int skip) {
            if (skip < 1 || skip >= reference.real().length) {
                throw new IllegalArgumentException("Series skip is outside the reference orbit");
            }
            double ar = 0.0;
            double ai = 0.0;
            double br = 0.0;
            double bi = 0.0;
            double cr = 0.0;
            double ci = 0.0;
            for (int iteration = 0; iteration < skip; iteration++) {
                double zr = reference.real()[iteration];
                double zi = reference.imaginary()[iteration];

                double nextAr = 2.0 * (zr * ar - zi * ai) + 1.0;
                double nextAi = 2.0 * (zr * ai + zi * ar);
                double nextBr = 2.0 * (zr * br - zi * bi) + ar * ar - ai * ai;
                double nextBi = 2.0 * (zr * bi + zi * br) + 2.0 * ar * ai;
                double nextCr = 2.0 * (zr * cr - zi * ci)
                        + 2.0 * (ar * br - ai * bi);
                double nextCi = 2.0 * (zr * ci + zi * cr)
                        + 2.0 * (ar * bi + ai * br);
                ar = nextAr;
                ai = nextAi;
                br = nextBr;
                bi = nextBi;
                cr = nextCr;
                ci = nextCi;
            }
            return new SeriesCoefficients(skip, ar, ai, br, bi, cr, ci);
        }

        Complex evaluate(double deltaReal, double deltaImaginary) {
            double squareReal = deltaReal * deltaReal - deltaImaginary * deltaImaginary;
            double squareImaginary = 2.0 * deltaReal * deltaImaginary;
            double cubeReal = squareReal * deltaReal - squareImaginary * deltaImaginary;
            double cubeImaginary = squareReal * deltaImaginary + squareImaginary * deltaReal;
            return new Complex(
                    aReal * deltaReal - aImaginary * deltaImaginary
                            + bReal * squareReal - bImaginary * squareImaginary
                            + cReal * cubeReal - cImaginary * cubeImaginary,
                    aReal * deltaImaginary + aImaginary * deltaReal
                            + bReal * squareImaginary + bImaginary * squareReal
                            + cReal * cubeImaginary + cImaginary * cubeReal);
        }
    }
}
