package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;

import java.math.BigDecimal;
import java.math.BigInteger;
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
    private static final double UNIT_ROUNDOFF = 0x1p-53;
    /* Generous multiple of the unit roundoff for the few operations of one perturbation step. */
    private static final double STATE_ROUNDING = 16.0 * UNIT_ROUNDOFF;
    /* Relative rounding of |z|^2 computed from its components. */
    private static final double ESCAPE_DECISION_ROUNDING = 4.0 * UNIT_ROUNDOFF;
    /*
     * The worst-case state error grows with the orbit derivative, so on long chaotic orbits it
     * soon exceeds the state itself although perturbation still matches exact iteration. Beyond
     * this relative size it no longer separates decisions and the glitch criterion governs.
     */
    private static final double INFORMATIVE_STATE_ERROR = 1e-12;
    /* Power of two so polling in the hot loop uses a cheap mask. */
    private static final int CANCELLATION_CHECK_INTERVAL = 32;
    private static final int RECOVERY_REFERENCE_CACHE_CAPACITY = 64;
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
                frame.job(), grid, reference, bla);

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

    /**
     * @param onReference whether the point is exactly the reference, judged from exact
     *         coordinates: a rounded offset of zero does not prove it
     */
    private static PerturbationResult perturb(
            ReferenceOrbit reference, double deltaCReal, double deltaCImaginary, boolean onReference,
            int maxIterations, MandelbrotBlaTable bla,
            BooleanSupplier cancelled
    ) {
        return bla == null || !bla.supportsDelta(deltaCReal, deltaCImaginary)
                ? perturbScalar(reference, deltaCReal, deltaCImaginary, onReference, maxIterations, cancelled)
                : perturbWithBla(reference, deltaCReal, deltaCImaginary, maxIterations, bla, cancelled);
    }

    /**
     * Scalar perturbation from a recovery reference without BLA, unreliable when the offset
     * from that reference does not keep pixel resolution as a double.
     */
    private static PerturbationResult perturbFromRecovery(
            ReferenceOrbit reference, BigDecimal cReal, BigDecimal cImaginary,
            BigDecimal realStep, BigDecimal imaginaryStep, MathContext mathContext,
            int maxIterations, BooleanSupplier cancelled
    ) {
        BigDecimal exactDeltaReal = cReal.subtract(reference.cReal(), mathContext);
        BigDecimal exactDeltaImaginary = cImaginary.subtract(reference.cImaginary(), mathContext);
        double deltaReal = exactDeltaReal.doubleValue();
        double deltaImaginary = exactDeltaImaginary.doubleValue();
        if (CoordinateDeltas.losesPixelResolution(exactDeltaReal, deltaReal, realStep)
                || CoordinateDeltas.losesPixelResolution(
                        exactDeltaImaginary, deltaImaginary, imaginaryStep)) {
            return new PerturbationResult(null, 0, false, 0, 0, 0);
        }
        return perturbScalar(reference, deltaReal, deltaImaginary,
                exactDeltaReal.signum() == 0 && exactDeltaImaginary.signum() == 0,
                maxIterations, cancelled);
    }

    /**
     * Scalar perturbation with a running bound on the absolute error of {@code z}. While that
     * bound is informative, an escape decision it cannot settle, with {@code |z|} within that
     * error of the escape radius, is unreliable, so the caller recovers or iterates the pixel
     * exactly instead of guessing. A point exactly on its reference, as the caller establishes
     * from exact coordinates, uses the reference's exact escape margin instead.
     */
    private static PerturbationResult perturbScalar(
            ReferenceOrbit reference, double deltaCReal, double deltaCImaginary, boolean onReference,
            int maxIterations, BooleanSupplier cancelled
    ) {
        double[] referenceReals = reference.real();
        double[] referenceImaginaries = reference.imaginary();
        double[] glitchThresholds = reference.glitchThresholds();
        int referenceLimit = reference.lastValidIteration();
        double deltaCMagnitude = Math.abs(deltaCReal) + Math.abs(deltaCImaginary);
        double deltaReal = 0.0;
        double deltaImaginary = 0.0;
        double zr = 0.0;
        double zi = 0.0;
        double absoluteState = 0.0;
        // Bounds on the absolute error of the delta and of z, which also covers reference rounding,
        // tracked only while they stay informative.
        boolean errorBounded = true;
        double deltaError = 0.0;
        double stateError = 0.0;
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
            if (errorBounded) {
                // An error e in delta becomes (2 z + e) e; rounding of the stored reference and
                // of this step adds a few units of the operand magnitudes.
                double referenceAbs = Math.abs(referenceReal) + Math.abs(referenceImaginary);
                double deltaAbs = Math.abs(deltaReal) + Math.abs(deltaImaginary);
                deltaError = (2.0 * (absoluteState + stateError) + deltaError) * deltaError
                        + STATE_ROUNDING * ((2.0 * referenceAbs + deltaAbs) * deltaAbs + deltaCMagnitude);
            }
            double nextDeltaReal = 2.0 * (referenceReal * deltaReal - referenceImaginary * deltaImaginary)
                    + deltaReal * deltaReal - deltaImaginary * deltaImaginary + deltaCReal;
            double nextDeltaImaginary = 2.0 * (referenceReal * deltaImaginary + referenceImaginary * deltaReal)
                    + 2.0 * deltaReal * deltaImaginary + deltaCImaginary;
            deltaReal = nextDeltaReal;
            deltaImaginary = nextDeltaImaginary;
            iteration++;
            referenceIteration++;
            double nextReferenceReal = referenceReals[referenceIteration];
            double nextReferenceImaginary = referenceImaginaries[referenceIteration];
            zr = nextReferenceReal + deltaReal;
            zi = nextReferenceImaginary + deltaImaginary;
            double magnitudeSquared = zr * zr + zi * zi;
            if (!Double.isFinite(magnitudeSquared)) {
                return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
            }
            boolean escaped = magnitudeSquared > 4.0;
            if (errorBounded) {
                // |zr| + |zi| bounds |z| without a square root; each component carries the stored
                // reference rounding and the rounding of this sum.
                absoluteState = Math.abs(zr) + Math.abs(zi);
                stateError = deltaError + UNIT_ROUNDOFF * (Math.abs(nextReferenceReal)
                        + Math.abs(nextReferenceImaginary) + absoluteState);
                errorBounded = stateError <= INFORMATIVE_STATE_ERROR * Math.max(1.0, absoluteState);
            }
            // With | |z| - 2 | <= t, |z|^2 - 4 = (|z| - 2)(|z| + 2) stays within 5 t for t < 1.
            if (errorBounded && Math.abs(magnitudeSquared - 4.0)
                    <= 5.0 * stateError + ESCAPE_DECISION_ROUNDING * magnitudeSquared) {
                if (!onReference) {
                    return new PerturbationResult(null, iteration, false, modifiedRebases, 0, 0);
                }
                // z is exactly the reference, whose escape margin was calculated exactly.
                escaped = reference.escapeMarginAt(referenceIteration).mantissa() > 0.0;
            }
            if (escaped) {
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
                deltaError = stateError;
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

    /**
     * Perturbation recurrence with a separate binary exponent for sub-double deltas. Like the
     * scalar loop it bounds the error of the delta while that bound is informative, and an
     * escape decision within it is unreliable, so the caller iterates the point exactly.
     */
    private static PerturbationResult perturbScaled(
            ReferenceOrbit reference,
            ScaledComplex deltaC,
            int maxIterations,
            BooleanSupplier cancelled
    ) {
        return perturbScaled(reference, deltaC, maxIterations, cancelled, null);
    }

    /**
     * @param errorBoundLoss when not null, receives the iteration at which the error bound
     *         stopped being tracked and the absolute error it then bounded, or stays untouched
     */
    private static PerturbationResult perturbScaled(
            ReferenceOrbit reference,
            ScaledComplex deltaC,
            int maxIterations,
            BooleanSupplier cancelled,
            double[] errorBoundLoss
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
            delta.limitError(zr, zi);
            if (errorBoundLoss != null && Double.isNaN(errorBoundLoss[0]) && !delta.errorBounded()) {
                errorBoundLoss[0] = iteration;
                errorBoundLoss[1] = delta.absoluteError();
            }
            int escape = delta.escapeDecision(reference, iteration);
            if (escape == UNCERTAIN_ESCAPE) {
                return new PerturbationResult(null, iteration, false, 0, 0, 0);
            }
            if (escape == ESCAPED) {
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

        /** Rounds correctly, so the mantissa is within half a unit in the last place. */
        static ScaledValue fromBigDecimal(BigDecimal value) {
            if (value.signum() == 0) {
                return ZERO;
            }
            double approximate = value.doubleValue();
            if (Double.isFinite(approximate) && Math.abs(approximate) >= Double.MIN_NORMAL) {
                return fromDouble(approximate);
            }
            // Scale by an exact power of two into the normal range, then round once.
            int decimalExponent = value.precision() - value.scale() - 1;
            int binaryExponent = Math.toIntExact((long) Math.floor(decimalExponent * LOG2_10));
            BigDecimal scaled = binaryExponent < 0
                    ? value.multiply(new BigDecimal(BigInteger.ONE.shiftLeft(-binaryExponent)))
                    : value.multiply(new BigDecimal(BigInteger.valueOf(5).pow(binaryExponent)))
                            .movePointLeft(binaryExponent);
            ScaledValue normalized = fromDouble(scaled.doubleValue());
            return new ScaledValue(normalized.mantissa(), normalized.exponent() + binaryExponent);
        }

        boolean isZero() {
            return mantissa == 0.0;
        }
    }

    private static final int NOT_ESCAPED = 0;
    private static final int ESCAPED = 1;
    private static final int UNCERTAIN_ESCAPE = 2;
    private static final int ERROR_EXPONENT_STEP = 256;
    private static final double ERROR_MANTISSA_LIMIT = 0x1p256;

    /** Mutable complex value whose components share one binary exponent. */
    private static final class ScaledComplex {
        private double real;
        private double imaginary;
        private long exponent;
        // Bound r = errorMantissa * 2^errorExponent on the error of the value relative to
        // |real| + |imaginary|, so it needs no rescaling with the value's exponent. On long
        // orbits r can exceed the double range while the absolute error stays tiny, so the
        // mantissa is rescaled by 2^256 steps. Tracked only while informative for z.
        private double errorMantissa;
        private long errorExponent;
        // 16u * 2^-errorExponent and 2 * 2^-errorExponent, the constants of the bound in units
        // of 2^errorExponent.
        private double scaledRounding = STATE_ROUNDING;
        private double scaledTwo = 2.0;
        private boolean errorBounded = true;

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
            double alignedLinearReal = align(linearReal, linearExponent, commonExponent);
            double alignedLinearImaginary = align(linearImaginary, linearExponent, commonExponent);
            double alignedQuadraticReal = align(quadraticReal, quadraticExponent, commonExponent);
            double alignedQuadraticImaginary = align(quadraticImaginary, quadraticExponent, commonExponent);
            double alignedDeltaCReal = align(deltaC.real, deltaC.exponent, commonExponent);
            double alignedDeltaCImaginary = align(deltaC.imaginary, deltaC.exponent, commonExponent);
            real = alignedLinearReal + alignedQuadraticReal + alignedDeltaCReal;
            imaginary = alignedLinearImaginary + alignedQuadraticImaginary + alignedDeltaCImaginary;
            if (errorBounded) {
                // With D = |delta| in L1 and relative error r, an error r D becomes
                // 2 |Z| (r + 16u) D + ((2 + r) r + 16u) D^2 plus deltaC rounding. The products
                // 2 Z delta and delta^2 cannot cancel in magnitude, so 2 |Z| D and D^2 are at most
                // twice the L1 norms of their already aligned values. In units of 2^errorExponent
                // with r = m 2^k: (r + 16u) = 2^k (m + 16u 2^-k) and
                // (2 + r) r + 16u = 2^k ((2 2^-k + m) m 2^k + 16u 2^-k).
                double m = errorMantissa;
                double linearAbs = Math.abs(alignedLinearReal) + Math.abs(alignedLinearImaginary);
                double quadraticAbs = Math.abs(alignedQuadraticReal) + Math.abs(alignedQuadraticImaginary);
                double squared = (scaledTwo + m) * (m * quadraticAbs);
                if (errorExponent != 0L) {
                    squared = scale(squared, errorExponent);
                }
                double error = 2.0 * (m + scaledRounding) * linearAbs
                        + 2.0 * (squared + scaledRounding * quadraticAbs)
                        + scaledRounding * (Math.abs(alignedDeltaCReal) + Math.abs(alignedDeltaCImaginary));
                double magnitude = Math.abs(real) + Math.abs(imaginary);
                errorMantissa = error == 0.0 ? 0.0 : error / magnitude;
                if (!(errorMantissa <= ERROR_MANTISSA_LIMIT)
                        || (errorExponent != 0L && errorMantissa < 1.0 / ERROR_MANTISSA_LIMIT)) {
                    rescaleError();
                }
            }
            exponent = commonExponent;
            normalize();
        }

        private void rescaleError() {
            if (!(errorMantissa <= Double.MAX_VALUE)) {
                // Overflow of a bound kept below 2^256 needs an absolute error far above the
                // informative limit, or a cancelled delta of zero.
                errorBounded = false;
                return;
            }
            // Whole steps of 2^256 bring the mantissa near 1, and back down once the bound
            // shrinks, so the rounding constants keep their weight; the exponent stays >= 0.
            long step = Math.max(-errorExponent,
                    Math.floorDiv(Math.getExponent(errorMantissa), ERROR_EXPONENT_STEP) * ERROR_EXPONENT_STEP);
            errorMantissa = Math.scalb(errorMantissa, (int) -step);
            errorExponent += step;
            scaledRounding = scale(STATE_ROUNDING, -errorExponent);
            scaledTwo = scale(2.0, -errorExponent);
        }

        /**
         * Stops tracking the error once its absolute size, from the delta and z as doubles,
         * exceeds the informative limit.
         */
        void limitError(double zr, double zi) {
            if (!errorBounded) {
                return;
            }
            // The absolute error is m D 2^(k + exponent); below 2^-42 it is informative for any z.
            double relativeMagnitude = errorMantissa * (Math.abs(real) + Math.abs(imaginary));
            long absoluteExponent = errorExponent + exponent + Math.getExponent(relativeMagnitude);
            if (absoluteExponent >= -42L && scale(relativeMagnitude, errorExponent + exponent)
                    > INFORMATIVE_STATE_ERROR * Math.max(1.0, Math.abs(zr) + Math.abs(zi))) {
                errorBounded = false;
            }
        }

        /** Whether the error bound is still tracked, for tests. */
        boolean errorBounded() {
            return errorBounded;
        }

        /** The absolute error bound as a double, for tests. */
        double absoluteError() {
            return scale(errorMantissa * (Math.abs(real) + Math.abs(imaginary)), errorExponent + exponent);
        }

        /**
         * Decides {@code |Z + delta|^2 > 4} from the exact reference margin, or reports
         * {@link #UNCERTAIN_ESCAPE} when the bounded error of the delta and of the rounding
         * here can change the sign.
         */
        int escapeDecision(ReferenceOrbit reference, int iteration) {
            ScaledValue referenceMargin = reference.escapeMarginAt(iteration);
            double referenceReal = reference.realAt(iteration);
            double referenceImaginary = reference.imaginaryAt(iteration);
            double linear = 2.0 * (referenceReal * real + referenceImaginary * imaginary);
            double quadratic = real * real + imaginary * imaginary;
            long quadraticExponent = exponent * 2L;
            long commonExponent = referenceMargin.isZero() ? Long.MIN_VALUE
                    : referenceMargin.exponent();
            // A bounded error of the linear term lives at the delta's exponent even when that
            // term cancels to zero.
            if (linear != 0.0 || (errorBounded && quadratic != 0.0)) {
                commonExponent = Math.max(commonExponent, exponent);
            }
            if (quadratic != 0.0) {
                commonExponent = Math.max(commonExponent, quadraticExponent);
            }
            if (commonExponent == Long.MIN_VALUE) {
                return NOT_ESCAPED;
            }
            double margin = align(referenceMargin.mantissa(), referenceMargin.exponent(), commonExponent);
            double linearTerm = align(linear, exponent, commonExponent);
            double quadraticTerm = align(quadratic, quadraticExponent, commonExponent);
            double sum = margin + linearTerm + quadraticTerm;
            if (errorBounded) {
                // Same bounds as in advance: the linear term can cancel, so it takes 2 |Z| D
                // explicitly, while D^2 is at most twice |delta|^2.
                // With r = m 2^k, as in advance.
                double m = errorMantissa;
                double referenceAbs = Math.abs(referenceReal) + Math.abs(referenceImaginary);
                double deltaAbs = Math.abs(real) + Math.abs(imaginary);
                double quadraticAbs = Math.abs(quadraticTerm);
                double squared = 2.0 * (scaledTwo + m) * (m * quadraticAbs);
                if (errorExponent != 0L) {
                    squared = scale(squared, 2L * errorExponent);
                }
                double uncertainty = STATE_ROUNDING * Math.abs(margin)
                        + (m + scaledRounding) * align(2.0 * referenceAbs * deltaAbs,
                                exponent + errorExponent, commonExponent)
                        + squared + 2.0 * STATE_ROUNDING * quadraticAbs
                        + ESCAPE_DECISION_ROUNDING
                                * (Math.abs(margin) + Math.abs(linearTerm) + Math.abs(quadraticTerm));
                if (Math.abs(sum) <= uncertainty) {
                    return UNCERTAIN_ESCAPE;
                }
            }
            return sum > 0.0 ? ESCAPED : NOT_ESCAPED;
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

        /** {@code value * 2^shift} for any finite value, saturating to zero or infinity. */
        private static double scale(double value, long shift) {
            return Math.scalb(value, (int) Math.max(-4096L, Math.min(4096L, shift)));
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
        MathContext context = grid.mathContext();
        return Optional.of(new PreciseSampler(job, grid, context, primary, bla,
                new RecoveryReferenceLattice<>(primary.cReal(), primary.cImaginary(),
                        grid.realStep(), grid.imaginaryStep(), context, RECOVERY_REFERENCE_CACHE_CAPACITY,
                        (real, imaginary, stop) -> real.compareTo(primary.cReal()) == 0
                                && imaginary.compareTo(primary.cImaginary()) == 0
                                ? primary : ReferenceOrbit.create(job, real, imaginary, context, stop))));
    }

    /**
     * Runs the scaled-delta loop for one pixel from the primary reference and returns the
     * iteration at which its error bound stopped being tracked and the absolute error it then
     * bounded, both NaN when it stayed tracked, for tests.
     */
    static double[] scaledErrorBoundLoss(RenderJob job, int x, int y) {
        PreciseRenderGrid grid = job.preciseGrid();
        ReferenceOrbit primary = ReferenceOrbit.create(job, grid, () -> false);
        ScaledComplex deltaC = ScaledComplex.from(
                ScaledValue.fromBigDecimal(grid.realAt(x).subtract(primary.cReal(), grid.mathContext())),
                ScaledValue.fromBigDecimal(grid.imaginaryAt(y).subtract(primary.cImaginary(), grid.mathContext())));
        double[] loss = {Double.NaN, Double.NaN};
        perturbScaled(primary, deltaC, job.maxIterations(), () -> false, loss);
        return loss;
    }

    /**
     * Arbitrary-coordinate sampler used by deep antialiasing. A sample the primary reference
     * cannot resolve uses the recovery reference of its lattice point, then exact iteration,
     * so every sample is a function of its coordinate however workers interleave.
     */
    public static final class PreciseSampler {
        private final RenderJob job;
        private final MathContext mathContext;
        private final ReferenceOrbit primary;
        private final MandelbrotBlaTable primaryBla;
        private final RecoveryReferenceLattice<ReferenceOrbit> recovery;
        private final BigDecimal realStep;
        private final BigDecimal imaginaryStep;

        private PreciseSampler(
                RenderJob job,
                PreciseRenderGrid grid,
                MathContext mathContext,
                ReferenceOrbit primary,
                MandelbrotBlaTable primaryBla,
                RecoveryReferenceLattice<ReferenceOrbit> recovery
        ) {
            this.job = job;
            this.realStep = grid.realStep();
            this.imaginaryStep = grid.imaginaryStep();
            this.mathContext = mathContext;
            this.primary = primary;
            this.primaryBla = primaryBla;
            this.recovery = recovery;
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

            BigDecimal exactDeltaReal = cReal.subtract(primary.cReal(), mathContext);
            BigDecimal exactDeltaImaginary = cImaginary.subtract(primary.cImaginary(), mathContext);
            double deltaReal = exactDeltaReal.doubleValue();
            double deltaImaginary = exactDeltaImaginary.doubleValue();
            if (CoordinateDeltas.losesPixelResolution(exactDeltaReal, deltaReal, realStep)
                    || CoordinateDeltas.losesPixelResolution(
                            exactDeltaImaginary, deltaImaginary, imaginaryStep)) {
                // A double offset would move the sample, possibly onto the reference.
                PerturbationResult result = perturbScaled(primary, ScaledComplex.from(
                                ScaledValue.fromBigDecimal(exactDeltaReal),
                                ScaledValue.fromBigDecimal(exactDeltaImaginary)),
                        job.maxIterations(), cancelled);
                return result.reliable() ? result.sample() : calculateHighPrecision(
                        cReal, cImaginary, mathContext, job.maxIterations(), cancelled);
            }
            double real = primary.cRealAsDouble() + deltaReal;
            double imaginary = primary.cImaginaryAsDouble() + deltaImaginary;
            if (isSafelyInsideKnownInterior(real, imaginary)) {
                return new FractalSample(job.maxIterations(), false, 0.0, 0.0);
            }

            PerturbationResult result = perturb(primary, deltaReal, deltaImaginary,
                    exactDeltaReal.signum() == 0 && exactDeltaImaginary.signum() == 0,
                    job.maxIterations(), primaryBla, cancelled);
            if (result.reliable()) {
                return result.sample();
            }
            ReferenceOrbit reference = recovery.reference(deltaReal, deltaImaginary, cancelled);
            if (reference != null) {
                result = perturbFromRecovery(reference, cReal, cImaginary, realStep, imaginaryStep,
                        mathContext, job.maxIterations(), cancelled);
                if (result.reliable()) {
                    return result.sample();
                }
            }
            return calculateHighPrecision(
                    cReal, cImaginary, mathContext, job.maxIterations(), cancelled);
        }

        /** Recovery references built so far, for tests. */
        long recoveryReferenceBuildCount() {
            return recovery.buildCount();
        }
    }

    /**
     * Primary reference plus the recovery references of a fixed lattice, shared by the workers
     * of one generation. A glitched pixel uses the recovery reference of its lattice point, so its
     * sample is a function of its coordinate, never of which tiles were calculated first.
     */
    private static final class ReferencePool {
        private final ReferenceOrbit primary;
        private final RecoveryReferenceLattice<ReferenceOrbit> recovery;
        private final MathContext mathContext;
        private final LongAdder additionalReferences = new LongAdder();
        private final LongAdder additionalReferenceNanos = new LongAdder();
        private final LongAdder modifiedRebases = new LongAdder();
        private final LongAdder blaSteps = new LongAdder();
        private final LongAdder blaSkippedIterations = new LongAdder();
        private final MandelbrotBlaTable primaryBla;
        private final BigDecimal realStep;
        private final BigDecimal imaginaryStep;

        private ReferencePool(
                RenderJob job,
                PreciseRenderGrid grid,
                ReferenceOrbit primary,
                MandelbrotBlaTable primaryBla
        ) {
            this.primary = primary;
            this.mathContext = grid.mathContext();
            this.primaryBla = primaryBla;
            this.realStep = grid.realStep();
            this.imaginaryStep = grid.imaginaryStep();
            this.recovery = new RecoveryReferenceLattice<>(primary.cReal(), primary.cImaginary(),
                    grid.realStep(), grid.imaginaryStep(), mathContext, RECOVERY_REFERENCE_CACHE_CAPACITY,
                    (real, imaginary, stop) -> {
                        // The origin lattice point is the primary reference: reuse its identical orbit.
                        if (real.compareTo(primary.cReal()) == 0 && imaginary.compareTo(primary.cImaginary()) == 0) {
                            return primary;
                        }
                        long started = System.nanoTime();
                        ReferenceOrbit reference = ReferenceOrbit.create(job, real, imaginary, mathContext, stop);
                        additionalReferenceNanos.add(System.nanoTime() - started);
                        if (reference != null) additionalReferences.increment();
                        return reference;
                    });
        }

        ReferenceOrbit primary() {
            return primary;
        }

        /**
         * Perturbs from the primary reference, then from the recovery reference of the pixel's
         * lattice point. An unreliable result leaves the pixel to exact iteration.
         */
        PerturbationResult calculate(
                BigDecimal cReal,
                BigDecimal cImaginary,
                double primaryDeltaReal,
                double primaryDeltaImaginary,
                int maxIterations,
                BooleanSupplier cancelled
        ) {
            // CoordinateDeltas sends an exact offset that rounds to zero to the scaled path,
            // so a zero offset here is exact.
            PerturbationResult primaryResult = perturb(
                    primary, primaryDeltaReal, primaryDeltaImaginary,
                    primaryDeltaReal == 0.0 && primaryDeltaImaginary == 0.0, maxIterations,
                    primaryBla, cancelled);
            if (primaryResult.reliable()) {
                return primaryResult;
            }
            int executed = primaryResult.executedIterations();
            int rebases = primaryResult.modifiedRebaseCount();
            ReferenceOrbit reference = recovery.reference(
                    primaryDeltaReal, primaryDeltaImaginary, cancelled);
            if (reference == null) {
                return new PerturbationResult(null, executed, false, rebases,
                        primaryResult.blaStepCount(), primaryResult.blaSkippedIterationCount());
            }
            PerturbationResult result = perturbFromRecovery(reference, cReal, cImaginary,
                    realStep, imaginaryStep, mathContext, maxIterations, cancelled);
            return new PerturbationResult(
                    result.sample(), executed + result.executedIterations(),
                    result.reliable(), rebases + result.modifiedRebaseCount(),
                    primaryResult.blaStepCount(), primaryResult.blaSkippedIterationCount());
        }

        /** Recovery orbits built in this generation, counting rebuilds after eviction. */
        int additionalReferenceCount() {
            return (int) Math.min(Integer.MAX_VALUE, additionalReferences.sum());
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
            // A zero keeps the scale of its operands, which would grow every iteration on the
            // real axis and make the exact sum below ever longer.
            if (zr.signum() == 0) zr = BigDecimal.ZERO;
            if (zi.signum() == 0) zi = BigDecimal.ZERO;
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
