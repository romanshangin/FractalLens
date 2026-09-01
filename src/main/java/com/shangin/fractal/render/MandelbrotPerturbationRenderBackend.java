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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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
    /* A wide safety margin prevents a double shortcut from classifying a boundary pixel. */
    private static final double INTERIOR_SAFETY_MARGIN = 1e-12;
    private static final double GLITCH_RELATIVE_THRESHOLD = 1e-6;

    private final ExecutorService workers;
    private final int workerCount;
    private final Consumer<DeepZoomTimingStats> diagnosticsCompleted;

    public MandelbrotPerturbationRenderBackend() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), ignored -> {});
    }

    MandelbrotPerturbationRenderBackend(int workerCount) {
        this(workerCount, ignored -> {});
    }

    /** Creates an instrumented backend for benchmarks and render diagnostics. */
    public MandelbrotPerturbationRenderBackend(
            int workerCount,
            Consumer<DeepZoomTimingStats> diagnosticsCompleted
    ) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be at least 1");
        }
        this.workerCount = workerCount;
        this.diagnosticsCompleted = Objects.requireNonNull(diagnosticsCompleted);
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
        PreciseRenderGrid grid = frame.renderGrid().preciseGrid();
        if (grid == null) {
            grid = frame.job().preciseGrid();
        }
        long referenceStarted = System.nanoTime();
        ReferenceOrbit reference = ReferenceOrbit.create(frame.job(), grid, cancelled);
        double referenceOrbitMs = elapsedMs(referenceStarted);
        if (reference == null) {
            return frame;
        }

        long coordinatesStarted = System.nanoTime();
        CoordinateDeltas deltas = CoordinateDeltas.create(
                grid, reference, frame.job().width(), frame.job().height(), cancelled);
        double coordinatePreparationMs = elapsedMs(coordinatesStarted);
        if (deltas == null) {
            return frame;
        }

        List<RenderRegion> queuedTiles = orderedTiles(frame.job()).stream()
                .filter(tile -> !frame.validity().isRegionReady(tile))
                .toList();
        java.util.concurrent.ConcurrentLinkedQueue<RenderRegion> tileQueue =
                new java.util.concurrent.ConcurrentLinkedQueue<>(queuedTiles);
        int workerTaskCount = Math.min(workerCount, queuedTiles.size());
        AtomicInteger completedTiles = new AtomicInteger();
        AtomicLong firstRegionNanos = new AtomicLong(-1L);
        LongAdder calculatedPixels = new LongAdder();
        LongAdder executedIterations = new LongAdder();
        LongAdder highPrecisionFallbackPixels = new LongAdder();
        java.util.concurrent.ConcurrentLinkedQueue<Long> tileTimes =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        List<Callable<Void>> workerTasks = new ArrayList<>(workerTaskCount);

        for (int worker = 0; worker < workerTaskCount; worker++) {
            workerTasks.add(() -> {
                RenderRegion tile;
                while (!cancelled.getAsBoolean() && (tile = tileQueue.poll()) != null) {
                    long tileStarted = System.nanoTime();
                    boolean complete = calculateTile(
                            frame, deltas, reference, tile, cancelled,
                            region -> {
                                firstRegionNanos.compareAndSet(-1L, System.nanoTime());
                                regionCompleted.accept(region);
                            },
                            calculatedPixels, executedIterations,
                            highPrecisionFallbackPixels);
                    if (!complete) {
                        return null;
                    }
                    completedTiles.incrementAndGet();
                    tileTimes.add(System.nanoTime() - tileStarted);
                }
                return null;
            });
        }

        try {
            for (Future<Void> future : workers.invokeAll(workerTasks)) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException exception) {
                    throw new IllegalStateException(
                            "Perturbation tile calculation failed", exception.getCause());
                }
            }
        } finally {
            int completed = completedTiles.get();
            long pixels = calculatedPixels.sum();
            long first = firstRegionNanos.get();
            diagnosticsCompleted.accept(new DeepZoomTimingStats(
                    referenceOrbitMs,
                    coordinatePreparationMs,
                    first < 0L ? -1.0 : (first - started) / 1_000_000.0,
                    workerTaskCount,
                    queuedTiles.size(),
                    completed,
                    queuedTiles.size() - completed,
                    pixels,
                    highPrecisionFallbackPixels.sum(),
                    pixels == 0L ? 0.0 : (double) executedIterations.sum() / pixels));
        }

        if (cancelled.getAsBoolean()) {
            return frame;
        }
        if (timingCompleted != null) {
            timingCompleted.accept(createTimingStats(tileTimes));
        }
        return frame;
    }

    private static boolean calculateTile(
            RenderFrame frame,
            CoordinateDeltas deltas,
            ReferenceOrbit reference,
            RenderRegion tile,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            LongAdder calculatedPixels,
            LongAdder executedIterations,
            LongAdder highPrecisionFallbackPixels
    ) {
        for (RenderRegion span : frame.validity().missingRowSpans(tile)) {
            SpanCalculation calculation = calculateSpan(
                    frame, deltas, reference, span, cancelled);
            calculatedPixels.add(calculation.pixelCount());
            executedIterations.add(calculation.executedIterations());
            highPrecisionFallbackPixels.add(calculation.highPrecisionFallbackCount());
            if (!calculation.complete()) {
                return false;
            }
            frame.validity().markReady(span);
            regionCompleted.accept(span);
        }
        return true;
    }

    private static SpanCalculation calculateSpan(
            RenderFrame frame,
            CoordinateDeltas deltas,
            ReferenceOrbit reference,
            RenderRegion span,
            BooleanSupplier cancelled
    ) {
        SamplePlane samples = frame.samplePlane();
        long pixelCount = 0L;
        long executedIterations = 0L;
        long highPrecisionFallbackCount = 0L;
        for (int y = span.y(); y < span.y() + span.height() && !cancelled.getAsBoolean(); y++) {
            double deltaImaginary = deltas.imaginary()[y];
            for (int x = span.x(); x < span.x() + span.width(); x++) {
                if (cancelled.getAsBoolean()) {
                    return new SpanCalculation(
                            pixelCount, executedIterations, highPrecisionFallbackCount, false);
                }
                double deltaReal = deltas.real()[x];
                double cReal = reference.cRealAsDouble() + deltaReal;
                double cImaginary = reference.cImaginaryAsDouble() + deltaImaginary;
                if (isSafelyInsideKnownInterior(cReal, cImaginary)) {
                    samples.set(x, y, new FractalSample(frame.job().maxIterations(), false, 0.0, 0.0));
                } else {
                    PerturbationResult result = perturb(
                            reference, deltaReal, deltaImaginary, frame.job().maxIterations());
                    executedIterations += result.executedIterations();
                    FractalSample sample = result.sample();
                    if (!result.reliable()) {
                        sample = calculateHighPrecision(
                                deltas.realCoordinates()[x],
                                deltas.imaginaryCoordinates()[y],
                                deltas.mathContext(),
                                frame.job().maxIterations(),
                                cancelled);
                        if (sample == null) {
                            return new SpanCalculation(
                                    pixelCount, executedIterations,
                                    highPrecisionFallbackCount, false);
                        }
                        executedIterations += sample.iterations();
                        highPrecisionFallbackCount++;
                    }
                    samples.set(x, y, sample);
                }
                pixelCount++;
            }
        }
        return new SpanCalculation(
                pixelCount, executedIterations, highPrecisionFallbackCount,
                !cancelled.getAsBoolean());
    }

    private record SpanCalculation(
            long pixelCount,
            long executedIterations,
            long highPrecisionFallbackCount,
            boolean complete
    ) {}

    private record CoordinateDeltas(
            double[] real,
            double[] imaginary,
            BigDecimal[] realCoordinates,
            BigDecimal[] imaginaryCoordinates,
            MathContext mathContext
    ) {
        static CoordinateDeltas create(
                PreciseRenderGrid grid,
                ReferenceOrbit reference,
                int width,
                int height,
                BooleanSupplier cancelled
        ) {
            double[] real = new double[width];
            double[] imaginary = new double[height];
            BigDecimal[] realCoordinates = new BigDecimal[width];
            BigDecimal[] imaginaryCoordinates = new BigDecimal[height];
            for (int x = 0; x < width; x++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                realCoordinates[x] = grid.realAt(x);
                real[x] = realCoordinates[x].subtract(
                        reference.cReal(), grid.mathContext()).doubleValue();
            }
            for (int y = 0; y < height; y++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                imaginaryCoordinates[y] = grid.imaginaryAt(y);
                imaginary[y] = imaginaryCoordinates[y].subtract(
                        reference.cImaginary(), grid.mathContext()).doubleValue();
            }
            return new CoordinateDeltas(
                    real, imaginary, realCoordinates, imaginaryCoordinates,
                    grid.mathContext());
        }
    }

    private static TileTimingStats createTimingStats(
            java.util.concurrent.ConcurrentLinkedQueue<Long> timings
    ) {
        if (timings.isEmpty()) {
            return new TileTimingStats(0, 0.0, 0.0, 0.0);
        }
        List<Long> sorted = new ArrayList<>(timings);
        sorted.sort(Long::compare);
        int size = sorted.size();
        int middle = size / 2;
        double median = size % 2 == 0
                ? (sorted.get(middle - 1).doubleValue() + sorted.get(middle)) / 2.0
                : sorted.get(middle);
        return new TileTimingStats(size, sorted.getFirst() / 1_000_000.0,
                median / 1_000_000.0, sorted.getLast() / 1_000_000.0);
    }

    private static double elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    /**
     * Exact cardioid/bulb tests are used only well inside their boundaries.
     * The margin keeps this performance shortcut from deciding ambiguous deep
     * boundary points using a rounded coordinate.
     */
    static boolean isSafelyInsideKnownInterior(double real, double imaginary) {
        double imaginarySquared = imaginary * imaginary;
        double cardioidX = real - 0.25;
        double q = cardioidX * cardioidX + imaginarySquared;
        if (q * (q + cardioidX) - 0.25 * imaginarySquared < -INTERIOR_SAFETY_MARGIN) {
            return true;
        }
        double bulbX = real + 1.0;
        return bulbX * bulbX + imaginarySquared < 0.0625 - INTERIOR_SAFETY_MARGIN;
    }

    private static PerturbationResult perturb(
            ReferenceOrbit reference, double deltaCReal, double deltaCImaginary, int maxIterations
    ) {
        double deltaReal = 0.0;
        double deltaImaginary = 0.0;
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;
        while (iteration < maxIterations) {
            if (iteration >= reference.lastValidIteration()) {
                return new PerturbationResult(null, iteration, false);
            }
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
            double magnitudeSquared = zr * zr + zi * zi;
            double referenceMagnitudeSquared = reference.realAt(iteration)
                    * reference.realAt(iteration)
                    + reference.imaginaryAt(iteration) * reference.imaginaryAt(iteration);
            if (!Double.isFinite(magnitudeSquared)
                    || magnitudeSquared < GLITCH_RELATIVE_THRESHOLD * referenceMagnitudeSquared) {
                return new PerturbationResult(null, iteration, false);
            }
            if (magnitudeSquared > 4.0) {
                return new PerturbationResult(
                        new FractalSample(iteration, true, zr, zi), iteration, true);
            }
        }
        return new PerturbationResult(
                new FractalSample(iteration, false, zr, zi), iteration, true);
    }

    private record PerturbationResult(
            FractalSample sample,
            int executedIterations,
            boolean reliable
    ) {}

    private static FractalSample calculateHighPrecision(
            BigDecimal cReal,
            BigDecimal cImaginary,
            MathContext context,
            int maxIterations,
            BooleanSupplier cancelled
    ) {
        BigDecimal zr = BigDecimal.ZERO;
        BigDecimal zi = BigDecimal.ZERO;
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return null;
            }
            BigDecimal nextReal = zr.multiply(zr, context)
                    .subtract(zi.multiply(zi, context), context).add(cReal, context);
            BigDecimal nextImaginary = zr.multiply(zi, context)
                    .multiply(BigDecimal.valueOf(2), context).add(cImaginary, context);
            zr = nextReal;
            zi = nextImaginary;
            if (zr.multiply(zr, context).add(zi.multiply(zi, context)).compareTo(FOUR) > 0) {
                return new FractalSample(
                        iteration, true, zr.doubleValue(), zi.doubleValue());
            }
        }
        return new FractalSample(maxIterations, false, zr.doubleValue(), zi.doubleValue());
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
    private record ReferenceOrbit(
            BigDecimal cReal,
            BigDecimal cImaginary,
            double[] real,
            double[] imaginary,
            int lastValidIteration
    ) {
        static ReferenceOrbit create(
                RenderJob job,
                PreciseRenderGrid grid,
                BooleanSupplier cancelled
        ) {
            int centerX = (job.width() - 1) / 2;
            int centerY = (job.height() - 1) / 2;
            BigDecimal cReal = grid.realAt(centerX);
            BigDecimal cImaginary = grid.imaginaryAt(centerY);
            MathContext context = grid.mathContext();
            double[] real = new double[job.maxIterations() + 1];
            double[] imaginary = new double[job.maxIterations() + 1];
            BigDecimal zr = BigDecimal.ZERO;
            BigDecimal zi = BigDecimal.ZERO;
            int lastValidIteration = 0;
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
                lastValidIteration = iteration + 1;
                if (zr.multiply(zr, context).add(zi.multiply(zi, context)).compareTo(FOUR) > 0) {
                    break;
                }
            }
            return new ReferenceOrbit(
                    cReal, cImaginary, real, imaginary, lastValidIteration);
        }

        double realAt(int iteration) { return real[iteration]; }
        double imaginaryAt(int iteration) { return imaginary[iteration]; }
        double cRealAsDouble() { return cReal.doubleValue(); }
        double cImaginaryAsDouble() { return cImaginary.doubleValue(); }
    }
}
