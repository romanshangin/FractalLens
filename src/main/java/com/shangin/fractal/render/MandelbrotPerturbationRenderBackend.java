package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
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
    /* Power of two so polling in the hot loop uses a cheap mask. */
    private static final int CANCELLATION_CHECK_INTERVAL = 32;
    private static final int MAX_ADDITIONAL_REFERENCES = 64;
    static final long DEFAULT_REFERENCE_CACHE_BYTES = 32L * 1024L * 1024L;

    private final ExecutorService workers;
    private final int workerCount;
    private final Consumer<DeepZoomTimingStats> diagnosticsCompleted;
    private final ReferenceOrbitCache referenceCache;
    private final boolean blaEnabled;

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
        this(workerCount, diagnosticsCompleted, DEFAULT_REFERENCE_CACHE_BYTES);
    }

    MandelbrotPerturbationRenderBackend(
            int workerCount,
            Consumer<DeepZoomTimingStats> diagnosticsCompleted,
            long referenceCacheBytes
    ) {
        this(workerCount, diagnosticsCompleted, referenceCacheBytes, true);
    }

    MandelbrotPerturbationRenderBackend(
            int workerCount,
            Consumer<DeepZoomTimingStats> diagnosticsCompleted,
            long referenceCacheBytes,
            boolean blaEnabled
    ) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be at least 1");
        }
        if (referenceCacheBytes < 1) {
            throw new IllegalArgumentException("Reference cache capacity must be positive");
        }
        this.workerCount = workerCount;
        this.diagnosticsCompleted = Objects.requireNonNull(diagnosticsCompleted);
        this.referenceCache = new ReferenceOrbitCache(referenceCacheBytes);
        this.blaEnabled = blaEnabled;
        AtomicInteger sequence = new AtomicInteger();
        workers = RenderDiagnostics.workerPool(workerCount, runnable -> {
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
        RenderDiagnostics.mark("reference_start");
        long referenceStarted = System.nanoTime();
        ReferenceOrbit reference = referenceCache.acquire(frame.job(), grid, cancelled);
        RenderDiagnostics.mark("reference_end");
        double referenceOrbitMs = elapsedMs(referenceStarted);
        if (reference == null) {
            return frame;
        }
        RenderDiagnostics.mark("coordinates_start");
        long coordinatesStarted = System.nanoTime();
        CoordinateDeltas deltas = CoordinateDeltas.create(
                grid, reference, frame.job().width(), frame.job().height(), cancelled);
        RenderDiagnostics.mark("coordinates_end");
        double coordinatePreparationMs = elapsedMs(coordinatesStarted);
        if (deltas == null) {
            return frame;
        }
        RenderDiagnostics.mark("bla_start");
        long blaStarted = System.nanoTime();
        MandelbrotBlaTable bla = blaEnabled ? MandelbrotBlaTable.create(
                reference.real(), reference.imaginary(), reference.lastBoundedIteration(),
                deltas.maximumDeltaMagnitude(), cancelled) : null;
        RenderDiagnostics.mark("bla_end");
        double blaPreparationMs = elapsedMs(blaStarted);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return frame;
        }
        ReferencePool referencePool = new ReferencePool(
                frame.job(), grid.mathContext(), reference, bla);

        RenderDiagnostics.mark("planning_start");
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
                            frame, deltas, referencePool, tile, cancelled,
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

        RenderDiagnostics.mark("planning_end");
        RenderDiagnostics.add("candidate_tiles", queuedTiles.size());
        RenderDiagnostics.add("planned_tasks", workerTasks.size());
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
                    blaPreparationMs,
                    first < 0L ? -1.0 : (first - started) / 1_000_000.0,
                    workerTaskCount,
                    queuedTiles.size(),
                    completed,
                    queuedTiles.size() - completed,
                    pixels,
                    highPrecisionFallbackPixels.sum(),
                    referencePool.modifiedRebaseCount(),
                    referencePool.blaStepCount(),
                    referencePool.blaSkippedIterationCount(),
                    referencePool.additionalReferenceCount(),
                    referencePool.additionalReferenceOrbitMs(),
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
            ReferencePool referencePool,
            RenderRegion tile,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            LongAdder calculatedPixels,
            LongAdder executedIterations,
            LongAdder highPrecisionFallbackPixels
    ) {
        for (RenderRegion span : frame.validity().missingRowSpans(tile)) {
            SpanCalculation calculation = calculateSpan(
                    frame, deltas, referencePool, span, cancelled);
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
            ReferencePool referencePool,
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
                double cReal = referencePool.primary().cRealAsDouble() + deltaReal;
                double cImaginary = referencePool.primary().cImaginaryAsDouble() + deltaImaginary;
                if (deltas.requiresHighPrecision(x, y)) {
                    PerturbationResult result = perturbScaled(
                            referencePool.primary(),
                            deltas.scaledDelta(x, y),
                            frame.job().maxIterations(),
                            cancelled);
                    referencePool.recordWork(result);
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
                } else if (isSafelyInsideKnownInterior(cReal, cImaginary)) {
                    samples.set(x, y, new FractalSample(frame.job().maxIterations(), false, 0.0, 0.0));
                } else {
                    PerturbationResult result = referencePool.calculate(
                            deltas.realCoordinates()[x],
                            deltas.imaginaryCoordinates()[y],
                            deltaReal,
                            deltaImaginary,
                            frame.job().maxIterations(),
                            cancelled);
                    referencePool.recordWork(result);
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
            boolean[] realNeedsHighPrecision,
            boolean[] imaginaryNeedsHighPrecision,
            ScaledValue[] scaledReal,
            ScaledValue[] scaledImaginary,
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
            boolean[] realNeedsHighPrecision = new boolean[width];
            boolean[] imaginaryNeedsHighPrecision = new boolean[height];
            ScaledValue[] scaledReal = new ScaledValue[width];
            ScaledValue[] scaledImaginary = new ScaledValue[height];
            for (int x = 0; x < width; x++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                realCoordinates[x] = grid.realAt(x);
                BigDecimal exactDelta = realCoordinates[x].subtract(
                        reference.cReal(), grid.mathContext());
                real[x] = exactDelta.doubleValue();
                realNeedsHighPrecision[x] = losesPixelResolution(
                        exactDelta, real[x], grid.realStep());
                if (realNeedsHighPrecision[x]) {
                    scaledReal[x] = ScaledValue.fromBigDecimal(exactDelta);
                }
            }
            for (int y = 0; y < height; y++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                imaginaryCoordinates[y] = grid.imaginaryAt(y);
                BigDecimal exactDelta = imaginaryCoordinates[y].subtract(
                        reference.cImaginary(), grid.mathContext());
                imaginary[y] = exactDelta.doubleValue();
                imaginaryNeedsHighPrecision[y] = losesPixelResolution(
                        exactDelta, imaginary[y], grid.imaginaryStep());
                if (imaginaryNeedsHighPrecision[y]) {
                    scaledImaginary[y] = ScaledValue.fromBigDecimal(exactDelta);
                }
            }
            return new CoordinateDeltas(
                    real, imaginary, realCoordinates, imaginaryCoordinates,
                    realNeedsHighPrecision, imaginaryNeedsHighPrecision,
                    scaledReal, scaledImaginary,
                    grid.mathContext());
        }

        boolean requiresHighPrecision(int x, int y) {
            return realNeedsHighPrecision[x] || imaginaryNeedsHighPrecision[y];
        }

        double maximumDeltaMagnitude() {
            double maximumReal = Math.max(Math.abs(real[0]), Math.abs(real[real.length - 1]));
            double maximumImaginary = Math.max(
                    Math.abs(imaginary[0]), Math.abs(imaginary[imaginary.length - 1]));
            return Math.nextUp(Math.hypot(maximumReal, maximumImaginary));
        }

        ScaledComplex scaledDelta(int x, int y) {
            ScaledValue realValue = scaledReal[x] == null
                    ? ScaledValue.fromDouble(real[x]) : scaledReal[x];
            ScaledValue imaginaryValue = scaledImaginary[y] == null
                    ? ScaledValue.fromDouble(imaginary[y]) : scaledImaginary[y];
            return ScaledComplex.from(realValue, imaginaryValue);
        }

        private static boolean losesPixelResolution(
                BigDecimal exactDelta,
                double approximateDelta,
                BigDecimal pixelStep
        ) {
            if (!Double.isFinite(approximateDelta)) {
                return true;
            }
            if (exactDelta.signum() == 0) {
                return false;
            }
            if (approximateDelta == 0.0) {
                return true;
            }
            double approximateStep = pixelStep.doubleValue();
            return !(approximateStep > 0.0)
                    || !Double.isFinite(approximateStep)
                    || Math.ulp(approximateDelta) * 16.0 > approximateStep;
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
            ReferenceOrbit reference, double deltaCReal, double deltaCImaginary, int maxIterations,
            MandelbrotBlaTable bla,
            BooleanSupplier cancelled
    ) {
        return bla == null || !bla.supportsDelta(deltaCReal, deltaCImaginary)
                ? perturbScalar(reference, deltaCReal, deltaCImaginary, maxIterations, cancelled)
                : perturbWithBla(reference, deltaCReal, deltaCImaginary, maxIterations, bla, cancelled);
    }

    private static PerturbationResult perturbScalar(
            ReferenceOrbit reference, double deltaCReal, double deltaCImaginary, int maxIterations,
            BooleanSupplier cancelled
    ) {
        double[] referenceReals = reference.real();
        double[] referenceImaginaries = reference.imaginary();
        double[] glitchThresholds = reference.glitchThresholds();
        int referenceLimit = reference.lastValidIteration();
        double deltaReal = 0.0;
        double deltaImaginary = 0.0;
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;
        int referenceIteration = 0;
        int modifiedRebases = 0;
        while (iteration < maxIterations) {
            if ((iteration & (CANCELLATION_CHECK_INTERVAL - 1)) == 0
                    && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
                return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
            }
            if (referenceIteration >= referenceLimit) {
                return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
            }
            double referenceReal = referenceReals[referenceIteration];
            double referenceImaginary = referenceImaginaries[referenceIteration];
            double nextDeltaReal = 2.0 * (referenceReal * deltaReal - referenceImaginary * deltaImaginary)
                    + deltaReal * deltaReal - deltaImaginary * deltaImaginary + deltaCReal;
            double nextDeltaImaginary = 2.0 * (referenceReal * deltaImaginary + referenceImaginary * deltaReal)
                    + 2.0 * deltaReal * deltaImaginary + deltaCImaginary;
            deltaReal = nextDeltaReal;
            deltaImaginary = nextDeltaImaginary;
            iteration++;
            referenceIteration++;
            zr = referenceReals[referenceIteration] + deltaReal;
            zi = referenceImaginaries[referenceIteration] + deltaImaginary;
            double magnitudeSquared = zr * zr + zi * zi;
            if (!Double.isFinite(magnitudeSquared)) {
                return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
            }
            if (magnitudeSquared > 4.0) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
                }
                return new PerturbationResult(
                        new FractalSample(iteration, true, zr, zi), iteration, true,
                        modifiedRebases, 0, 0);
            }
            double deltaMagnitudeSquared = deltaReal * deltaReal
                    + deltaImaginary * deltaImaginary;
            if (magnitudeSquared < deltaMagnitudeSquared) {
                deltaReal = zr;
                deltaImaginary = zi;
                referenceIteration = 0;
                modifiedRebases++;
            } else if (magnitudeSquared < glitchThresholds[referenceIteration]) {
                return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
            }
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
        }
        return new PerturbationResult(
                new FractalSample(iteration, false, zr, zi), iteration, true,
                modifiedRebases, 0, 0);
    }

    private static PerturbationResult perturbWithBla(
            ReferenceOrbit reference, double deltaCReal, double deltaCImaginary, int maxIterations,
            MandelbrotBlaTable bla,
            BooleanSupplier cancelled
    ) {
        double[] referenceReals = reference.real();
        double[] referenceImaginaries = reference.imaginary();
        double[] glitchThresholds = reference.glitchThresholds();
        int referenceLimit = reference.lastValidIteration();
        double deltaReal = 0.0;
        double deltaImaginary = 0.0;
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;
        int referenceIteration = 0;
        int modifiedRebases = 0;
        int blaSteps = 0;
        int blaSkippedIterations = 0;
        int workSteps = 0;
        orbit:
        while (iteration < maxIterations) {
            if ((workSteps++ & (CANCELLATION_CHECK_INTERVAL - 1)) == 0
                    && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
                return new PerturbationResult(null, iteration, false, modifiedRebases,
                        blaSteps, blaSkippedIterations);
            }
            if (referenceIteration >= referenceLimit) {
                return new PerturbationResult(null, iteration, false, modifiedRebases,
                        blaSteps, blaSkippedIterations);
            }
            double currentDeltaMagnitudeSquared = deltaReal * deltaReal
                    + deltaImaginary * deltaImaginary;
            MandelbrotBlaTable.Step block = bla.lookup(referenceIteration,
                    currentDeltaMagnitudeSquared, maxIterations - iteration);
            while (block != null) {
                double nextDeltaReal = block.aReal() * deltaReal
                        - block.aImaginary() * deltaImaginary
                        + block.bReal() * deltaCReal - block.bImaginary() * deltaCImaginary;
                double nextDeltaImaginary = block.aReal() * deltaImaginary
                        + block.aImaginary() * deltaReal
                        + block.bReal() * deltaCImaginary + block.bImaginary() * deltaCReal;
                int nextReferenceIteration = referenceIteration + block.length();
                double nextZr = referenceReals[nextReferenceIteration] + nextDeltaReal;
                double nextZi = referenceImaginaries[nextReferenceIteration] + nextDeltaImaginary;
                double magnitudeSquared = nextZr * nextZr + nextZi * nextZi;
                double deltaMagnitudeSquared = nextDeltaReal * nextDeltaReal
                        + nextDeltaImaginary * nextDeltaImaginary;
                // Never report an escape at the end of a skipped block. Try a
                // shorter block, then scalar steps near escape, preserving the
                // exact iteration and orbit values used by smooth coloring.
                if (Double.isFinite(magnitudeSquared) && magnitudeSquared <= 4.0
                        && (magnitudeSquared >= glitchThresholds[nextReferenceIteration]
                            || magnitudeSquared < deltaMagnitudeSquared)) {
                    deltaReal = nextDeltaReal;
                    deltaImaginary = nextDeltaImaginary;
                    zr = nextZr;
                    zi = nextZi;
                    iteration += block.length();
                    referenceIteration = nextReferenceIteration;
                    blaSteps++;
                    blaSkippedIterations += block.length();
                    if (magnitudeSquared < deltaMagnitudeSquared) {
                        deltaReal = zr;
                        deltaImaginary = zi;
                        referenceIteration = 0;
                        modifiedRebases++;
                    }
                    continue orbit;
                }
                block = bla.lookup(referenceIteration, currentDeltaMagnitudeSquared,
                        block.length() - 1);
            }
            double referenceReal = referenceReals[referenceIteration];
            double referenceImaginary = referenceImaginaries[referenceIteration];
            double nextDeltaReal = 2.0 * (referenceReal * deltaReal - referenceImaginary * deltaImaginary)
                    + deltaReal * deltaReal - deltaImaginary * deltaImaginary + deltaCReal;
            double nextDeltaImaginary = 2.0 * (referenceReal * deltaImaginary + referenceImaginary * deltaReal)
                    + 2.0 * deltaReal * deltaImaginary + deltaCImaginary;
            deltaReal = nextDeltaReal;
            deltaImaginary = nextDeltaImaginary;
            iteration++;
            referenceIteration++;
            zr = referenceReals[referenceIteration] + deltaReal;
            zi = referenceImaginaries[referenceIteration] + deltaImaginary;
            double magnitudeSquared = zr * zr + zi * zi;
            if (!Double.isFinite(magnitudeSquared)) {
                return new PerturbationResult(null, iteration, false, modifiedRebases,
                        blaSteps, blaSkippedIterations);
            }
            if (magnitudeSquared > 4.0) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return new PerturbationResult(null, iteration, false, modifiedRebases,
                            blaSteps, blaSkippedIterations);
                }
                return new PerturbationResult(
                        new FractalSample(iteration, true, zr, zi), iteration, true,
                        modifiedRebases, blaSteps, blaSkippedIterations);
            }
            double deltaMagnitudeSquared = deltaReal * deltaReal
                    + deltaImaginary * deltaImaginary;
            if (magnitudeSquared < deltaMagnitudeSquared) {
                deltaReal = zr;
                deltaImaginary = zi;
                referenceIteration = 0;
                modifiedRebases++;
            } else if (magnitudeSquared < glitchThresholds[referenceIteration]) {
                return new PerturbationResult(null, iteration, false, modifiedRebases,
                        blaSteps, blaSkippedIterations);
            }
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new PerturbationResult(null, iteration, false, modifiedRebases,
                    blaSteps, blaSkippedIterations);
        }
        return new PerturbationResult(
                new FractalSample(iteration, false, zr, zi), iteration, true,
                modifiedRebases, blaSteps, blaSkippedIterations);
    }

    /** Perturbation recurrence with a separate binary exponent for sub-double deltas. */
    private static PerturbationResult perturbScaled(
            ReferenceOrbit reference,
            ScaledComplex deltaC,
            int maxIterations,
            BooleanSupplier cancelled
    ) {
        double[] referenceReals = reference.real();
        double[] referenceImaginaries = reference.imaginary();
        double[] glitchThresholds = reference.glitchThresholds();
        int iterationLimit = Math.min(maxIterations, reference.lastValidIteration());
        ScaledComplex delta = ScaledComplex.zero();
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;
        while (iteration < iterationLimit) {
            if ((iteration & (CANCELLATION_CHECK_INTERVAL - 1)) == 0
                    && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
                return new PerturbationResult(null, iteration, false, 0, 0, 0);
            }
            double referenceReal = referenceReals[iteration];
            double referenceImaginary = referenceImaginaries[iteration];
            delta.advance(referenceReal, referenceImaginary, deltaC);
            iteration++;
            double deltaReal = delta.realAsDouble();
            double deltaImaginary = delta.imaginaryAsDouble();
            zr = referenceReals[iteration] + deltaReal;
            zi = referenceImaginaries[iteration] + deltaImaginary;
            if (delta.magnitudeEscapes(reference, iteration)) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return new PerturbationResult(null, iteration, false, 0, 0, 0);
                }
                return new PerturbationResult(
                        new FractalSample(iteration, true, zr, zi), iteration, true, 0, 0, 0);
            }
            double magnitudeSquared = zr * zr + zi * zi;
            if (!Double.isFinite(magnitudeSquared)
                    || magnitudeSquared < glitchThresholds[iteration]) {
                return new PerturbationResult(null, iteration, false, 0, 0, 0);
            }
        }
        if (iteration < maxIterations
                || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return new PerturbationResult(null, iteration, false, 0, 0, 0);
        }
        return new PerturbationResult(
                new FractalSample(iteration, false, zr, zi), iteration, true, 0, 0, 0);
    }

    /** Finite nonzero number represented as mantissa * 2^exponent. */
    private record ScaledValue(double mantissa, long exponent) {
        private static final double LOG2_10 = 3.32192809488736234787;
        private static final ScaledValue ZERO = new ScaledValue(0.0, 0L);

        static ScaledValue fromDouble(double value) {
            if (value == 0.0) {
                return ZERO;
            }
            int exponent = Math.getExponent(value);
            double mantissa = Math.scalb(value, -exponent);
            if (Math.abs(mantissa) < 1.0) {
                int adjustment = Math.getExponent(mantissa);
                mantissa = Math.scalb(mantissa, -adjustment);
                exponent += adjustment;
            }
            return new ScaledValue(mantissa, exponent);
        }

        static ScaledValue fromBigDecimal(BigDecimal value) {
            if (value.signum() == 0) {
                return ZERO;
            }
            int decimalExponent = value.precision() - value.scale() - 1;
            double normalizedDecimal = value.movePointLeft(decimalExponent).doubleValue();
            double rawBinaryExponent = decimalExponent * LOG2_10;
            long binaryExponent = (long) Math.floor(rawBinaryExponent);
            double mantissa = normalizedDecimal
                    * Math.pow(2.0, rawBinaryExponent - binaryExponent);
            int adjustment = Math.getExponent(mantissa);
            return new ScaledValue(
                    Math.scalb(mantissa, -adjustment), binaryExponent + adjustment);
        }

        boolean isZero() {
            return mantissa == 0.0;
        }
    }

    /** Mutable complex value whose components share one binary exponent. */
    private static final class ScaledComplex {
        private double real;
        private double imaginary;
        private long exponent;

        private ScaledComplex(double real, double imaginary, long exponent) {
            this.real = real;
            this.imaginary = imaginary;
            this.exponent = exponent;
            normalize();
        }

        static ScaledComplex zero() {
            return new ScaledComplex(0.0, 0.0, 0L);
        }

        static ScaledComplex from(ScaledValue real, ScaledValue imaginary) {
            if (real.isZero() && imaginary.isZero()) {
                return zero();
            }
            long exponent = real.isZero() ? imaginary.exponent()
                    : imaginary.isZero() ? real.exponent()
                    : Math.max(real.exponent(), imaginary.exponent());
            return new ScaledComplex(
                    align(real.mantissa(), real.exponent(), exponent),
                    align(imaginary.mantissa(), imaginary.exponent(), exponent),
                    exponent);
        }

        void advance(double referenceReal, double referenceImaginary, ScaledComplex deltaC) {
            double linearReal = 2.0 * (referenceReal * real - referenceImaginary * imaginary);
            double linearImaginary = 2.0 * (referenceReal * imaginary + referenceImaginary * real);
            double quadraticReal = real * real - imaginary * imaginary;
            double quadraticImaginary = 2.0 * real * imaginary;
            long linearExponent = exponent;
            long quadraticExponent = exponent * 2L;
            long commonExponent = largestExponent(
                    linearReal, linearImaginary, linearExponent,
                    quadraticReal, quadraticImaginary, quadraticExponent,
                    deltaC.real, deltaC.imaginary, deltaC.exponent);
            real = align(linearReal, linearExponent, commonExponent)
                    + align(quadraticReal, quadraticExponent, commonExponent)
                    + align(deltaC.real, deltaC.exponent, commonExponent);
            imaginary = align(linearImaginary, linearExponent, commonExponent)
                    + align(quadraticImaginary, quadraticExponent, commonExponent)
                    + align(deltaC.imaginary, deltaC.exponent, commonExponent);
            exponent = commonExponent;
            normalize();
        }

        boolean magnitudeEscapes(ReferenceOrbit reference, int iteration) {
            ScaledValue referenceMargin = reference.escapeMarginAt(iteration);
            double referenceReal = reference.realAt(iteration);
            double referenceImaginary = reference.imaginaryAt(iteration);
            double linear = 2.0 * (referenceReal * real + referenceImaginary * imaginary);
            double quadratic = real * real + imaginary * imaginary;
            long quadraticExponent = exponent * 2L;
            long commonExponent = referenceMargin.isZero() ? Long.MIN_VALUE
                    : referenceMargin.exponent();
            if (linear != 0.0) {
                commonExponent = Math.max(commonExponent, exponent);
            }
            if (quadratic != 0.0) {
                commonExponent = Math.max(commonExponent, quadraticExponent);
            }
            if (commonExponent == Long.MIN_VALUE) {
                return false;
            }
            double sum = align(referenceMargin.mantissa(), referenceMargin.exponent(), commonExponent)
                    + align(linear, exponent, commonExponent)
                    + align(quadratic, quadraticExponent, commonExponent);
            return sum > 0.0;
        }

        double realAsDouble() {
            return asDouble(real, exponent);
        }

        double imaginaryAsDouble() {
            return asDouble(imaginary, exponent);
        }

        private void normalize() {
            double largest = Math.max(Math.abs(real), Math.abs(imaginary));
            if (largest == 0.0) {
                exponent = 0L;
                return;
            }
            int adjustment = Math.getExponent(largest);
            real = Math.scalb(real, -adjustment);
            imaginary = Math.scalb(imaginary, -adjustment);
            exponent += adjustment;
        }

        private static long largestExponent(
                double firstReal, double firstImaginary, long firstExponent,
                double secondReal, double secondImaginary, long secondExponent,
                double thirdReal, double thirdImaginary, long thirdExponent
        ) {
            long largest = Long.MIN_VALUE;
            if (firstReal != 0.0 || firstImaginary != 0.0) {
                largest = firstExponent;
            }
            if (secondReal != 0.0 || secondImaginary != 0.0) {
                largest = Math.max(largest, secondExponent);
            }
            if (thirdReal != 0.0 || thirdImaginary != 0.0) {
                largest = Math.max(largest, thirdExponent);
            }
            return largest == Long.MIN_VALUE ? 0L : largest;
        }

        private static double align(double mantissa, long exponent, long targetExponent) {
            if (mantissa == 0.0) {
                return 0.0;
            }
            long shift = exponent - targetExponent;
            if (shift < -1074L) {
                return 0.0;
            }
            if (shift > 1023L) {
                return Math.copySign(Double.POSITIVE_INFINITY, mantissa);
            }
            return Math.scalb(mantissa, (int) shift);
        }

        private static double asDouble(double mantissa, long exponent) {
            if (mantissa == 0.0 || exponent < -1074L) {
                return Math.copySign(0.0, mantissa);
            }
            if (exponent > 1023L) {
                return Math.copySign(Double.POSITIVE_INFINITY, mantissa);
            }
            return Math.scalb(mantissa, (int) exponent);
        }
    }

    private record PerturbationResult(
            FractalSample sample,
            int executedIterations,
            boolean reliable,
            int modifiedRebaseCount,
            int blaStepCount,
            int blaSkippedIterationCount
    ) {}

    /**
     * Creates a thread-safe point sampler that keeps one shared perturbation
     * reference pool for a follow-up deep-zoom pass such as antialiasing.
     */
    public static Optional<PreciseSampler> createPreciseSampler(
            RenderJob job,
            BooleanSupplier cancelled
    ) {
        Objects.requireNonNull(job);
        Objects.requireNonNull(cancelled);
        if (job.formula().preset() != FractalPreset.MANDELBROT
                || job.formula().orbitTrap() != OrbitTrap.NONE) {
            throw new IllegalArgumentException(
                    "Precise perturbation sampling supports Mandelbrot without orbit traps only");
        }
        PreciseRenderGrid grid = job.preciseGrid();
        ReferenceOrbit primary = ReferenceOrbit.create(job, grid, cancelled);
        if (primary == null) {
            return Optional.empty();
        }
        double maximumReal = Math.max(
                grid.realAt(0).subtract(primary.cReal(), grid.mathContext()).abs().doubleValue(),
                grid.realAt(job.width() - 1).subtract(
                        primary.cReal(), grid.mathContext()).abs().doubleValue())
                + grid.realStep().doubleValue();
        double maximumImaginary = Math.max(
                grid.imaginaryAt(0).subtract(primary.cImaginary(), grid.mathContext()).abs().doubleValue(),
                grid.imaginaryAt(job.height() - 1).subtract(
                        primary.cImaginary(), grid.mathContext()).abs().doubleValue())
                + grid.imaginaryStep().doubleValue();
        MandelbrotBlaTable bla = MandelbrotBlaTable.create(
                primary.real(), primary.imaginary(), primary.lastBoundedIteration(),
                Math.nextUp(Math.hypot(maximumReal, maximumImaginary)), cancelled);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }
        return Optional.of(new PreciseSampler(
                job,
                grid.mathContext(),
                new ReferencePool(job, grid.mathContext(), primary, bla)));
    }

    /** Shared-reference arbitrary-coordinate sampler used by deep antialiasing. */
    public static final class PreciseSampler {
        private final RenderJob job;
        private final MathContext mathContext;
        private final ReferencePool referencePool;

        private PreciseSampler(
                RenderJob job,
                MathContext mathContext,
                ReferencePool referencePool
        ) {
            this.job = job;
            this.mathContext = mathContext;
            this.referencePool = referencePool;
        }

        /** Returns {@code null} only when the supplied generation is cancelled. */
        public FractalSample sample(
                BigDecimal cReal,
                BigDecimal cImaginary,
                BooleanSupplier cancelled
        ) {
            Objects.requireNonNull(cReal);
            Objects.requireNonNull(cImaginary);
            Objects.requireNonNull(cancelled);
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return null;
            }

            ReferenceOrbit primary = referencePool.primary();
            double deltaReal = cReal.subtract(primary.cReal(), mathContext).doubleValue();
            double deltaImaginary = cImaginary.subtract(
                    primary.cImaginary(), mathContext).doubleValue();
            double real = primary.cRealAsDouble() + deltaReal;
            double imaginary = primary.cImaginaryAsDouble() + deltaImaginary;
            if (isSafelyInsideKnownInterior(real, imaginary)) {
                return new FractalSample(job.maxIterations(), false, 0.0, 0.0);
            }

            PerturbationResult result = referencePool.calculate(
                    cReal, cImaginary, deltaReal, deltaImaginary,
                    job.maxIterations(), cancelled);
            if (result.reliable()) {
                return result.sample();
            }
            return calculateHighPrecision(
                    cReal, cImaginary, mathContext, job.maxIterations(), cancelled);
        }
    }

    /** Shared bounded set of references used to recover perturbation glitches. */
    private static final class ReferencePool {
        private final RenderJob job;
        private final MathContext mathContext;
        private final java.util.concurrent.CopyOnWriteArrayList<ReferenceOrbit> references =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger reservedAdditionalReferences = new AtomicInteger();
        private final LongAdder additionalReferenceNanos = new LongAdder();
        private final LongAdder modifiedRebases = new LongAdder();
        private final LongAdder blaSteps = new LongAdder();
        private final LongAdder blaSkippedIterations = new LongAdder();
        private final MandelbrotBlaTable primaryBla;

        private ReferencePool(
                RenderJob job,
                MathContext mathContext,
                ReferenceOrbit primary,
                MandelbrotBlaTable primaryBla
        ) {
            this.job = job;
            this.mathContext = mathContext;
            this.primaryBla = primaryBla;
            references.add(primary);
        }

        ReferenceOrbit primary() {
            return references.getFirst();
        }

        PerturbationResult calculate(
                BigDecimal cReal,
                BigDecimal cImaginary,
                double primaryDeltaReal,
                double primaryDeltaImaginary,
                int maxIterations,
                BooleanSupplier cancelled
        ) {
            int executed = 0;
            int modifiedRebases = 0;
            PerturbationResult primaryResult = perturb(
                    primary(), primaryDeltaReal, primaryDeltaImaginary, maxIterations,
                    primaryBla, cancelled);
            executed += primaryResult.executedIterations();
            modifiedRebases += primaryResult.modifiedRebaseCount();
            if (primaryResult.reliable()) {
                return primaryResult;
            }

            for (ReferenceCandidate nearest : orderedAdditionalReferences(cReal, cImaginary)) {
                PerturbationResult result = perturb(
                        nearest.reference(), nearest.deltaReal(),
                        nearest.deltaImaginary(), maxIterations, null, cancelled);
                executed += result.executedIterations();
                modifiedRebases += result.modifiedRebaseCount();
                if (result.reliable()) {
                    return new PerturbationResult(
                            result.sample(), executed, true, modifiedRebases,
                            primaryResult.blaStepCount(), primaryResult.blaSkippedIterationCount());
                }
            }

            if (cancelled.getAsBoolean() || !reserveAdditionalReference()) {
                return new PerturbationResult(null, executed, false, modifiedRebases,
                        primaryResult.blaStepCount(), primaryResult.blaSkippedIterationCount());
            }

            /* Do not serialize high-precision orbit construction across workers. */
            long started = System.nanoTime();
            ReferenceOrbit additional = ReferenceOrbit.create(
                    job, cReal, cImaginary, mathContext, cancelled);
            additionalReferenceNanos.add(System.nanoTime() - started);
            if (additional == null) {
                return new PerturbationResult(null, executed, false, modifiedRebases,
                        primaryResult.blaStepCount(), primaryResult.blaSkippedIterationCount());
            }
            references.add(additional);
            PerturbationResult result = perturb(
                    additional, 0.0, 0.0, maxIterations, null, cancelled);
            return new PerturbationResult(
                    result.sample(), executed + result.executedIterations(),
                    result.reliable(), modifiedRebases + result.modifiedRebaseCount(),
                    primaryResult.blaStepCount(), primaryResult.blaSkippedIterationCount());
        }

        private boolean reserveAdditionalReference() {
            while (true) {
                int reserved = reservedAdditionalReferences.get();
                if (reserved >= MAX_ADDITIONAL_REFERENCES) {
                    return false;
                }
                if (reservedAdditionalReferences.compareAndSet(reserved, reserved + 1)) {
                    return true;
                }
            }
        }

        private List<ReferenceCandidate> orderedAdditionalReferences(
                BigDecimal cReal,
                BigDecimal cImaginary
        ) {
            List<ReferenceCandidate> candidates = new ArrayList<>(
                    Math.max(0, references.size() - 1));
            for (int index = 1; index < references.size(); index++) {
                ReferenceOrbit reference = references.get(index);
                double deltaReal = cReal.subtract(
                        reference.cReal(), mathContext).doubleValue();
                double deltaImaginary = cImaginary.subtract(
                        reference.cImaginary(), mathContext).doubleValue();
                double distance = deltaReal * deltaReal + deltaImaginary * deltaImaginary;
                candidates.add(new ReferenceCandidate(
                        reference, deltaReal, deltaImaginary, distance));
            }
            candidates.sort(Comparator.comparingDouble(ReferenceCandidate::distanceSquared));
            return candidates;
        }

        private record ReferenceCandidate(
                ReferenceOrbit reference,
                double deltaReal,
                double deltaImaginary,
                double distanceSquared
        ) {}

        int additionalReferenceCount() {
            return references.size() - 1;
        }

        void recordWork(PerturbationResult result) {
            modifiedRebases.add(result.modifiedRebaseCount());
            blaSteps.add(result.blaStepCount());
            blaSkippedIterations.add(result.blaSkippedIterationCount());
        }

        long modifiedRebaseCount() {
            return modifiedRebases.sum();
        }

        long blaStepCount() { return blaSteps.sum(); }

        long blaSkippedIterationCount() { return blaSkippedIterations.sum(); }

        double additionalReferenceOrbitMs() {
            return additionalReferenceNanos.sum() / 1_000_000.0;
        }
    }

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

    static List<RenderRegion> orderedTiles(RenderJob job) {
        List<RenderRegion> tiles = new ArrayList<>();
        for (int y = 0; y < job.height(); y += TILE_SIZE) {
            for (int x = 0; x < job.width(); x += TILE_SIZE) {
                tiles.add(new RenderRegion(x, y, Math.min(TILE_SIZE, job.width() - x),
                        Math.min(TILE_SIZE, job.height() - y)));
            }
        }
        double priorityX = job.width() * job.priority().x();
        double priorityY = job.height() * job.priority().y();
        Comparator<RenderRegion> byPriority = Comparator.comparingDouble(
                tile -> distanceSquared(tile, priorityX, priorityY));
        job.approximateCoverage().ifPresentOrElse(
                coverage -> tiles.sort((first, second) -> compareForZoomOut(
                        first, second, coverage, priorityX, priorityY)),
                () -> tiles.sort(byPriority));
        return tiles;
    }

    private static int compareForZoomOut(
            RenderRegion first,
            RenderRegion second,
            RenderRegion coverage,
            double priorityX,
            double priorityY
    ) {
        boolean firstExposed = uncoveredArea(first, coverage) > 0;
        boolean secondExposed = uncoveredArea(second, coverage) > 0;
        if (firstExposed != secondExposed) {
            return firstExposed ? -1 : 1;
        }
        if (firstExposed) {
            int byPreviewEdge = Double.compare(
                    distanceToCoverageSquared(first, coverage),
                    distanceToCoverageSquared(second, coverage));
            if (byPreviewEdge != 0) {
                return byPreviewEdge;
            }
        }
        return Double.compare(
                distanceSquared(first, priorityX, priorityY),
                distanceSquared(second, priorityX, priorityY));
    }

    private static int uncoveredArea(RenderRegion tile, RenderRegion coverage) {
        int overlapWidth = Math.max(0, Math.min(tile.x() + tile.width(), coverage.x() + coverage.width())
                - Math.max(tile.x(), coverage.x()));
        int overlapHeight = Math.max(0, Math.min(tile.y() + tile.height(), coverage.y() + coverage.height())
                - Math.max(tile.y(), coverage.y()));
        return tile.width() * tile.height() - overlapWidth * overlapHeight;
    }

    private static double distanceToCoverageSquared(RenderRegion tile, RenderRegion coverage) {
        int coverageRight = coverage.x() + coverage.width();
        int coverageBottom = coverage.y() + coverage.height();
        int dx = Math.max(0, Math.max(coverage.x() - (tile.x() + tile.width()), tile.x() - coverageRight));
        int dy = Math.max(0, Math.max(coverage.y() - (tile.y() + tile.height()), tile.y() - coverageBottom));
        return (double) dx * dx + (double) dy * dy;
    }

    private static double distanceSquared(RenderRegion tile, double priorityX, double priorityY) {
        double dx = tile.x() + tile.width() / 2.0 - priorityX;
        double dy = tile.y() + tile.height() / 2.0 - priorityY;
        return dx * dx + dy * dy;
    }

    @Override
    public void close() {
        workers.shutdownNow();
        referenceCache.clear();
    }

    int cachedReferenceCount() { return referenceCache.size(); }

    long cachedReferenceBytes() { return referenceCache.retainedBytes(); }

    /** High-precision centre orbit retained once for all tiles in one frame. */
    private record ReferenceOrbit(
            BigDecimal cReal,
            BigDecimal cImaginary,
            double[] real,
            double[] imaginary,
            double[] glitchThresholds,
            ScaledValue[] escapeMargins,
            int lastValidIteration
    ) {
        static ReferenceOrbit create(
                RenderJob job,
                PreciseRenderGrid grid,
                BooleanSupplier cancelled
        ) {
            int centerX = (job.width() - 1) / 2;
            int centerY = (job.height() - 1) / 2;
            return create(
                    job,
                    grid.realAt(centerX),
                    grid.imaginaryAt(centerY),
                    grid.mathContext(),
                    cancelled);
        }

        static ReferenceOrbit create(
                RenderJob job,
                BigDecimal cReal,
                BigDecimal cImaginary,
                MathContext context,
                BooleanSupplier cancelled
        ) {
            double[] real = new double[job.maxIterations() + 1];
            double[] imaginary = new double[job.maxIterations() + 1];
            double[] glitchThresholds = new double[job.maxIterations() + 1];
            ScaledValue[] escapeMargins = new ScaledValue[job.maxIterations() + 1];
            escapeMargins[0] = ScaledValue.fromDouble(-4.0);
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
                double referenceReal = real[iteration + 1];
                double referenceImaginary = imaginary[iteration + 1];
                double referenceMagnitudeSquared = referenceReal * referenceReal
                        + referenceImaginary * referenceImaginary;
                glitchThresholds[iteration + 1] = GLITCH_RELATIVE_THRESHOLD
                        * referenceMagnitudeSquared;
                BigDecimal magnitudeSquared = zr.multiply(zr, context)
                        .add(zi.multiply(zi, context), context);
                escapeMargins[iteration + 1] = ScaledValue.fromBigDecimal(
                        magnitudeSquared.subtract(FOUR, context));
                lastValidIteration = iteration + 1;
                if (magnitudeSquared.compareTo(FOUR) > 0) {
                    break;
                }
            }
            return new ReferenceOrbit(
                    cReal, cImaginary, real, imaginary,
                    glitchThresholds, escapeMargins, lastValidIteration);
        }

        double realAt(int iteration) { return real[iteration]; }
        double imaginaryAt(int iteration) { return imaginary[iteration]; }
        ScaledValue escapeMarginAt(int iteration) { return escapeMargins[iteration]; }
        int lastBoundedIteration() {
            return escapeMargins[lastValidIteration].mantissa() > 0.0
                    ? lastValidIteration - 1 : lastValidIteration;
        }
        double cRealAsDouble() { return cReal.doubleValue(); }
        double cImaginaryAsDouble() { return cImaginary.doubleValue(); }
        long retainedBytes() {
            return ((long) real.length + imaginary.length + glitchThresholds.length)
                    * Double.BYTES
                    + (long) escapeMargins.length * (Double.BYTES + Long.BYTES);
        }
    }

    /**
     * Backend-owned LRU cache. Entries are shared only when centre coordinate,
     * precision, and iteration limit match exactly; cancelled work is never retained.
     */
    private static final class ReferenceOrbitCache {
        private final long capacityBytes;
        private final LinkedHashMap<ReferenceOrbitKey, ReferenceOrbit> entries =
                new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;

        private ReferenceOrbitCache(long capacityBytes) { this.capacityBytes = capacityBytes; }

        ReferenceOrbit acquire(
                RenderJob job, PreciseRenderGrid grid, BooleanSupplier cancelled
        ) {
            ReferenceOrbitKey key = ReferenceOrbitKey.forJob(job, grid);
            synchronized (this) {
                ReferenceOrbit cached = entries.get(key);
                if (cached != null) {
                    return cached;
                }
            }

            /* Orbit construction is cancellable and must never block another generation's lookup. */
            ReferenceOrbit created = ReferenceOrbit.create(job, grid, cancelled);
            if (created == null || cancelled.getAsBoolean()) {
                return created;
            }

            synchronized (this) {
                ReferenceOrbit cached = entries.get(key);
                if (cached != null) {
                    return cached;
                }
                long entryBytes = created.retainedBytes();
                if (entryBytes > capacityBytes) {
                    return created;
                }
                while (bytes + entryBytes > capacityBytes && !entries.isEmpty()) {
                    Map.Entry<ReferenceOrbitKey, ReferenceOrbit> eldest =
                            entries.entrySet().iterator().next();
                    bytes -= eldest.getValue().retainedBytes();
                    entries.remove(eldest.getKey());
                }
                entries.put(key, created);
                bytes += entryBytes;
            }
            return created;
        }

        synchronized int size() { return entries.size(); }
        synchronized long retainedBytes() { return bytes; }
        synchronized void clear() { entries.clear(); bytes = 0L; }
    }

    private record ReferenceOrbitKey(
            BigDecimal cReal, BigDecimal cImaginary, MathContext context, int maxIterations
    ) {
        static ReferenceOrbitKey forJob(RenderJob job, PreciseRenderGrid grid) {
            int centerX = (job.width() - 1) / 2;
            int centerY = (job.height() - 1) / 2;
            return new ReferenceOrbitKey(grid.realAt(centerX), grid.imaginaryAt(centerY),
                    grid.mathContext(), job.maxIterations());
        }
    }
}
