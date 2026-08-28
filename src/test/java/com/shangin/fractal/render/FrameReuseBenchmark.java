package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone benchmark comparing a fresh pan render with a render that reuses
 * a completed source frame. It intentionally lives outside the unit-test naming
 * convention and runs only through the Maven benchmark profile.
 */
public final class FrameReuseBenchmark {

    private static final int DEFAULT_ITERATIONS = 300;
    private static final int DEFAULT_WARMUPS = 1;
    private static final int DEFAULT_RUNS = 2;
    private static final int[] DEFAULT_TILE_SIZES = {16, 32, 64};

    private FrameReuseBenchmark() {}

    public static void main(String[] args) throws InterruptedException {
        Locale.setDefault(Locale.ROOT);

        int iterations = integerProperty("benchmark.iterations", DEFAULT_ITERATIONS);
        int warmups = integerProperty("benchmark.warmups", DEFAULT_WARMUPS);
        int runs = integerProperty("benchmark.runs", DEFAULT_RUNS);
        int workers = integerProperty(
                "benchmark.workers",
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
        );
        int[] tileSizes = tileSizesProperty();

        List<Resolution> resolutions = List.of(
                new Resolution("1080p", 1920, 1080),
                new Resolution("HiDPI-1280x720@2x", 2560, 1440)
        );

        System.out.printf(
                "Frame reuse benchmark: iterations=%d, workers=%d, warmups=%d, runs=%d%n",
                iterations,
                workers,
                warmups,
                runs
        );

        for (Resolution resolution : resolutions) {
            for (int tileSize : tileSizes) {
                benchmarkConfiguration(
                        resolution,
                        iterations,
                        workers,
                        tileSize,
                        warmups,
                        runs
                );
            }
        }
    }

    private static void benchmarkConfiguration(
            Resolution resolution,
            int iterations,
            int workers,
            int tileSize,
            int warmups,
            int runs
    ) throws InterruptedException {
        FractalPreset preset = FractalPreset.MANDELBROT;
        FractalCalculator calculator = new FractalCalculator(preset.createFormula());
        Viewport sourceViewport = preset.defaultViewport();
        RenderRequest sourceRequest = new RenderRequest(
                calculator,
                sourceViewport,
                resolution.width(),
                resolution.height(),
                iterations
        );

        try (ParallelFractalCalculator parallel =
                     new ParallelFractalCalculator(workers, tileSize)) {

            RenderFrame sourceFrame = RenderFrame.create(sourceRequest);
            parallel.calculate(sourceFrame, () -> false, ignored -> {});

            List<PanScenario> scenarios = List.of(
                    new PanScenario("short", Math.max(1, resolution.width() / 20), 0),
                    new PanScenario("medium", resolution.width() / 4, 0),
                    new PanScenario("near-full", resolution.width() * 3 / 4, 0)
            );

            System.out.printf(
                    "%n[%s, tile=%d, %,dx%,d]%n",
                    resolution.name(),
                    tileSize,
                    resolution.width(),
                    resolution.height()
            );
            System.out.println(
                    "scenario   reuse    mode   first-p50 first-p95 total-p50 total-p95 "
                            + "calc-p50 color-p50 tiles-p50 speedup"
            );

            for (PanScenario scenario : scenarios) {
                Viewport targetViewport = sourceViewport.shiftedByPixels(
                        scenario.dx(),
                        scenario.dy(),
                        resolution.width(),
                        resolution.height()
                );
                RenderRequest targetRequest = new RenderRequest(
                        calculator,
                        targetViewport,
                        resolution.width(),
                        resolution.height(),
                        iterations
                );

                for (int i = 0; i < warmups; i++) {
                    runFresh(parallel, targetRequest);
                    runReused(parallel, sourceFrame, targetRequest);
                }

                List<Measurement> fresh = new ArrayList<>();
                List<Measurement> reused = new ArrayList<>();

                for (int i = 0; i < runs; i++) {
                    if ((i & 1) == 0) {
                        fresh.add(runFresh(parallel, targetRequest));
                        reused.add(runReused(parallel, sourceFrame, targetRequest));
                    } else {
                        reused.add(runReused(parallel, sourceFrame, targetRequest));
                        fresh.add(runFresh(parallel, targetRequest));
                    }
                }

                Summary freshSummary = Summary.from(fresh);
                Summary reuseSummary = Summary.from(reused);
                double speedup = freshSummary.totalP50Ms() / reuseSummary.totalP50Ms();

                printSummary(scenario.name(), freshSummary, 1.0);
                printSummary(scenario.name(), reuseSummary, speedup);
            }
        }
    }

    private static Measurement runFresh(
            ParallelFractalCalculator parallel,
            RenderRequest request
    ) throws InterruptedException {
        RenderFrame frame = RenderFrame.create(request);
        return runCalculation(parallel, frame, 0L, 0);
    }

    private static Measurement runReused(
            ParallelFractalCalculator parallel,
            RenderFrame sourceFrame,
            RenderRequest request
    ) throws InterruptedException {
        long started = System.nanoTime();
        FrameReuseResult reuse = new FrameReusePlanner().plan(sourceFrame, request);
        long planNanos = System.nanoTime() - started;

        return runCalculation(
                parallel,
                reuse.frame(),
                planNanos,
                reuse.reusedPixels()
        );
    }

    private static Measurement runCalculation(
            ParallelFractalCalculator parallel,
            RenderFrame frame,
            long planNanos,
            int reusedPixels
    ) throws InterruptedException {
        ConcurrentLinkedQueue<RenderRegion> completedRegions =
                new ConcurrentLinkedQueue<>();
        AtomicLong firstRegionNanos = new AtomicLong();
        AtomicReference<TileTimingStats> timingStats = new AtomicReference<>();
        IntBuffer pixels = IntBuffer.allocate(frame.fractalData().size());

        long calculationStart = System.nanoTime();
        parallel.calculate(
                frame,
                () -> false,
                region -> {
                    firstRegionNanos.compareAndSet(0L, System.nanoTime());
                    completedRegions.add(region);
                },
                timingStats::set
        );
        long calculationNanos = System.nanoTime() - calculationStart;

        ColoringStrategy coloring = new SmoothPaletteColoring(
                PalettePreset.ICE.palette()
        );
        FractalColorizer colorizer = new FractalColorizer();
        long colorStart = System.nanoTime();

        for (RenderRegion region : completedRegions) {
            colorizer.colorRegion(
                    frame.fractalData(),
                    pixels,
                    coloring,
                    region
            );
        }

        long colorNanos = System.nanoTime() - colorStart;
        long firstNanos = reusedPixels > 0
                ? planNanos
                : firstRegionNanos.get() - calculationStart;
        TileTimingStats tiles = timingStats.get();
        int totalPixels = frame.fractalData().size();

        return new Measurement(
                reusedPixels * 100.0 / totalPixels,
                toMs(firstNanos),
                toMs(planNanos + calculationNanos + colorNanos),
                toMs(calculationNanos),
                toMs(colorNanos),
                tiles.tileCount()
        );
    }

    private static void printSummary(
            String scenario,
            Summary summary,
            double speedup
    ) {
        System.out.printf(
                "%-10s %6.1f%% %-6s %9.2f %9.2f %9.2f %9.2f %8.2f %9.2f %9.0f %7.2fx%n",
                scenario,
                summary.reusePercent(),
                summary.reusePercent() == 0.0 ? "fresh" : "reuse",
                summary.firstP50Ms(),
                summary.firstP95Ms(),
                summary.totalP50Ms(),
                summary.totalP95Ms(),
                summary.calculationP50Ms(),
                summary.colorP50Ms(),
                summary.tileCountP50(),
                speedup
        );
    }

    private static int integerProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    private static int[] tileSizesProperty() {
        String value = System.getProperty("benchmark.tileSizes");

        if (value == null || value.isBlank()) {
            return DEFAULT_TILE_SIZES;
        }

        return Arrays.stream(value.split(","))
                .map(String::trim)
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static double percentile(double[] source, double percentile) {
        double[] values = source.clone();
        Arrays.sort(values);
        int index = (int) Math.ceil(percentile * values.length) - 1;
        return values[Math.clamp(index, 0, values.length - 1)];
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record Resolution(String name, int width, int height) {}

    private record PanScenario(String name, int dx, int dy) {}

    private record Measurement(
            double reusePercent,
            double firstMs,
            double totalMs,
            double calculationMs,
            double colorMs,
            int tileCount
    ) {}

    private record Summary(
            double reusePercent,
            double firstP50Ms,
            double firstP95Ms,
            double totalP50Ms,
            double totalP95Ms,
            double calculationP50Ms,
            double colorP50Ms,
            double tileCountP50
    ) {
        static Summary from(List<Measurement> measurements) {
            return new Summary(
                    measurements.getFirst().reusePercent(),
                    percentile(values(measurements, Measurement::firstMs), 0.50),
                    percentile(values(measurements, Measurement::firstMs), 0.95),
                    percentile(values(measurements, Measurement::totalMs), 0.50),
                    percentile(values(measurements, Measurement::totalMs), 0.95),
                    percentile(values(measurements, Measurement::calculationMs), 0.50),
                    percentile(values(measurements, Measurement::colorMs), 0.50),
                    percentile(values(measurements, value -> value.tileCount()), 0.50)
            );
        }

        private static double[] values(
                List<Measurement> measurements,
                java.util.function.ToDoubleFunction<Measurement> extractor
        ) {
            return measurements.stream().mapToDouble(extractor).toArray();
        }
    }
}
