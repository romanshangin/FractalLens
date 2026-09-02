package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.SamplingPattern;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Compares direct Mandelbrot iteration with a shared reference-orbit recurrence. */
public final class PerturbationBenchmark {

    private static final int WIDTH = Integer.getInteger("perturbation.width", 480);
    private static final int HEIGHT = Integer.getInteger("perturbation.height", 270);
    private static final Integer ITERATION_OVERRIDE = Integer.getInteger("perturbation.iterations");
    private static final IterationSettings ITERATION_SETTINGS = new IterationSettings();
    private static final BigDecimal DEFAULT_SCALE =
            FractalPreset.MANDELBROT.defaultViewport().scaleExact();
    private static final int WARMUPS = Integer.getInteger("perturbation.warmups", 1);
    private static final int RUNS = Integer.getInteger("perturbation.runs", 3);
    private static final double CENTER_REAL = -0.743643887037151;
    private static final double CENTER_IMAGINARY = 0.13182590420533;
    private static final String PRODUCTION_DEEP_SCALE =
            System.getProperty("perturbation.deepScale", "1.6e-13");
    private static final boolean BLA_ENABLED =
            Boolean.parseBoolean(System.getProperty("perturbation.bla", "true"));

    private PerturbationBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.printf(
                "Perturbation benchmark: %dx%d, iterations=%s, warmups=%d, runs=%d%n",
                WIDTH, HEIGHT, iterationMode(), WARMUPS, RUNS
        );
        System.out.println(
                "zoom iterations direct-p50 perturbation-p50 direct/perturb mismatches");

        for (double zoom : new double[]{10_000.0, 1_000_000.0, 100_000_000.0}) {
            benchmark(zoom);
        }

        benchmarkProductionDeepStartup();
        benchmarkBackendSelectionThreshold();
    }

    private static void benchmark(double zoom) {
        double scale = DEFAULT_SCALE.doubleValue() / zoom;
        int maxIterations = maxIterations(BigDecimal.valueOf(scale));
        ReferenceOrbit reference = ReferenceOrbit.calculate(maxIterations);

        for (int i = 0; i < WARMUPS; i++) {
            calculateDirect(scale, maxIterations);
            calculatePerturbation(scale, maxIterations, reference);
        }

        long[] directTimes = new long[RUNS];
        long[] perturbationTimes = new long[RUNS];
        int[] direct = null;
        int[] perturbation = null;

        for (int i = 0; i < RUNS; i++) {
            long started = System.nanoTime();
            direct = calculateDirect(scale, maxIterations);
            directTimes[i] = System.nanoTime() - started;

            started = System.nanoTime();
            perturbation = calculatePerturbation(scale, maxIterations, reference);
            perturbationTimes[i] = System.nanoTime() - started;
        }

        int mismatches = mismatchCount(direct, perturbation);
        Arrays.sort(directTimes);
        Arrays.sort(perturbationTimes);
        double directMs = toMs(directTimes[RUNS / 2]);
        double perturbationMs = toMs(perturbationTimes[RUNS / 2]);
        System.out.printf(
                "%12.0fx %,10d %10.2f %16.2f %14.2fx %,10d%n",
                zoom, maxIterations, directMs, perturbationMs,
                directMs / perturbationMs, mismatches
        );
    }

    private static int[] calculateDirect(double scale, int maxIterations) {
        int[] iterations = new int[WIDTH * HEIGHT];
        double width = scale * WIDTH / HEIGHT;

        for (int y = 0; y < HEIGHT; y++) {
            double ci = CENTER_IMAGINARY + scale / 2.0 - scale * y / (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                double cr = CENTER_REAL - width / 2.0 + width * x / (WIDTH - 1);
                double zr = 0.0;
                double zi = 0.0;
                int iteration = 0;
                while (zr * zr + zi * zi <= 4.0 && iteration < maxIterations) {
                    double nextReal = zr * zr - zi * zi + cr;
                    zi = 2.0 * zr * zi + ci;
                    zr = nextReal;
                    iteration++;
                }
                iterations[y * WIDTH + x] = iteration;
            }
        }
        return iterations;
    }

    private static int[] calculatePerturbation(
            double scale,
            int maxIterations,
            ReferenceOrbit reference
    ) {
        int[] iterations = new int[WIDTH * HEIGHT];
        double width = scale * WIDTH / HEIGHT;

        for (int y = 0; y < HEIGHT; y++) {
            double deltaCi = scale / 2.0 - scale * y / (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                double deltaCr = -width / 2.0 + width * x / (WIDTH - 1);
                double dr = 0.0;
                double di = 0.0;
                int iteration = 0;
                while (iteration < maxIterations) {
                    double zr = reference.real[iteration] + dr;
                    double zi = reference.imaginary[iteration] + di;
                    if (zr * zr + zi * zi > 4.0) {
                        break;
                    }
                    double nextDr = 2.0 * (reference.real[iteration] * dr
                            - reference.imaginary[iteration] * di)
                            + dr * dr - di * di + deltaCr;
                    di = 2.0 * (reference.real[iteration] * di
                            + reference.imaginary[iteration] * dr + dr * di)
                            + deltaCi;
                    dr = nextDr;
                    iteration++;
                }
                iterations[y * WIDTH + x] = iteration;
            }
        }
        return iterations;
    }

    private static int mismatchCount(int[] expected, int[] actual) {
        int mismatches = 0;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                mismatches++;
            }
        }
        return mismatches;
    }

    private static void benchmarkProductionDeepStartup() {
        BigDecimal scale = new BigDecimal(PRODUCTION_DEEP_SCALE);
        int maxIterations = maxIterations(scale);
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-0.8317528516858322713653476366999",
                        "0.207813754242134522471317257011028", PRODUCTION_DEEP_SCALE),
                WIDTH, HEIGHT, maxIterations);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        AtomicReference<TileTimingStats> tileTimings = new AtomicReference<>();
        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(
                             Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                             diagnostics::set,
                             MandelbrotPerturbationRenderBackend.DEFAULT_REFERENCE_CACHE_BYTES,
                             BLA_ENABLED)) {
            System.out.printf(
                    "%nProduction deep glitch workload (%dx%d, scale=%s, %,d iterations, BLA=%s)%n",
                    WIDTH, HEIGHT, PRODUCTION_DEEP_SCALE, maxIterations, BLA_ENABLED);
            renderAndReport("cold", backend, job, diagnostics, tileTimings);
            RenderFrame cached = renderAndReport("cached", backend, job, diagnostics, tileTimings);
            benchmarkDeepAntialias(cached);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Production deep benchmark interrupted", exception);
        }
    }

    private static RenderFrame renderAndReport(
            String label,
            MandelbrotPerturbationRenderBackend backend,
            RenderJob job,
            AtomicReference<DeepZoomTimingStats> diagnostics,
            AtomicReference<TileTimingStats> tileTimings
    ) throws InterruptedException {
        RenderFrame frame = RenderFrame.create(job);
        diagnostics.set(null);
        tileTimings.set(null);
        AtomicBoolean cancelled = new AtomicBoolean();
        long started = System.nanoTime();
        backend.render(frame, cancelled::get, ignored -> {}, tileTimings::set);
        double totalMs = toMs(System.nanoTime() - started);
        DeepZoomTimingStats stats = diagnostics.get();
        TileTimingStats tiles = tileTimings.get();
        System.out.printf(
                "%s total=%.2fms reference=%.2fms coordinates=%.2fms BLA=%.2fms first-region=%.2fms%n",
                label, totalMs, stats.referenceOrbitMs(), stats.coordinatePreparationMs(),
                stats.blaPreparationMs(), stats.timeToFirstRegionMs());
        System.out.printf(
                "%s bla-steps=%d bla-skipped-iterations=%d scalar-iterations/pixel=%.1f%n",
                label, stats.blaStepCount(), stats.blaSkippedIterationCount(),
                stats.averageIterationsPerPixel()
                        - (double) stats.blaSkippedIterationCount() / stats.calculatedPixelCount());
        System.out.printf(
                "%s references=%d modified-rebases=%d reference-build-cpu=%.2fms fallbacks=%d "
                        + "avg-iterations=%.1f (%.1f%% of max) throughput=%.2f Miter/s "
                        + "cache=%d/%dB%n",
                label,
                stats.additionalReferenceOrbitCount(), stats.modifiedRebaseCount(),
                stats.additionalReferenceOrbitMs(),
                stats.highPrecisionFallbackPixelCount(), stats.averageIterationsPerPixel(),
                100.0 * stats.averageIterationsPerPixel() / job.maxIterations(),
                iterationThroughput(stats, totalMs),
                backend.cachedReferenceCount(), backend.cachedReferenceBytes());
        System.out.printf(
                "%s tiles=%d tile-min/median/max=%.2f/%.2f/%.2fms%n",
                label,
                tiles.tileCount(), tiles.minMs(), tiles.medianMs(), tiles.maxMs());
        return frame;
    }

    private static void benchmarkBackendSelectionThreshold() {
        int normalIterations = maxIterations(DEFAULT_SCALE);
        BigDecimal deepScale = new BigDecimal("1e-80");
        int deepIterations = maxIterations(deepScale);
        RenderJob normal = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport(-0.75, 0.0, 2.4), WIDTH, HEIGHT, normalIterations);
        RenderJob deep = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-0.7436438870371510000000000000000000001",
                        "0.1318259042053300000000000000000000002", "1e-80"),
                WIDTH, HEIGHT, deepIterations);
        DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
        MandelbrotPerturbationRenderBackend perturbation =
                new MandelbrotPerturbationRenderBackend(1);
        try (PrecisionSelectingRenderBackend selector =
                     new PrecisionSelectingRenderBackend(direct, perturbation)) {
            System.out.printf(
                    "%nbackend-selection normal=%s (%,d iterations) deep=%s (%,d iterations)%n",
                    selector.select(normal).getClass().getSimpleName(), normalIterations,
                    selector.select(deep).getClass().getSimpleName(), deepIterations);
        }
    }

    private static void benchmarkDeepAntialias(RenderFrame frame) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger publishedTiles = new AtomicInteger();
        long started = System.nanoTime();
        try (InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            service.refineDeep(
                    frame,
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    SamplingPattern.REGULAR,
                    RefinedPixelSnapshot.empty(frame.job().width(), frame.job().height()),
                    Runnable::run,
                    (region, colors) -> publishedTiles.incrementAndGet(),
                    completed::countDown,
                    exception -> {
                        error.set(exception);
                        completed.countDown();
                    });
            if (!completed.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Deep AA benchmark timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Deep AA benchmark interrupted", exception);
        }
        if (error.get() != null) {
            throw new IllegalStateException("Deep AA benchmark failed", error.get());
        }
        System.out.printf("deep-aa=%.2fms published-tiles=%d%n",
                toMs(System.nanoTime() - started), publishedTiles.get());
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double iterationThroughput(DeepZoomTimingStats stats, double totalMs) {
        double executedIterations = stats.calculatedPixelCount()
                * stats.averageIterationsPerPixel();
        return executedIterations / (totalMs * 1_000.0);
    }

    private static String iterationMode() {
        return ITERATION_OVERRIDE == null
                ? "adaptive (base=%d, perZoomLevel=%d)".formatted(
                        ITERATION_SETTINGS.baseIterations(),
                        ITERATION_SETTINGS.iterationsPerZoomLevel())
                : "fixed %,d".formatted(validatedIterationOverride());
    }

    private static int maxIterations(BigDecimal scale) {
        return ITERATION_OVERRIDE == null
                ? ITERATION_SETTINGS.maxIterations(DEFAULT_SCALE, scale)
                : validatedIterationOverride();
    }

    private static int validatedIterationOverride() {
        if (ITERATION_OVERRIDE == null || ITERATION_OVERRIDE <= 0) {
            throw new IllegalArgumentException(
                    "perturbation.iterations must be a positive integer");
        }
        return ITERATION_OVERRIDE;
    }

    private record ReferenceOrbit(double[] real, double[] imaginary) {
        private static ReferenceOrbit calculate(int maxIterations) {
            double[] real = new double[maxIterations + 1];
            double[] imaginary = new double[maxIterations + 1];
            for (int i = 0; i < maxIterations; i++) {
                real[i + 1] = real[i] * real[i] - imaginary[i] * imaginary[i] + CENTER_REAL;
                imaginary[i + 1] = 2.0 * real[i] * imaginary[i] + CENTER_IMAGINARY;
            }
            return new ReferenceOrbit(real, imaginary);
        }
    }
}
