package com.shangin.fractal.render;

import com.shangin.fractal.formula.DistanceSample;
import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.util.List;
import java.util.Locale;

/**
 * Compares distance-estimation boundary detection with regular 4x4 coverage.
 * Run with {@code mvn verify -Pdistance-estimation-benchmark}.
 */
public final class DistanceEstimationBenchmark {

    private static final int DEFAULT_WIDTH = 320;
    private static final int DEFAULT_HEIGHT = 200;
    private static final int DEFAULT_ITERATIONS = 500;
    private static final int SAMPLE_GRID = 4;

    private DistanceEstimationBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);

        int width = integerProperty("distanceBenchmark.width", DEFAULT_WIDTH);
        int height = integerProperty("distanceBenchmark.height", DEFAULT_HEIGHT);
        int maxIterations = integerProperty(
                "distanceBenchmark.iterations",
                DEFAULT_ITERATIONS
        );

        System.out.printf(
                "Distance estimation benchmark: %,dx%,d, maxIterations=%d%n%n",
                width,
                height,
                maxIterations
        );
        System.out.println(
                "formula       zoom base-ms   DE-ms   4x4-ms DE/base 4x4/base "
                        + "mixed precision recall candidates"
        );

        for (Scenario scenario : scenarios()) {
            runScenario(scenario, width, height, maxIterations);
        }
    }

    private static List<Scenario> scenarios() {
        return List.of(
                scenario(FractalPreset.MANDELBROT, "Mandelbrot", 1.0, -0.75, 0.0),
                scenario(
                        FractalPreset.MANDELBROT,
                        "Mandelbrot",
                        1_000.0,
                        -0.743643887037151,
                        0.13182590420533
                ),
                scenario(FractalPreset.JULIA, "Julia", 1.0, 0.0, 0.0),
                scenario(FractalPreset.MULTIBROT_CUBIC, "Multibrot3", 1.0, 0.0, 0.0)
        );
    }

    private static Scenario scenario(
            FractalPreset preset,
            String name,
            double zoom,
            double centerReal,
            double centerImaginary
    ) {
        Viewport defaults = preset.defaultViewport();
        return new Scenario(
                name,
                preset.createFormula(),
                new Viewport(
                        centerReal,
                        centerImaginary,
                        defaults.scale() / zoom
                ),
                zoom
        );
    }

    private static void runScenario(
            Scenario scenario,
            int width,
            int height,
            int maxIterations
    ) {
        FractalCalculator calculator = new FractalCalculator(scenario.formula());
        RenderGrid grid = RenderGrid.from(scenario.viewport(), width, height);

        // Warm up both kernels before measuring.
        measureBase(calculator, grid, width, height, maxIterations);
        measureDistance(calculator, grid, width, height, maxIterations);

        long baseNanos = measureBase(calculator, grid, width, height, maxIterations);
        DistanceResult distance = measureDistance(
                calculator,
                grid,
                width,
                height,
                maxIterations
        );
        CoverageResult coverage = measureCoverage(
                calculator,
                grid,
                width,
                height,
                maxIterations
        );

        long truePositives = 0;
        long falsePositives = 0;
        long falseNegatives = 0;
        for (int index = 0; index < distance.boundaryCandidates().length; index++) {
            boolean predicted = distance.boundaryCandidates()[index];
            boolean mixed = coverage.mixedPixels()[index];
            if (predicted && mixed) {
                truePositives++;
            } else if (predicted) {
                falsePositives++;
            } else if (mixed) {
                falseNegatives++;
            }
        }

        double precision = ratio(truePositives, truePositives + falsePositives);
        double recall = ratio(truePositives, truePositives + falseNegatives);
        long mixedCount = truePositives + falseNegatives;

        System.out.printf(
                "%-12s %6.0fx %7.1f %7.1f %8.1f %7.2fx %8.2fx "
                        + "%5d %8.1f%% %5.1f%% %10d%n",
                scenario.name(),
                scenario.zoom(),
                millis(baseNanos),
                millis(distance.elapsedNanos()),
                millis(coverage.elapsedNanos()),
                (double) distance.elapsedNanos() / baseNanos,
                (double) coverage.elapsedNanos() / baseNanos,
                mixedCount,
                precision * 100.0,
                recall * 100.0,
                distance.candidateCount()
        );
    }

    private static long measureBase(
            FractalCalculator calculator,
            RenderGrid grid,
            int width,
            int height,
            int maxIterations
    ) {
        long checksum = 0;
        long started = System.nanoTime();
        for (int y = 0; y < height; y++) {
            double imaginary = grid.imaginaryAt(y);
            for (int x = 0; x < width; x++) {
                checksum += calculator.calculateSample(
                        grid.realAt(x),
                        imaginary,
                        maxIterations
                ).iterations();
            }
        }
        consume(checksum);
        return System.nanoTime() - started;
    }

    private static DistanceResult measureDistance(
            FractalCalculator calculator,
            RenderGrid grid,
            int width,
            int height,
            int maxIterations
    ) {
        boolean[] candidates = new boolean[width * height];
        double pixelRadius = 0.5 * Math.hypot(grid.realStep(), grid.imaginaryStep());
        int candidateCount = 0;
        long checksum = 0;
        long started = System.nanoTime();

        for (int y = 0; y < height; y++) {
            double imaginary = grid.imaginaryAt(y);
            for (int x = 0; x < width; x++) {
                DistanceSample result = calculator.calculateDistanceSample(
                        grid.realAt(x),
                        imaginary,
                        maxIterations
                );
                boolean candidate = result.hasDistance()
                        && result.distance() <= pixelRadius;
                candidates[y * width + x] = candidate;
                candidateCount += candidate ? 1 : 0;
                checksum += result.sample().iterations();
            }
        }
        consume(checksum);
        return new DistanceResult(
                System.nanoTime() - started,
                candidates,
                candidateCount
        );
    }

    private static CoverageResult measureCoverage(
            FractalCalculator calculator,
            RenderGrid grid,
            int width,
            int height,
            int maxIterations
    ) {
        boolean[] mixedPixels = new boolean[width * height];
        long checksum = 0;
        long started = System.nanoTime();

        for (int y = 0; y < height; y++) {
            double centerImaginary = grid.imaginaryAt(y);
            for (int x = 0; x < width; x++) {
                double centerReal = grid.realAt(x);
                boolean firstEscaped = false;
                boolean firstSet = false;
                boolean mixed = false;

                for (int sampleY = 0; sampleY < SAMPLE_GRID; sampleY++) {
                    double offsetY = (sampleY + 0.5) / SAMPLE_GRID - 0.5;
                    for (int sampleX = 0; sampleX < SAMPLE_GRID; sampleX++) {
                        double offsetX = (sampleX + 0.5) / SAMPLE_GRID - 0.5;
                        var sample = calculator.calculateSample(
                                centerReal + offsetX * grid.realStep(),
                                centerImaginary - offsetY * grid.imaginaryStep(),
                                maxIterations
                        );
                        checksum += sample.iterations();
                        if (!firstSet) {
                            firstEscaped = sample.escaped();
                            firstSet = true;
                        } else if (sample.escaped() != firstEscaped) {
                            mixed = true;
                        }
                    }
                }
                mixedPixels[y * width + x] = mixed;
            }
        }
        consume(checksum);
        return new CoverageResult(System.nanoTime() - started, mixedPixels);
    }

    private static int integerProperty(String name, int fallback) {
        return Integer.getInteger(name, fallback);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static volatile long blackhole;

    private static void consume(long value) {
        blackhole = value;
    }

    private record Scenario(
            String name,
            FractalFormula formula,
            Viewport viewport,
            double zoom
    ) {}

    private record DistanceResult(
            long elapsedNanos,
            boolean[] boundaryCandidates,
            int candidateCount
    ) {}

    private record CoverageResult(long elapsedNanos, boolean[] mixedPixels) {}
}
