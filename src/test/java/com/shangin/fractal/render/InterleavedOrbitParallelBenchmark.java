package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Paired multi-threaded comparison of the scalar and interleaved-orbit tile
 * kernels through {@link ParallelFractalCalculator} with the production tile
 * size and default worker count. Measures total frame time, time to the first
 * completed region and cancellation latency on the formula benchmark scenes.
 * Every completed frame, and every ready pixel of a cancelled frame, is
 * checked against the scalar frame; any mismatch aborts the benchmark.
 */
final class InterleavedOrbitParallelBenchmark {

    private static final int TILE_SIZE = 32;

    private InterleavedOrbitParallelBenchmark() {}

    static void run(int width, int height, int baseIterations, int warmups, int rounds)
            throws InterruptedException {
        Locale.setDefault(Locale.ROOT);
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        System.out.printf(
                "Interleaved-orbit parallel benchmark: %,dx%,d, baseIterations=%d, warmups=%d, "
                        + "rounds=%d, workers=%d, tile=%d, java=%s, arch=%s, processors=%d%n%n",
                width, height, baseIterations, warmups, rounds, workers, TILE_SIZE,
                System.getProperty("java.vm.version"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors());
        System.out.println(
                "formula      zoom maxIter scalar-total-p50 inter-total-p50 total-speedup(med/min/max) "
                        + "scalar-first-p50 inter-first-p50 first-ratio(med) "
                        + "scalar-cancel(p50/max) inter-cancel(p50/max)");

        try (ParallelFractalCalculator scalar =
                     new ParallelFractalCalculator(workers, TILE_SIZE, false);
             ParallelFractalCalculator interleaved =
                     new ParallelFractalCalculator(workers, TILE_SIZE, true)) {
            for (FormulaCalculationBenchmark.Scenario scenario : FormulaCalculationBenchmark.scenarios()) {
                runScenario(scenario, scalar, interleaved, width, height,
                        baseIterations, warmups, rounds);
            }
        }
    }

    private static void runScenario(
            FormulaCalculationBenchmark.Scenario scenario,
            ParallelFractalCalculator scalar,
            ParallelFractalCalculator interleaved,
            int width,
            int height,
            int baseIterations,
            int warmups,
            int rounds
    ) throws InterruptedException {
        int maxIterations = FormulaCalculationBenchmark.maxIterations(scenario, baseIterations);
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(scenario.preset(), OrbitTrap.NONE),
                scenario.viewport(), width, height, maxIterations);
        String label = scenario.name() + " " + scenario.zoom() + "x";

        FractalData reference = (FractalData) render(scalar, job).frame().samplePlane();
        InterleavedOrbitBenchmark.requireIdentical(reference,
                (FractalData) render(interleaved, job).frame().samplePlane(), label + " parallel");

        for (int warmup = 0; warmup < warmups; warmup++) {
            render(scalar, job);
            render(interleaved, job);
        }

        long[][] total = new long[2][rounds];
        long[][] first = new long[2][rounds];
        long[][] cancel = new long[2][rounds];
        ParallelFractalCalculator[] calculators = {scalar, interleaved};
        for (int round = 0; round < rounds; round++) {
            for (int offset = 0; offset < 2; offset++) {
                int variant = (round + offset) % 2;
                Result result = render(calculators[variant], job);
                total[variant][round] = result.totalNanos();
                first[variant][round] = result.firstNanos();
            }
        }
        for (int round = 0; round < rounds; round++) {
            for (int offset = 0; offset < 2; offset++) {
                int variant = (round + offset) % 2;
                cancel[variant][round] = cancelAfterFirstRegion(
                        calculators[variant], job, reference, label);
            }
        }

        double[] totalSpeedups = ratios(total[0], total[1]);
        double[] firstRatios = ratios(first[0], first[1]);
        System.out.printf(
                "%-10s %6.0fx %7d %16.2f %15.2f %10.3f/%5.3f/%5.3f %16.2f %15.2f %16.3f "
                        + "%11.2f/%6.2f %12.2f/%6.2f%n",
                scenario.name(), scenario.zoom(), maxIterations,
                toMs(median(total[0])), toMs(median(total[1])),
                median(totalSpeedups), min(totalSpeedups), max(totalSpeedups),
                toMs(median(first[0])), toMs(median(first[1])), median(firstRatios),
                toMs(median(cancel[0])), toMs(max(cancel[0])),
                toMs(median(cancel[1])), toMs(max(cancel[1])));
    }

    private static Result render(ParallelFractalCalculator calculator, RenderJob job)
            throws InterruptedException {
        RenderFrame frame = RenderFrame.create(job);
        AtomicLong firstCompleted = new AtomicLong();
        long started = System.nanoTime();
        calculator.calculate(frame, () -> false,
                ignored -> firstCompleted.compareAndSet(0L, System.nanoTime()));
        long finished = System.nanoTime();
        if (!frame.isComplete() || firstCompleted.get() == 0L) {
            throw new IllegalStateException("Benchmark render did not complete");
        }
        return new Result(frame, firstCompleted.get() - started, finished - started);
    }

    /** Cancels as soon as the first region is reported; returns flag-to-return latency. */
    private static long cancelAfterFirstRegion(
            ParallelFractalCalculator calculator, RenderJob job, FractalData reference, String label
    ) throws InterruptedException {
        RenderFrame frame = RenderFrame.create(job);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong cancelledAt = new AtomicLong();
        calculator.calculate(frame, cancelled::get, ignored -> {
            if (cancelledAt.compareAndSet(0L, System.nanoTime())) {
                cancelled.set(true);
            }
        });
        long returned = System.nanoTime();
        if (cancelledAt.get() == 0L) {
            throw new IllegalStateException("No region completed before cancellation");
        }
        requireReadyPixelsMatch(frame, reference, label + " cancelled");
        return returned - cancelledAt.get();
    }

    private static void requireReadyPixelsMatch(RenderFrame frame, FractalData reference, String label) {
        SamplePlane actual = frame.samplePlane();
        ValidityMask validity = frame.validity();
        for (int y = 0; y < reference.height(); y++) {
            for (int x = 0; x < reference.width(); x++) {
                if (!validity.isReady(x, y)) {
                    continue;
                }
                int index = y * reference.width() + x;
                if (reference.iterations(index) != actual.iterations(index)
                        || reference.escaped(index) != actual.escaped(index)
                        || Double.doubleToRawLongBits(reference.smoothIterations(index))
                        != Double.doubleToRawLongBits(actual.smoothIterations(index))) {
                    throw new IllegalStateException(String.format(
                            "%s: ready pixel (%d, %d) differs from scalar", label, x, y));
                }
            }
        }
    }

    private static double[] ratios(long[] baseline, long[] candidate) {
        double[] ratios = new double[baseline.length];
        for (int round = 0; round < baseline.length; round++) {
            ratios[round] = baseline[round] / (double) candidate[round];
        }
        return ratios;
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

    private static double max(long[] values) {
        return Arrays.stream(values).max().orElseThrow();
    }

    private static double toMs(double nanos) {
        return nanos / 1_000_000.0;
    }

    private record Result(RenderFrame frame, long firstNanos, long totalNanos) {}
}
