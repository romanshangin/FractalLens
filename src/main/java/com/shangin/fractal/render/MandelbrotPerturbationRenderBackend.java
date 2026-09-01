package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Mandelbrot deep-zoom renderer using a high-precision reference orbit and
 * double-precision perturbations for the individual pixel deltas.
 *
 * <p>This is deliberately Mandelbrot-only. Julia and orbit traps have
 * different orbit semantics and stay on their established paths until they
 * have a dedicated deep-zoom design.</p>
 */
public final class MandelbrotPerturbationRenderBackend implements RenderBackend {

    private static final int TILE_SIZE = 32;
    private static final BigDecimal FOUR = BigDecimal.valueOf(4);

    private final ExecutorService workers;

    public MandelbrotPerturbationRenderBackend() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    MandelbrotPerturbationRenderBackend(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be at least 1");
        }
        AtomicInteger sequence = new AtomicInteger();
        workers = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable,
                    "mandelbrot-perturbation-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public boolean supports(RenderJob job) {
        Objects.requireNonNull(job);
        return job.formula().preset() == FractalPreset.MANDELBROT
                && job.formula().orbitTrap() == OrbitTrap.NONE;
    }

    @Override
    public RenderFrame render(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(regionCompleted);
        if (!supports(frame.job())) {
            throw new IllegalArgumentException("Perturbation rendering supports Mandelbrot without orbit traps only");
        }
        if (cancelled.getAsBoolean()) {
            return frame;
        }

        long started = System.nanoTime();
        ReferenceOrbit reference = ReferenceOrbit.create(frame.job(), cancelled);
        if (reference == null) {
            return frame;
        }

        PreciseRenderGrid grid = frame.job().preciseGrid();
        List<RenderRegion> tiles = orderedTiles(frame.job());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (RenderRegion tile : tiles) {
            List<RenderRegion> missing = frame.validity().missingRowSpans(tile);
            for (RenderRegion span : missing) {
                tasks.add(() -> {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    calculateSpan(frame, grid, reference, span, cancelled);
                    if (!cancelled.getAsBoolean()) {
                        frame.validity().markReady(span);
                        regionCompleted.accept(span);
                    }
                    return null;
                });
            }
        }
        for (Future<Void> future : workers.invokeAll(tasks)) {
            if (cancelled.getAsBoolean()) {
                return frame;
            }
            try {
                future.get();
            } catch (java.util.concurrent.ExecutionException exception) {
                throw new IllegalStateException("Perturbation tile calculation failed", exception.getCause());
            }
        }
        if (timingCompleted != null) {
            double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
            timingCompleted.accept(new TileTimingStats(tasks.size(), elapsedMs, elapsedMs, elapsedMs));
        }
        return frame;
    }

    private static void calculateSpan(
            RenderFrame frame, PreciseRenderGrid grid, ReferenceOrbit reference,
            RenderRegion span, BooleanSupplier cancelled
    ) {
        SamplePlane samples = frame.samplePlane();
        for (int y = span.y(); y < span.y() + span.height() && !cancelled.getAsBoolean(); y++) {
            BigDecimal imaginary = grid.imaginaryAt(y);
            for (int x = span.x(); x < span.x() + span.width(); x++) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                double deltaReal = grid.realAt(x).subtract(reference.cReal(), grid.mathContext()).doubleValue();
                double deltaImaginary = imaginary.subtract(reference.cImaginary(), grid.mathContext()).doubleValue();
                samples.set(x, y, perturb(reference, deltaReal, deltaImaginary,
                        frame.job().maxIterations()));
            }
        }
    }

    private static FractalSample perturb(
            ReferenceOrbit reference, double deltaCReal, double deltaCImaginary, int maxIterations
    ) {
        double deltaReal = 0.0;
        double deltaImaginary = 0.0;
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;
        while (iteration < maxIterations) {
            double referenceReal = reference.realAt(iteration);
            double referenceImaginary = reference.imaginaryAt(iteration);
            double nextDeltaReal = 2.0 * (referenceReal * deltaReal - referenceImaginary * deltaImaginary)
                    + deltaReal * deltaReal - deltaImaginary * deltaImaginary + deltaCReal;
            double nextDeltaImaginary = 2.0 * (referenceReal * deltaImaginary + referenceImaginary * deltaReal)
                    + 2.0 * deltaReal * deltaImaginary + deltaCImaginary;
            deltaReal = nextDeltaReal;
            deltaImaginary = nextDeltaImaginary;
            iteration++;
            zr = reference.realAt(iteration) + deltaReal;
            zi = reference.imaginaryAt(iteration) + deltaImaginary;
            if (zr * zr + zi * zi > 4.0) {
                return new FractalSample(iteration, true, zr, zi);
            }
        }
        return new FractalSample(iteration, false, zr, zi);
    }

    private static List<RenderRegion> orderedTiles(RenderJob job) {
        List<RenderRegion> tiles = new ArrayList<>();
        for (int y = 0; y < job.height(); y += TILE_SIZE) {
            for (int x = 0; x < job.width(); x += TILE_SIZE) {
                tiles.add(new RenderRegion(x, y, Math.min(TILE_SIZE, job.width() - x),
                        Math.min(TILE_SIZE, job.height() - y)));
            }
        }
        double priorityX = job.width() * job.priority().x();
        double priorityY = job.height() * job.priority().y();
        tiles.sort(Comparator.comparingDouble(tile -> {
            double dx = tile.x() + tile.width() / 2.0 - priorityX;
            double dy = tile.y() + tile.height() / 2.0 - priorityY;
            return dx * dx + dy * dy;
        }));
        return tiles;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    /** High-precision centre orbit retained once for all tiles in one frame. */
    private record ReferenceOrbit(BigDecimal cReal, BigDecimal cImaginary,
                                  double[] real, double[] imaginary) {
        static ReferenceOrbit create(RenderJob job, BooleanSupplier cancelled) {
            PreciseRenderGrid grid = job.preciseGrid();
            int centerX = (job.width() - 1) / 2;
            int centerY = (job.height() - 1) / 2;
            BigDecimal cReal = grid.realAt(centerX);
            BigDecimal cImaginary = grid.imaginaryAt(centerY);
            MathContext context = grid.mathContext();
            double[] real = new double[job.maxIterations() + 1];
            double[] imaginary = new double[job.maxIterations() + 1];
            BigDecimal zr = BigDecimal.ZERO;
            BigDecimal zi = BigDecimal.ZERO;
            for (int iteration = 0; iteration < job.maxIterations(); iteration++) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return null;
                }
                BigDecimal nextReal = zr.multiply(zr, context).subtract(zi.multiply(zi, context), context)
                        .add(cReal, context);
                BigDecimal nextImaginary = zr.multiply(zi, context).multiply(BigDecimal.valueOf(2), context)
                        .add(cImaginary, context);
                zr = nextReal;
                zi = nextImaginary;
                real[iteration + 1] = zr.doubleValue();
                imaginary[iteration + 1] = zi.doubleValue();
                if (zr.multiply(zr, context).add(zi.multiply(zi, context)).compareTo(FOUR) > 0) {
                    // Continue no further: every sufficiently-close delta shares this escape horizon.
                    // The remaining slots are intentionally infinite so they cannot appear bounded.
                    for (int tail = iteration + 2; tail < real.length; tail++) {
                        real[tail] = Double.POSITIVE_INFINITY;
                        imaginary[tail] = Double.POSITIVE_INFINITY;
                    }
                    break;
                }
            }
            return new ReferenceOrbit(cReal, cImaginary, real, imaginary);
        }

        double realAt(int iteration) { return real[iteration]; }
        double imaginaryAt(int iteration) { return imaginary[iteration]; }
    }
}
