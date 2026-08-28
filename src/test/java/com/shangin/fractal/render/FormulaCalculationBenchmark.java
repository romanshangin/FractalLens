package com.shangin.fractal.render;

import com.shangin.fractal.config.AdaptiveIterationPolicy;
import com.shangin.fractal.config.IterationPolicy;
import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Single-threaded baseline benchmark for the Mandelbrot and Julia calculation
 * kernels. Keeping scheduling and coloring out of the measurement makes future
 * formula-level optimizations directly comparable.
 */
public final class FormulaCalculationBenchmark {

    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;
    private static final int DEFAULT_BASE_ITERATIONS = 300;
    private static final int DEFAULT_WARMUPS = 1;
    private static final int DEFAULT_RUNS = 3;
    private static final int ITERATIONS_PER_ZOOM_LEVEL = 50;

    private FormulaCalculationBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);

        int width = integerProperty("formulaBenchmark.width", DEFAULT_WIDTH);
        int height = integerProperty("formulaBenchmark.height", DEFAULT_HEIGHT);
        int baseIterations = integerProperty(
                "formulaBenchmark.baseIterations",
                DEFAULT_BASE_ITERATIONS
        );
        int warmups = integerProperty("formulaBenchmark.warmups", DEFAULT_WARMUPS);
        int runs = integerProperty("formulaBenchmark.runs", DEFAULT_RUNS);

        System.out.printf(
                "Formula benchmark: %,dx%,d, baseIterations=%d, warmups=%d, runs=%d%n%n",
                width,
                height,
                baseIterations,
                warmups,
                runs
        );
        System.out.println(
                "formula     zoom maxIter time-p50 time-p95 ns/pixel   Mpx/s "
                        + "iter-mean iter-p50 iter-p95 interior"
        );

        for (Scenario scenario : scenarios()) {
            runScenario(
                    scenario,
                    width,
                    height,
                    baseIterations,
                    warmups,
                    runs
            );
        }
    }

    private static List<Scenario> scenarios() {
        return List.of(
                scenario(FractalPreset.MANDELBROT, "Mandelbrot", 1.0, -0.75, 0.0),
                scenario(FractalPreset.MANDELBROT, "Mandelbrot", 100.0, -0.743643887037151, 0.13182590420533),
                scenario(FractalPreset.MANDELBROT, "Mandelbrot", 10_000.0, -0.743643887037151, 0.13182590420533),
                scenario(FractalPreset.JULIA, "Julia", 1.0, 0.0, 0.0),
                scenario(FractalPreset.JULIA, "Julia", 100.0, 0.0, 0.0),
                scenario(FractalPreset.JULIA, "Julia", 10_000.0, 0.0, 0.0)
        );
    }

    private static Scenario scenario(
            FractalPreset preset,
            String name,
            double zoom,
            double centerReal,
            double centerImaginary
    ) {
        Viewport defaultViewport = preset.defaultViewport();
        Viewport viewport = new Viewport(
                centerReal,
                centerImaginary,
                defaultViewport.scale() / zoom
        );

        return new Scenario(
                name,
                preset.createFormula(),
                defaultViewport.scale(),
                viewport,
                zoom
        );
    }

    private static void runScenario(
            Scenario scenario,
            int width,
            int height,
            int baseIterations,
            int warmups,
            int runs
    ) {
        IterationPolicy iterationPolicy =
                new AdaptiveIterationPolicy(ITERATIONS_PER_ZOOM_LEVEL);
        int maxIterations = iterationPolicy.maxIterations(
                baseIterations,
                scenario.defaultScale(),
                scenario.viewport().scale()
        );
        FractalCalculator calculator = new FractalCalculator(scenario.formula());
        RenderGrid grid = RenderGrid.from(scenario.viewport(), width, height);

        for (int i = 0; i < warmups; i++) {
            calculator.calculate(width, height, grid, maxIterations);
        }

        List<Long> elapsedNanos = new ArrayList<>();
        FractalData lastData = null;

        for (int i = 0; i < runs; i++) {
            long started = System.nanoTime();
            lastData = calculator.calculate(width, height, grid, maxIterations);
            elapsedNanos.add(System.nanoTime() - started);
        }

        Distribution distribution = Distribution.from(lastData);
        double p50Nanos = percentile(elapsedNanos, 0.50);
        double p95Nanos = percentile(elapsedNanos, 0.95);
        long pixelCount = (long) width * height;
        double nsPerPixel = p50Nanos / pixelCount;
        double megapixelsPerSecond = pixelCount / p50Nanos * 1_000.0;

        System.out.printf(
                "%-10s %6.0fx %7d %8.2f %8.2f %8.2f %7.2f "
                        + "%9.2f %8d %8d %7.2f%%%n",
                scenario.name(),
                scenario.zoom(),
                maxIterations,
                toMs(p50Nanos),
                toMs(p95Nanos),
                nsPerPixel,
                megapixelsPerSecond,
                distribution.meanIterations(),
                distribution.p50Iterations(),
                distribution.p95Iterations(),
                distribution.interiorPercent()
        );
    }

    private static double percentile(List<Long> source, double percentile) {
        long[] values = source.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(percentile * values.length) - 1;
        return values[Math.clamp(index, 0, values.length - 1)];
    }

    private static double toMs(double nanos) {
        return nanos / 1_000_000.0;
    }

    private static int integerProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    private record Scenario(
            String name,
            FractalFormula formula,
            double defaultScale,
            Viewport viewport,
            double zoom
    ) {}

    private record Distribution(
            double meanIterations,
            int p50Iterations,
            int p95Iterations,
            double interiorPercent
    ) {
        static Distribution from(FractalData data) {
            int[] iterations = new int[data.size()];
            long iterationSum = 0L;
            int interiorCount = 0;

            for (int index = 0; index < data.size(); index++) {
                int value = data.iterations(index);
                iterations[index] = value;
                iterationSum += value;

                if (!data.escaped(index)) {
                    interiorCount++;
                }
            }

            Arrays.sort(iterations);

            return new Distribution(
                    iterationSum / (double) data.size(),
                    iterations[iterations.length / 2],
                    iterations[(int) Math.ceil(iterations.length * 0.95) - 1],
                    interiorCount * 100.0 / data.size()
            );
        }
    }
}
