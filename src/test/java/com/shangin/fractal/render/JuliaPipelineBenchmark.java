package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Compares the generic formula pipeline with a direct Julia data pipeline. */
public final class JuliaPipelineBenchmark {

    private static final double C_REAL = -0.8;
    private static final double C_IMAGINARY = 0.156;
    private static final double LOG_2 = Math.log(2.0);
    private static final int WIDTH = Integer.getInteger("juliaPipeline.width", 960);
    private static final int HEIGHT = Integer.getInteger("juliaPipeline.height", 540);
    private static final int WARMUPS = Integer.getInteger("juliaPipeline.warmups", 2);
    private static final int RUNS = Integer.getInteger("juliaPipeline.runs", 5);

    private JuliaPipelineBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        FractalCalculator genericCalculator = new FractalCalculator(
                FractalPreset.JULIA.createFormula()
        );

        System.out.printf(
                "Julia pipeline benchmark: %dx%d, warmups=%d, runs=%d%n",
                WIDTH,
                HEIGHT,
                WARMUPS,
                RUNS
        );
        System.out.println("zoom maxIter generic-p50 direct-p50 speedup");

        for (Scenario scenario : scenarios()) {
            benchmark(genericCalculator, scenario);
        }
    }

    private static void benchmark(
            FractalCalculator genericCalculator,
            Scenario scenario
    ) {
        RenderGrid grid = RenderGrid.from(scenario.viewport(), WIDTH, HEIGHT);

        for (int i = 0; i < WARMUPS; i++) {
            genericCalculator.calculate(WIDTH, HEIGHT, grid, scenario.maxIterations());
            calculateDirect(grid, scenario.maxIterations());
        }

        long[] genericTimes = new long[RUNS];
        long[] directTimes = new long[RUNS];
        FractalData generic = null;
        Result direct = null;

        for (int i = 0; i < RUNS; i++) {
            if ((i & 1) == 0) {
                long started = System.nanoTime();
                generic = genericCalculator.calculate(
                        WIDTH,
                        HEIGHT,
                        grid,
                        scenario.maxIterations()
                );
                genericTimes[i] = System.nanoTime() - started;

                started = System.nanoTime();
                direct = calculateDirect(grid, scenario.maxIterations());
                directTimes[i] = System.nanoTime() - started;
            } else {
                long started = System.nanoTime();
                direct = calculateDirect(grid, scenario.maxIterations());
                directTimes[i] = System.nanoTime() - started;

                started = System.nanoTime();
                generic = genericCalculator.calculate(
                        WIDTH,
                        HEIGHT,
                        grid,
                        scenario.maxIterations()
                );
                genericTimes[i] = System.nanoTime() - started;
            }
        }

        verify(generic, direct);
        Arrays.sort(genericTimes);
        Arrays.sort(directTimes);
        double genericMs = toMs(genericTimes[RUNS / 2]);
        double directMs = toMs(directTimes[RUNS / 2]);

        System.out.printf(
                "%5.0fx %7d %11.2f %10.2f %7.2fx%n",
                scenario.zoom(),
                scenario.maxIterations(),
                genericMs,
                directMs,
                genericMs / directMs
        );
    }

    private static Result calculateDirect(
            RenderGrid grid,
            int maxIterations
    ) {
        Result result = new Result(WIDTH * HEIGHT);

        for (int y = 0; y < HEIGHT; y++) {
            double imaginary = grid.imaginaryAt(y);

            for (int x = 0; x < WIDTH; x++) {
                double zr = grid.realAt(x);
                double zi = imaginary;
                int iteration = 0;

                while (zr * zr + zi * zi <= 4.0 && iteration < maxIterations) {
                    double zrNew = zr * zr - zi * zi + C_REAL;
                    double ziNew = 2.0 * zr * zi + C_IMAGINARY;
                    zr = zrNew;
                    zi = ziNew;
                    iteration++;
                }

                int index = y * WIDTH + x;
                boolean escaped = iteration < maxIterations;
                result.iterations[index] = iteration;
                result.escaped[index] = escaped;
                result.smoothIterations[index] = escaped
                        ? iteration + 1.0
                                - Math.log(Math.log(Math.sqrt(zr * zr + zi * zi))) / LOG_2
                        : iteration;
            }
        }

        return result;
    }

    private static void verify(FractalData generic, Result direct) {
        for (int index = 0; index < generic.size(); index++) {
            if (generic.iterations(index) != direct.iterations[index]
                    || generic.escaped(index) != direct.escaped[index]
                    || Double.doubleToLongBits(generic.smoothIterations(index))
                            != Double.doubleToLongBits(direct.smoothIterations[index])) {
                throw new AssertionError("Pipeline mismatch at pixel " + index);
            }
        }
    }

    private static List<Scenario> scenarios() {
        return List.of(
                scenario(1.0, 300),
                scenario(100.0, 632),
                scenario(10_000.0, 964)
        );
    }

    private static Scenario scenario(double zoom, int maxIterations) {
        return new Scenario(
                zoom,
                maxIterations,
                new Viewport(0.0, 0.0, 2.4 / zoom)
        );
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record Scenario(double zoom, int maxIterations, Viewport viewport) {}

    private static final class Result {
        private final int[] iterations;
        private final double[] smoothIterations;
        private final boolean[] escaped;

        private Result(int size) {
            iterations = new int[size];
            smoothIterations = new double[size];
            escaped = new boolean[size];
        }
    }
}
