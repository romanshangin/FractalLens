package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;

import java.util.Arrays;

/** Compares full-frame Mandelbrot rendering with conjugate-row reuse. */
public final class MandelbrotSymmetryBenchmark {

    private static final int WIDTH = Integer.getInteger("symmetryBenchmark.width", 1920);
    private static final int HEIGHT = Integer.getInteger("symmetryBenchmark.height", 1080);
    private static final int MAX_ITERATIONS = Integer.getInteger("symmetryBenchmark.iterations", 300);
    private static final int WARMUPS = Integer.getInteger("symmetryBenchmark.warmups", 3);
    private static final int RUNS = Integer.getInteger("symmetryBenchmark.runs", 7);

    private MandelbrotSymmetryBenchmark() {}

    public static void main(String[] args) throws InterruptedException {
        FractalFormula delegate = FractalPreset.MANDELBROT.createFormula();
        FractalCalculator fullCalculator = new FractalCalculator(
                new SymmetryOverrideFormula(delegate, false)
        );
        FractalCalculator symmetricCalculator = new FractalCalculator(
                new SymmetryOverrideFormula(delegate, true)
        );
        Viewport viewport = FractalPreset.MANDELBROT.defaultViewport();

        try (ParallelFractalCalculator parallelCalculator = new ParallelFractalCalculator()) {
            for (int i = 0; i < WARMUPS; i++) {
                render(parallelCalculator, fullCalculator, viewport);
                render(parallelCalculator, symmetricCalculator, viewport);
            }

            double[] fullTimes = new double[RUNS];
            double[] symmetricTimes = new double[RUNS];

            for (int i = 0; i < RUNS; i++) {
                fullTimes[i] = render(parallelCalculator, fullCalculator, viewport);
                symmetricTimes[i] = render(parallelCalculator, symmetricCalculator, viewport);
            }

            Arrays.sort(fullTimes);
            Arrays.sort(symmetricTimes);

            double fullMedian = fullTimes[RUNS / 2];
            double symmetricMedian = symmetricTimes[RUNS / 2];

            System.out.printf(
                    "Mandelbrot symmetry benchmark: %dx%d, maxIterations=%d, runs=%d%n",
                    WIDTH,
                    HEIGHT,
                    MAX_ITERATIONS,
                    RUNS
            );
            System.out.printf("Full-frame p50: %.2f ms%n", fullMedian);
            System.out.printf("Symmetric p50:  %.2f ms%n", symmetricMedian);
            System.out.printf("Speedup:        %.2fx%n", fullMedian / symmetricMedian);
        }
    }

    private static double render(
            ParallelFractalCalculator parallelCalculator,
            FractalCalculator calculator,
            Viewport viewport
    ) throws InterruptedException {
        RenderFrame frame = RenderFrame.create(
                new RenderRequest(
                        calculator,
                        viewport,
                        WIDTH,
                        HEIGHT,
                        MAX_ITERATIONS
                )
        );
        long start = System.nanoTime();

        parallelCalculator.calculate(frame, () -> false, ignored -> {});

        if (!frame.isComplete()) {
            throw new IllegalStateException("Benchmark frame is incomplete");
        }

        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private record SymmetryOverrideFormula(
            FractalFormula delegate,
            boolean hasConjugateSymmetry
    ) implements FractalFormula {

        @Override
        public FractalSample calculate(double real, double imaginary, int maxIterations) {
            return delegate.calculate(real, imaginary, maxIterations);
        }
    }
}
