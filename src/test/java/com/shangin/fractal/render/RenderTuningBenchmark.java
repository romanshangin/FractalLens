package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Compares tile sizes and worker counts after calculation-kernel tuning. */
public final class RenderTuningBenchmark {

    private static final int WIDTH = Integer.getInteger("renderTuning.width", 1920);
    private static final int HEIGHT = Integer.getInteger("renderTuning.height", 1080);
    private static final int WARMUPS = Integer.getInteger("renderTuning.warmups", 1);
    private static final int RUNS = Integer.getInteger("renderTuning.runs", 3);
    private static final int[] TILE_SIZES = {16, 32, 64};

    private RenderTuningBenchmark() {}

    public static void main(String[] args) throws InterruptedException {
        Locale.setDefault(Locale.ROOT);
        int processors = Runtime.getRuntime().availableProcessors();
        int[] workerCounts = workerCounts(processors);

        System.out.printf(
                "Render tuning benchmark: %dx%d, processors=%d, warmups=%d, runs=%d%n",
                WIDTH,
                HEIGHT,
                processors,
                WARMUPS,
                RUNS
        );
        System.out.println("scenario               tile workers first-p50 total-p50");

        for (Scenario scenario : scenarios()) {
            for (int tileSize : TILE_SIZES) {
                for (int workers : workerCounts) {
                    benchmark(scenario, tileSize, workers);
                }
            }
        }
    }

    private static void benchmark(
            Scenario scenario,
            int tileSize,
            int workers
    ) throws InterruptedException {
        try (ParallelFractalCalculator calculator =
                     new ParallelFractalCalculator(workers, tileSize)) {
            for (int i = 0; i < WARMUPS; i++) {
                render(calculator, scenario);
            }

            long[] firstTimes = new long[RUNS];
            long[] totalTimes = new long[RUNS];

            for (int i = 0; i < RUNS; i++) {
                Result result = render(calculator, scenario);
                firstTimes[i] = result.firstNanos();
                totalTimes[i] = result.totalNanos();
            }

            Arrays.sort(firstTimes);
            Arrays.sort(totalTimes);

            System.out.printf(
                    "%-22s %4d %7d %9.2f %9.2f%n",
                    scenario.name(),
                    tileSize,
                    workers,
                    toMs(firstTimes[RUNS / 2]),
                    toMs(totalTimes[RUNS / 2])
            );
        }
    }

    private static Result render(
            ParallelFractalCalculator calculator,
            Scenario scenario
    ) throws InterruptedException {
        RenderFrame frame = RenderFrame.create(scenario.request());
        AtomicLong firstCompleted = new AtomicLong();
        long started = System.nanoTime();

        calculator.calculate(
                frame,
                () -> false,
                ignored -> firstCompleted.compareAndSet(0L, System.nanoTime())
        );

        long finished = System.nanoTime();

        if (!frame.isComplete() || firstCompleted.get() == 0L) {
            throw new IllegalStateException("Benchmark render did not complete");
        }

        return new Result(
                firstCompleted.get() - started,
                finished - started
        );
    }

    private static List<Scenario> scenarios() {
        return List.of(
                scenario(
                        "Mandelbrot overview",
                        FractalPreset.MANDELBROT,
                        new Viewport(-0.75, 0.0, 2.4),
                        300
                ),
                scenario(
                        "Mandelbrot 10000x",
                        FractalPreset.MANDELBROT,
                        new Viewport(
                                -0.743643887037151,
                                0.13182590420533,
                                2.4 / 10_000.0
                        ),
                        964
                ),
                scenario(
                        "Julia 10000x",
                        FractalPreset.JULIA,
                        new Viewport(0.0, 0.0, 2.4 / 10_000.0),
                        964
                )
        );
    }

    private static Scenario scenario(
            String name,
            FractalPreset preset,
            Viewport viewport,
            int maxIterations
    ) {
        return new Scenario(
                name,
                new RenderRequest(
                        new FractalCalculator(preset.createFormula()),
                        viewport,
                        WIDTH,
                        HEIGHT,
                        maxIterations
                )
        );
    }

    private static int[] workerCounts(int processors) {
        return Arrays.stream(new int[] {
                        Math.max(1, processors / 2),
                        Math.max(1, processors - 1),
                        processors
                })
                .distinct()
                .toArray();
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record Scenario(String name, RenderRequest request) {}

    private record Result(long firstNanos, long totalNanos) {}
}
