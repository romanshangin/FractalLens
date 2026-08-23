package com.shangin.fractal.render;

import com.shangin.fractal.formula.MandelbrotFormula;
import com.shangin.fractal.math.Viewport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FractalCalculatorBenchmark {

    //private static final int WIDTH = 1000;
    //private static final int HEIGHT = 700;

    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 7;

    private static final int[] ITERATION_COUNTS = {300, 1000, 2000};

    private static final int[][] RESOLUTIONS = {
            {1000, 704},
            {2000, 1408}
    };

    private static final int MAX_ITERATIONS = 2000;

    static void main() throws InterruptedException {

        int availableProcessors = Runtime.getRuntime().availableProcessors();

        int workerCount = Math.max(1, availableProcessors - 1);

        System.out.println("Available processors: " + availableProcessors);

        System.out.println("Parallel workers: " + workerCount);

        FractalCalculator sequentialCalculator = new FractalCalculator(new MandelbrotFormula());
        ParallelFractalCalculator parallelCalculator = new ParallelFractalCalculator();

        Viewport viewport = new Viewport(-0.75, 0.0, 2.4);

        for (int[] resolution : RESOLUTIONS) {
            int width = resolution[0];
            int height = resolution[1];

            runBenchmark(
                    sequentialCalculator,
                    parallelCalculator,
                    viewport,
                    width,
                    height,
                    MAX_ITERATIONS
            );
        }
    }

    private static void runBenchmark(
            FractalCalculator sequentialCalculator,
            ParallelFractalCalculator parallelCalculator,
            Viewport viewport,
            int width,
            int height,
            int maxIterations
    ) throws InterruptedException {

        System.out.printf("%nResolution: %dx%d%n", width, height);

        RenderRequest request =
                new RenderRequest(
                        sequentialCalculator,
                        viewport,
                        width,
                        height,
                        maxIterations
                );

        warmUpSequential(
                sequentialCalculator,
                viewport,
                width,
                height,
                maxIterations
        );

        warmUpParallel(
                parallelCalculator,
                request
        );

        List<Double> sequentialTimes =
                benchmarkSequential(
                        sequentialCalculator,
                        viewport,
                        width,
                        height,
                        maxIterations
                );

        List<Double> parallelTimes =
                benchmarkParallel(
                        parallelCalculator,
                        request
                );

        double sequentialMedian =
                median(sequentialTimes);

        double parallelMedian =
                median(parallelTimes);

        System.out.printf(
                "Sequential median: %.2f ms%n",
                sequentialMedian
        );

        System.out.printf(
                "Parallel median:   %.2f ms%n",
                parallelMedian
        );

        System.out.printf(
                "Speedup:           %.2fx%n",
                sequentialMedian / parallelMedian
        );
    }

    private static void warmUpSequential(
            FractalCalculator sequentialCalculator,
            Viewport viewport,
            int width,
            int height,
            int maxIterations) {
        System.out.println("Sequential warmup...");

        for (int i = 0; i < WARMUP_RUNS; i++) {

            sequentialCalculator.calculate(width, height, viewport, maxIterations);
        }
    }

    private static void warmUpParallel(ParallelFractalCalculator calculator, RenderRequest request) throws InterruptedException {

        System.out.println("Parallel warmup...");

        for (int i = 0; i < WARMUP_RUNS; i++) {

            calculator.calculate(request, () -> false);
        }
    }

    private static List<Double> benchmarkSequential(
            FractalCalculator calculator,
            Viewport viewport,
            int width,
            int height,
            int maxIterations
    ) {
        List<Double> times =
                new ArrayList<>();

        for (int i = 0; i < MEASURED_RUNS; i++) {
            long start =
                    System.nanoTime();

            calculator.calculate(
                    width,
                    height,
                    viewport,
                    maxIterations
            );

            double elapsedMs =
                    elapsedMilliseconds(start);

            times.add(elapsedMs);

            System.out.printf(
                    "Sequential #%d: %.2f ms%n",
                    i + 1,
                    elapsedMs
            );
        }

        return times;
    }

    private static List<Double> benchmarkParallel(ParallelFractalCalculator calculator, RenderRequest request) throws InterruptedException {

        List<Double> times = new ArrayList<>();

        for (int i = 0; i < MEASURED_RUNS; i++) {

            long start = System.nanoTime();

            calculator.calculate(request, () -> false);

            double elapsedMs = elapsedMilliseconds(start);

            times.add(elapsedMs);

            System.out.printf("Parallel #%d:   %.2f ms%n", i + 1, elapsedMs);
        }

        return times;
    }

    private static double elapsedMilliseconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);

        Collections.sort(sorted);

        int size = sorted.size();

        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }

        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    }
}