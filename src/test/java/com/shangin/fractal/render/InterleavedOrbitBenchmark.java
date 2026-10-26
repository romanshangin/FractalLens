package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paired single-threaded comparison of the scalar direct-double kernel with
 * the experimental 2-lane and 4-lane interleaved-orbit kernels on the formula
 * benchmark scenes. Every frame is first checked pixel for pixel against the
 * scalar output; any mismatch aborts the benchmark.
 */
public final class InterleavedOrbitBenchmark {

    private static final int TILE_SIZE = 32;
    private static final String[] VARIANTS = {"scalar", "2-lane", "4-lane"};

    private InterleavedOrbitBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);

        int width = integerProperty("interleavedOrbit.width", 1920);
        int height = integerProperty("interleavedOrbit.height", 1080);
        int baseIterations = integerProperty("interleavedOrbit.baseIterations", 300);
        int warmups = integerProperty("interleavedOrbit.warmups", 2);
        int rounds = integerProperty("interleavedOrbit.rounds", 7);
        String passes = System.getProperty("interleavedOrbit.passes", "full,tiled");

        System.out.printf(
                "Interleaved-orbit benchmark: %,dx%,d, baseIterations=%d, warmups=%d, rounds=%d, "
                        + "passes=%s, java=%s, arch=%s, processors=%d%n%n",
                width, height, baseIterations, warmups, rounds, passes,
                System.getProperty("java.vm.version"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.println(
                "pass  formula      zoom maxIter iter-mean interior "
                        + "scalar-p50 2lane-p50 4lane-p50 "
                        + "2lane-speedup(med/min/max) 4lane-speedup(med/min/max)");

        for (String pass : passes.split(",")) {
            boolean tiled = switch (pass.trim()) {
                case "full" -> false;
                case "tiled" -> true;
                default -> throw new IllegalArgumentException("Unknown pass: " + pass);
            };
            for (FormulaCalculationBenchmark.Scenario scenario : FormulaCalculationBenchmark.scenarios()) {
                runScenario(scenario, tiled, width, height, baseIterations, warmups, rounds);
            }
        }
    }

    private static void runScenario(
            FormulaCalculationBenchmark.Scenario scenario,
            boolean tiled,
            int width,
            int height,
            int baseIterations,
            int warmups,
            int rounds
    ) {
        int maxIterations = FormulaCalculationBenchmark.maxIterations(scenario, baseIterations);
        RenderGrid grid = RenderGrid.from(scenario.viewport(), width, height);
        FractalCalculator scalar = new FractalCalculator(scenario.formula());
        InterleavedOrbitKernel twoLanes = kernel(scenario.preset(), 2);
        InterleavedOrbitKernel fourLanes = kernel(scenario.preset(), 4);
        AtomicBoolean cancelled = new AtomicBoolean();

        List<Variant> variants = List.of(
                tiled
                        ? () -> tiledFrame(width, height, maxIterations, (data, tile) ->
                                scalar.calculateTile(data, grid, tile[0], tile[1], tile[2], tile[3],
                                        maxIterations, cancelled::get, (x, y) -> false, ignored -> {}))
                        : () -> scalar.calculate(width, height, grid, maxIterations),
                interleaved(twoLanes, tiled, width, height, grid, maxIterations, cancelled),
                interleaved(fourLanes, tiled, width, height, grid, maxIterations, cancelled));

        FractalData reference = variants.get(0).run();
        for (int variant = 1; variant < variants.size(); variant++) {
            requireIdentical(reference, variants.get(variant).run(),
                    scenario.name() + " " + scenario.zoom() + "x " + VARIANTS[variant]);
        }

        for (int warmup = 0; warmup < warmups; warmup++) {
            for (Variant variant : variants) {
                variant.run();
            }
        }

        long[][] elapsed = new long[variants.size()][rounds];
        for (int round = 0; round < rounds; round++) {
            for (int offset = 0; offset < variants.size(); offset++) {
                int variant = (round + offset) % variants.size();
                long started = System.nanoTime();
                variants.get(variant).run();
                elapsed[variant][round] = System.nanoTime() - started;
            }
        }

        double[] twoLaneSpeedups = speedups(elapsed[0], elapsed[1]);
        double[] fourLaneSpeedups = speedups(elapsed[0], elapsed[2]);
        System.out.printf(
                "%-5s %-10s %6.0fx %7d %9.2f %7.2f%% %10.2f %9.2f %9.2f "
                        + "%8.3f/%5.3f/%5.3f %12.3f/%5.3f/%5.3f%n",
                tiled ? "tiled" : "full",
                scenario.name(),
                scenario.zoom(),
                maxIterations,
                meanIterations(reference),
                interiorPercent(reference),
                toMs(median(elapsed[0])),
                toMs(median(elapsed[1])),
                toMs(median(elapsed[2])),
                median(twoLaneSpeedups), min(twoLaneSpeedups), max(twoLaneSpeedups),
                median(fourLaneSpeedups), min(fourLaneSpeedups), max(fourLaneSpeedups));
    }

    private static InterleavedOrbitKernel kernel(FractalPreset preset, int lanes) {
        return switch (preset) {
            case MANDELBROT -> InterleavedOrbitKernel.mandelbrot(lanes);
            case JULIA -> {
                Map<String, Double> parameters =
                        FormulaDefinition.forPreset(preset, OrbitTrap.NONE).parameters();
                yield InterleavedOrbitKernel.julia(
                        lanes, parameters.get("cReal"), parameters.get("cImaginary"));
            }
            default -> throw new IllegalArgumentException("Unsupported preset: " + preset);
        };
    }

    private static Variant interleaved(
            InterleavedOrbitKernel kernel,
            boolean tiled,
            int width,
            int height,
            RenderGrid grid,
            int maxIterations,
            AtomicBoolean cancelled
    ) {
        if (!tiled) {
            return () -> kernel.calculate(width, height, grid, maxIterations);
        }
        return () -> tiledFrame(width, height, maxIterations, (data, tile) ->
                kernel.calculateTile(data, grid, tile[0], tile[1], tile[2], tile[3],
                        maxIterations, cancelled::get, (x, y) -> false, ignored -> {}));
    }

    /** Calculates the frame in production-sized tiles, one after another. */
    private static FractalData tiledFrame(
            int width, int height, int maxIterations, TileCalculation calculation
    ) {
        FractalData data = new FractalData(width, height, maxIterations);
        int[] tile = new int[4];
        for (int y = 0; y < height; y += TILE_SIZE) {
            for (int x = 0; x < width; x += TILE_SIZE) {
                tile[0] = x;
                tile[1] = Math.min(x + TILE_SIZE, width);
                tile[2] = y;
                tile[3] = Math.min(y + TILE_SIZE, height);
                if (!calculation.calculate(data, tile)) {
                    throw new IllegalStateException("Benchmark tile was cancelled");
                }
            }
        }
        return data;
    }

    static void requireIdentical(FractalData expected, FractalData actual, String label) {
        for (int index = 0; index < expected.size(); index++) {
            if (expected.iterations(index) != actual.iterations(index)
                    || expected.escaped(index) != actual.escaped(index)
                    || Double.doubleToRawLongBits(expected.smoothIterations(index))
                    != Double.doubleToRawLongBits(actual.smoothIterations(index))
                    || Double.doubleToRawLongBits(expected.orbitTrapDistance(index))
                    != Double.doubleToRawLongBits(actual.orbitTrapDistance(index))) {
                throw new IllegalStateException(String.format(
                        "%s differs from scalar at pixel (%d, %d): iterations %d/%d, "
                                + "escaped %b/%b, smooth %s/%s",
                        label, index % expected.width(), index / expected.width(),
                        expected.iterations(index), actual.iterations(index),
                        expected.escaped(index), actual.escaped(index),
                        expected.smoothIterations(index), actual.smoothIterations(index)));
            }
        }
    }

    private static double[] speedups(long[] baseline, long[] candidate) {
        double[] speedups = new double[baseline.length];
        for (int round = 0; round < baseline.length; round++) {
            speedups[round] = baseline[round] / (double) candidate[round];
        }
        return speedups;
    }

    private static double median(long[] values) {
        return median(Arrays.stream(values).asDoubleStream().toArray());
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];
    }

    private static double min(double[] values) {
        return Arrays.stream(values).min().orElseThrow();
    }

    private static double max(double[] values) {
        return Arrays.stream(values).max().orElseThrow();
    }

    private static double meanIterations(FractalData data) {
        long sum = 0L;
        for (int index = 0; index < data.size(); index++) {
            sum += data.iterations(index);
        }
        return sum / (double) data.size();
    }

    private static double interiorPercent(FractalData data) {
        int interior = 0;
        for (int index = 0; index < data.size(); index++) {
            if (!data.escaped(index)) {
                interior++;
            }
        }
        return interior * 100.0 / data.size();
    }

    private static double toMs(double nanos) {
        return nanos / 1_000_000.0;
    }

    private static int integerProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    @FunctionalInterface
    private interface Variant {
        FractalData run();
    }

    @FunctionalInterface
    private interface TileCalculation {
        boolean calculate(FractalData data, int[] tile);
    }
}
