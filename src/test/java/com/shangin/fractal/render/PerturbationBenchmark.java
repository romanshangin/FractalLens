package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Compares direct Mandelbrot iteration with a shared reference-orbit recurrence. */
public final class PerturbationBenchmark {

    private static final int WIDTH = Integer.getInteger("perturbation.width", 480);
    private static final int HEIGHT = Integer.getInteger("perturbation.height", 270);
    private static final int MAX_ITERATIONS = Integer.getInteger("perturbation.iterations", 964);
    private static final int WARMUPS = Integer.getInteger("perturbation.warmups", 1);
    private static final int RUNS = Integer.getInteger("perturbation.runs", 3);
    private static final double CENTER_REAL = -0.743643887037151;
    private static final double CENTER_IMAGINARY = 0.13182590420533;

    private PerturbationBenchmark() {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.printf(
                "Perturbation benchmark: %dx%d, maxIterations=%d, warmups=%d, runs=%d%n",
                WIDTH, HEIGHT, MAX_ITERATIONS, WARMUPS, RUNS
        );
        System.out.println("zoom direct-p50 perturbation-p50 direct/perturb mismatches");

        for (double zoom : new double[]{10_000.0, 1_000_000.0, 100_000_000.0}) {
            benchmark(zoom);
        }

        benchmarkProductionDeepStartup();
    }

    private static void benchmark(double zoom) {
        double scale = 2.4 / zoom;
        ReferenceOrbit reference = ReferenceOrbit.calculate(MAX_ITERATIONS);

        for (int i = 0; i < WARMUPS; i++) {
            calculateDirect(scale);
            calculatePerturbation(scale, reference);
        }

        long[] directTimes = new long[RUNS];
        long[] perturbationTimes = new long[RUNS];
        int[] direct = null;
        int[] perturbation = null;

        for (int i = 0; i < RUNS; i++) {
            long started = System.nanoTime();
            direct = calculateDirect(scale);
            directTimes[i] = System.nanoTime() - started;

            started = System.nanoTime();
            perturbation = calculatePerturbation(scale, reference);
            perturbationTimes[i] = System.nanoTime() - started;
        }

        int mismatches = mismatchCount(direct, perturbation);
        Arrays.sort(directTimes);
        Arrays.sort(perturbationTimes);
        double directMs = toMs(directTimes[RUNS / 2]);
        double perturbationMs = toMs(perturbationTimes[RUNS / 2]);
        System.out.printf(
                "%12.0fx %10.2f %16.2f %14.2fx %,10d%n",
                zoom, directMs, perturbationMs, directMs / perturbationMs, mismatches
        );
    }

    private static int[] calculateDirect(double scale) {
        int[] iterations = new int[WIDTH * HEIGHT];
        double width = scale * WIDTH / HEIGHT;

        for (int y = 0; y < HEIGHT; y++) {
            double ci = CENTER_IMAGINARY + scale / 2.0 - scale * y / (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                double cr = CENTER_REAL - width / 2.0 + width * x / (WIDTH - 1);
                double zr = 0.0;
                double zi = 0.0;
                int iteration = 0;
                while (zr * zr + zi * zi <= 4.0 && iteration < MAX_ITERATIONS) {
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

    private static int[] calculatePerturbation(double scale, ReferenceOrbit reference) {
        int[] iterations = new int[WIDTH * HEIGHT];
        double width = scale * WIDTH / HEIGHT;

        for (int y = 0; y < HEIGHT; y++) {
            double deltaCi = scale / 2.0 - scale * y / (HEIGHT - 1);
            for (int x = 0; x < WIDTH; x++) {
                double deltaCr = -width / 2.0 + width * x / (WIDTH - 1);
                double dr = 0.0;
                double di = 0.0;
                int iteration = 0;
                while (iteration < MAX_ITERATIONS) {
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
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-0.8317528516858322713653476366999",
                        "0.207813754242134522471317257011028", "1.6e-13"),
                WIDTH, HEIGHT, MAX_ITERATIONS);
        RenderFrame frame = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        AtomicReference<TileTimingStats> tileTimings = new AtomicReference<>();
        long started = System.nanoTime();
        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(
                             Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                             diagnostics::set)) {
            backend.render(frame, () -> false, ignored -> {}, tileTimings::set);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Production deep benchmark interrupted", exception);
        }
        double totalMs = toMs(System.nanoTime() - started);
        DeepZoomTimingStats stats = diagnostics.get();
        TileTimingStats tiles = tileTimings.get();
        System.out.printf("%nProduction deep startup (%dx%d, %,d iterations)%n",
                WIDTH, HEIGHT, MAX_ITERATIONS);
        System.out.printf(
                "total=%.2fms reference=%.2fms coordinates=%.2fms first-region=%.2fms%n",
                totalMs, stats.referenceOrbitMs(), stats.coordinatePreparationMs(),
                stats.timeToFirstRegionMs());
        System.out.printf(
                "references=%d rebasing-cpu=%.2fms fallbacks=%d avg-iterations=%.1f%n",
                stats.additionalReferenceOrbitCount(), stats.rebasingOrbitMs(),
                stats.highPrecisionFallbackPixelCount(), stats.averageIterationsPerPixel());
        System.out.printf(
                "tiles=%d tile-min/median/max=%.2f/%.2f/%.2fms%n",
                tiles.tileCount(), tiles.minMs(), tiles.medianMs(), tiles.maxMs());
    }

    private static double toMs(long nanos) {
        return nanos / 1_000_000.0;
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
