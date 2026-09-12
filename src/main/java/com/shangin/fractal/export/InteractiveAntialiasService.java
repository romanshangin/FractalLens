package com.shangin.fractal.export;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.gpu.PaletteRecolorBackend;
import com.shangin.fractal.gpu.PaletteRecolorTiming;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.SamplePlane;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.RefinedPixelSnapshot;
import com.shangin.fractal.render.AntialiasSampleCache;
import com.shangin.fractal.render.PixelShift;
import com.shangin.fractal.render.BaseColorPhaseCache;
import com.shangin.fractal.render.PreciseFractalSampler;
import com.shangin.fractal.render.PreciseRenderGrid;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.scene.SamplingPattern;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Runs a cancellable, candidate-only antialiasing pass after the interactive
 * base frame is visible and publishes refined tiles progressively. Navigation
 * never waits for this refinement.
 */
public final class InteractiveAntialiasService implements AutoCloseable {

    private static final int SAMPLE_GRID = 4;
    private static final int DEEP_LOW_SAMPLE_GRID = 2;
    private static final int DEEP_HIGH_SAMPLE_GRID = 4;
    private static final double DEEP_COLOR_EDGE_THRESHOLD = 0.20;
    private static final int TILE_SIZE = 32;

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            daemonThreadFactory("fractal-aa")
    );
    private final ExecutorService workers;
    private final AtomicLong generation = new AtomicLong();
    private final AntialiasSampleCache sampleCache;
    private final boolean profiling;
    private RenderFrame cachedFrame;
    private SamplingPattern cachedSamplingPattern;
    private RenderFrame basePhaseFrame;
    private BaseColorPhaseCache basePhaseCache;
    private final ThreadLocal<SmoothColorLookup> recolorLookup =
            ThreadLocal.withInitial(SmoothColorLookup::new);
    private Future<?> currentRefinement;
    private volatile Profile lastProfile = Profile.EMPTY;

    /** Worker elapsed times are summed and overlap; they are not process CPU time. */
    public record Profile(long totalNanos, long baseColorNanos, long candidateNanos,
                          long candidateCriticalPathNanos, long samplingNanos,
                          long cacheColorNanos, long testedPixels, long candidates, long samples,
                          long cachePreparationNanos, long cacheMergeNanos, long cacheBatches) {
        public static final Profile EMPTY = new Profile(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public InteractiveAntialiasService() {
        this(new AntialiasSampleCache(), Boolean.getBoolean("fractal.aa.profile"));
    }

    InteractiveAntialiasService(AntialiasSampleCache sampleCache) {
        this(sampleCache, false);
    }

    InteractiveAntialiasService(AntialiasSampleCache sampleCache, boolean profiling) {
        this(sampleCache, profiling, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    InteractiveAntialiasService(AntialiasSampleCache sampleCache, boolean profiling, int workerCount) {
        if (workerCount < 1) throw new IllegalArgumentException("Positive worker count required");
        this.sampleCache = Objects.requireNonNull(sampleCache);
        this.profiling = profiling;
        workers = Executors.newFixedThreadPool(workerCount, daemonThreadFactory("fractal-aa-worker"));
    }

    public Profile lastProfile() { return lastProfile; }

    public void refine(
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            RefinedPixelSnapshot reusedPixels,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            Runnable onSuccess,
            Consumer<Throwable> onError
    ) {
        startRefinement(
                frame, coloring, samplingPattern, reusedPixels,
                callbackExecutor, onTileReady, onSuccess, onError, false);
    }

    /** Runs candidate-only AA without converting deep subpixel coordinates to doubles. */
    public void refineDeep(
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            RefinedPixelSnapshot reusedPixels,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            Runnable onSuccess,
            Consumer<Throwable> onError
    ) {
        startRefinement(
                frame, coloring, samplingPattern, reusedPixels,
                callbackExecutor, onTileReady, onSuccess, onError, true);
    }

    private synchronized void startRefinement(
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            RefinedPixelSnapshot reusedPixels,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            Runnable onSuccess,
            Consumer<Throwable> onError,
            boolean deepZoom
    ) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(samplingPattern);
        Objects.requireNonNull(reusedPixels);
        Objects.requireNonNull(callbackExecutor);
        Objects.requireNonNull(onTileReady);
        Objects.requireNonNull(onSuccess);
        Objects.requireNonNull(onError);

        if (!frame.isComplete()) {
            throw new IllegalArgumentException("Interactive AA requires a completed frame");
        }
        if (reusedPixels.width() != frame.samplePlane().width()
                || reusedPixels.height() != frame.samplePlane().height()) {
            throw new IllegalArgumentException("Refined pixel dimensions must match frame");
        }

        long refinementId = generation.incrementAndGet();
        cancelCurrentFuture();
        synchronized (this) {
            if (cachedFrame != frame || cachedSamplingPattern != samplingPattern) {
                sampleCache.clear();
                cachedFrame = frame;
                cachedSamplingPattern = samplingPattern;
                basePhaseFrame = null;
                basePhaseCache = null;
            }
        }
        currentRefinement = coordinator.submit(() -> runRefinement(
                refinementId,
                frame,
                coloring,
                samplingPattern,
                reusedPixels,
                callbackExecutor,
                onTileReady,
                onSuccess,
                onError,
                deepZoom
        ));
    }

    private void runRefinement(
            long refinementId,
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            RefinedPixelSnapshot reusedPixels,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            Runnable onSuccess,
            Consumer<Throwable> onError,
            boolean deepZoom
    ) {
        try {
            long started = profiling ? System.nanoTime() : 0;
            MutableProfile profile = profiling ? new MutableProfile() : null;
            lastProfile = Profile.EMPTY;
            SamplePlane data = frame.samplePlane();
            int[] baseColors;
            SmoothColorLookup lookup = coloring instanceof SmoothPaletteColoring smooth
                    ? new SmoothColorLookup(smooth)
                    : null;
            if (coloring instanceof SmoothPaletteColoring smooth) {
                baseColors = new int[data.size()];
                basePhases(frame, smooth).recolorInto(baseColors, lookup);
            } else {
                baseColors = AdaptivePngExportService.colorBaseFrame(data, coloring);
            }
            if (profiling) profile.baseColorNanos = System.nanoTime() - started;
            long cacheStarted = profiling ? System.nanoTime() : 0;
            AntialiasSampleCache.Snapshot retained;
            synchronized (this) {
                if (shouldCancel(refinementId) || cachedFrame != frame) return;
                retained = coloring instanceof SmoothPaletteColoring smooth
                        ? sampleCache.snapshotFor(smooth) : AntialiasSampleCache.Snapshot.EMPTY;
            }
            BitSet cachedPixels = retained.pixelMask();
            // Keep retained AA colors separate: candidate detection must still see base-only colors.
            int[] cachedColors = retained.size() == 0 ? null : new int[data.size()];
            if (cachedColors != null) retained.recolorInto(cachedColors, lookup);
            if (profiling) profile.cachePreparationNanos = System.nanoTime() - cacheStarted;
            PreciseFractalSampler preciseSampler = deepZoom
                    ? PreciseFractalSampler.create(
                    frame.job(), () -> shouldCancel(refinementId)).orElseThrow(
                    () -> new CancellationException("Deep AA reference orbit was cancelled"))
                    : null;
            List<Future<?>> tasks = new ArrayList<>();

            for (RenderRegion tile : orderedTiles(frame, reusedPixels)) {
                tasks.add(workers.submit(() -> refineTile(
                        refinementId,
                        frame,
                        coloring,
                        samplingPattern,
                        baseColors,
                        cachedPixels,
                        cachedColors,
                        reusedPixels,
                        lookup,
                        tile,
                        callbackExecutor,
                        onTileReady,
                        preciseSampler,
                        profile
                )));
            }

            for (Future<?> task : tasks) {
                task.get();
            }

            if (shouldCancel(refinementId)) {
                return;
            }
            if (profiling) lastProfile = profile.snapshot(System.nanoTime() - started);

            callbackExecutor.execute(() -> {
                if (isCurrent(refinementId)) {
                    onSuccess.run();
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (CancellationException exception) {
            // Superseded by navigation or a newer frame.
        } catch (ExecutionException exception) {
            reportError(refinementId, exception.getCause(), callbackExecutor, onError);
        } catch (RuntimeException exception) {
            reportError(refinementId, exception, callbackExecutor, onError);
        }
    }

    private void refineTile(
            long refinementId,
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            int[] baseColors,
            BitSet cachedPixels,
            int[] cachedColors,
            RefinedPixelSnapshot reusedPixels,
            SmoothColorLookup lookup,
            RenderRegion tile,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            PreciseFractalSampler preciseSampler,
            MutableProfile profile
    ) {
        SamplePlane data = frame.samplePlane();
        RenderGrid grid = frame.renderGrid();
        FractalCalculator calculator = preciseSampler == null
                ? frame.job().formula().createDirectCalculator()
                : null;
        int[] tileColors = new int[tile.width() * tile.height()];
        AntialiasSampleCache.Batch batch = lookup == null ? null
                : sampleCache.newBatch(tileColors.length, (SmoothPaletteColoring) coloring);
        FractalSample[] regularSamples = null, smallSamples = null;
        long candidateNanos = 0, samplingNanos = 0, cacheColorNanos = 0;
        long testedPixels = 0, candidates = 0, samplesCount = 0;

        for (int y = tile.y(); y < tile.y() + tile.height(); y++) {
            if (shouldCancel(refinementId)) {
                throw new CancellationException();
            }

            for (int x = tile.x(); x < tile.x() + tile.width(); x++) {
                if ((x & 15) == 0 && shouldCancel(refinementId)) {
                    throw new CancellationException();
                }

                int tileIndex = (y - tile.y()) * tile.width() + x - tile.x();
                int frameIndex = y * data.width() + x;

                if (cachedPixels.get(frameIndex)) {
                    tileColors[tileIndex] = cachedColors[frameIndex];
                    continue;
                }

                if (reusedPixels.isRefined(x, y)) {
                    tileColors[tileIndex] = reusedPixels.color(x, y);
                    continue;
                }

                tileColors[tileIndex] = baseColors[frameIndex];

                long candidateStarted = profiling ? System.nanoTime() : 0;
                boolean candidate = preciseSampler == null
                        ? AdaptivePngExportService.isSupersamplingCandidate(
                        calculator, grid, frame.request().maxIterations(),
                        data, baseColors, x, y)
                        : isDeepSupersamplingCandidate(
                        data, baseColors, x, y);
                if (profiling) {
                    candidateNanos += System.nanoTime() - candidateStarted;
                    testedPixels++;
                }
                if (!candidate) {
                    continue;
                }
                if (profiling) candidates++;

                long samplingStarted = profiling ? System.nanoTime() : 0;
                int sampleGridSize = preciseSampler == null ? SAMPLE_GRID : deepSampleGridSize(data, x, y);
                int sampleCount = sampleGridSize * sampleGridSize;
                FractalSample[] scratch;
                if (sampleCount == DEEP_LOW_SAMPLE_GRID * DEEP_LOW_SAMPLE_GRID) {
                    if (smallSamples == null) smallSamples = new FractalSample[sampleCount];
                    scratch = smallSamples;
                } else {
                    if (regularSamples == null) regularSamples = new FractalSample[sampleCount];
                    scratch = regularSamples;
                }
                FractalSample[] samples = preciseSampler == null
                        ? sampleGrid(
                        calculator, grid, frame.request().maxIterations(),
                        x, y, samplingPattern, scratch)
                        : sampleDeepGrid(
                        preciseSampler, grid.preciseGrid(), data,
                        x, y, samplingPattern,
                        () -> shouldCancel(refinementId), scratch);
                if (samples == null) {
                    throw new CancellationException();
                }
                if (profiling) {
                    samplingNanos += System.nanoTime() - samplingStarted;
                    samplesCount += samples.length;
                }
                long cacheStarted = profiling ? System.nanoTime() : 0;
                if (batch != null) {
                    Integer storedColor = batch.add(frameIndex, samples, lookup);
                    if (storedColor != null) {
                        tileColors[tileIndex] = storedColor;
                        if (profiling) cacheColorNanos += System.nanoTime() - cacheStarted;
                        continue;
                    }
                }
                tileColors[tileIndex] = AdaptivePngExportService.colorSamples(
                        samples, coloring, frame.request().maxIterations());
                if (profiling) cacheColorNanos += System.nanoTime() - cacheStarted;
            }
        }

        if (profiling) profile.addTile(candidateNanos, samplingNanos, cacheColorNanos,
                testedPixels, candidates, samplesCount);

        // orderedTiles excludes fully displayed tiles. Cached results may still
        // be missing from the surface after cancellation, so publish them too.
        if (shouldCancel(refinementId)) {
            return;
        }
        if (batch != null && !batch.isEmpty()) {
            long mergeStarted = profiling ? System.nanoTime() : 0;
            if (!commitTile(refinementId, frame, batch)) return;
            if (profiling) {
                profile.cacheMergeNanos.add(System.nanoTime() - mergeStarted);
                profile.cacheBatches.increment();
            }
        }

        callbackExecutor.execute(() -> {
            if (isCurrent(refinementId)) {
                onTileReady.accept(tile, tileColors);
            }
        });
    }

    /** Serialize admission with frame replacement, cache shifting and cancellation. */
    private synchronized boolean commitTile(long refinementId, RenderFrame frame,
                                            AntialiasSampleCache.Batch batch) {
        if (shouldCancel(refinementId) || cachedFrame != frame) return false;
        sampleCache.merge(batch);
        return true;
    }

    /** Recolors the completed base frame and every cached AA candidate. */
    public int[] recolorCached(RenderFrame frame, ColoringStrategy coloring) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(coloring);
        int[] colors = new int[frame.samplePlane().size()];
        recolorCachedInto(frame, coloring, colors);
        return colors;
    }

    /** Recolors into caller-owned storage so animation buffers can be reused. */
    public void recolorCachedInto(
            RenderFrame frame,
            ColoringStrategy coloring,
            int[] colors
    ) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(colors);
        SamplePlane data = frame.samplePlane();
        if (colors.length != data.size()) {
            throw new IllegalArgumentException("Recolor buffer dimensions do not match frame");
        }

        if (coloring instanceof SmoothPaletteColoring smooth) {
            BaseColorPhaseCache phases = basePhases(frame, smooth);
            SmoothColorLookup lookup = recolorLookup.get();
            lookup.update(smooth);
            phases.recolorInto(colors, lookup);
            aaSnapshot(frame, smooth).recolorInto(colors, lookup);
            return;
        }

        IntStream.range(0, data.size()).parallel().forEach(index ->
                colors[index] = coloring.color(
                        data.iterations(index), data.smoothIterations(index),
                        data.escaped(index), data.maxIterations(),
                        data.orbitTrapDistance(index)));
    }

    /** Same base + AA operation through the optional GPU backend, with complete CPU timing. */
    public PaletteRecolorTiming recolorCachedInto(
            RenderFrame frame,
            ColoringStrategy coloring,
            int[] colors,
            PaletteRecolorBackend backend
    ) throws InterruptedException {
        Objects.requireNonNull(backend);
        Objects.requireNonNull(frame);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(colors);
        long started = System.nanoTime();
        if (!(coloring instanceof SmoothPaletteColoring smooth)) {
            recolorCachedInto(frame, coloring, colors);
            long elapsed = System.nanoTime() - started;
            return PaletteRecolorTiming.cpu(0, elapsed, elapsed);
        }
        BaseColorPhaseCache phases = basePhases(frame, smooth);
        AntialiasSampleCache.Snapshot aa = aaSnapshot(frame, smooth);
        long prepared = System.nanoTime();
        PaletteRecolorTiming timing = backend.recolor(phases, aa, smooth, colors);
        return timing.withPreparation(prepared - started, System.nanoTime() - started);
    }

    private synchronized AntialiasSampleCache.Snapshot aaSnapshot(
            RenderFrame frame, SmoothPaletteColoring coloring) {
        return cachedFrame == frame ? sampleCache.snapshotFor(coloring) : AntialiasSampleCache.Snapshot.EMPTY;
    }

    private BaseColorPhaseCache basePhases(
            RenderFrame frame,
            SmoothPaletteColoring coloring
    ) {
        synchronized (this) {
            if (basePhaseFrame == frame
                    && basePhaseCache != null
                    && basePhaseCache.matches(coloring)) {
                return basePhaseCache;
            }
        }
        BaseColorPhaseCache created = BaseColorPhaseCache.create(frame.samplePlane(), coloring);
        synchronized (this) {
            basePhaseFrame = frame;
            basePhaseCache = created;
        }
        return created;
    }

    /** Transfers cached AA samples along the exact pixel shift used for frame reuse. */
    public synchronized void reuseFrame(RenderFrame source, RenderFrame target, PixelShift shift) {
        if (cachedFrame != source) {
            sampleCache.clear();
        } else {
            sampleCache.shift(source.samplePlane().width(),
                    target.samplePlane().width(), target.samplePlane().height(), shift);
        }
        cachedFrame = target;
        basePhaseFrame = null;
        basePhaseCache = null;
    }

    private static FractalSample[] sampleDeepGrid(
            PreciseFractalSampler sampler,
            PreciseRenderGrid grid,
            SamplePlane data,
            int pixelX,
            int pixelY,
            SamplingPattern pattern,
            java.util.function.BooleanSupplier cancelled,
            FractalSample[] samples
    ) {
        if (grid == null) {
            throw new IllegalArgumentException("Deep AA requires a precise render grid");
        }
        int sampleGridSize = samples.length == DEEP_LOW_SAMPLE_GRID * DEEP_LOW_SAMPLE_GRID
                ? DEEP_LOW_SAMPLE_GRID : DEEP_HIGH_SAMPLE_GRID;
        BigDecimal centerReal = grid.realAt(pixelX);
        BigDecimal centerImaginary = grid.imaginaryAt(pixelY);
        int index = 0;
        for (int sampleY = 0; sampleY < sampleGridSize; sampleY++) {
            for (int sampleX = 0; sampleX < sampleGridSize; sampleX++) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                double offsetX = ((sampleX + sampleOffset(
                        pattern, pixelX, pixelY, sampleX, sampleY, 0))
                        / sampleGridSize) - 0.5;
                double offsetY = ((sampleY + sampleOffset(
                        pattern, pixelX, pixelY, sampleX, sampleY, 1))
                        / sampleGridSize) - 0.5;
                BigDecimal real = centerReal.add(
                        grid.realStep().multiply(
                                BigDecimal.valueOf(offsetX), grid.mathContext()),
                        grid.mathContext());
                BigDecimal imaginary = centerImaginary.subtract(
                        grid.imaginaryStep().multiply(
                                BigDecimal.valueOf(offsetY), grid.mathContext()),
                        grid.mathContext());
                FractalSample sample = sampler.sample(real, imaginary, cancelled);
                if (sample == null) {
                    return null;
                }
                samples[index++] = sample;
            }
        }
        return samples;
    }

    /** Uses 4x4 only across the set boundary; palette-only edges use 2x2. */
    static int deepSampleGridSize(
            SamplePlane data,
            int x,
            int y
    ) {
        int width = data.width();
        boolean centerEscaped = data.escaped(y * width + x);
        for (int neighborY = Math.max(0, y - 1);
             neighborY <= Math.min(data.height() - 1, y + 1);
             neighborY++) {
            for (int neighborX = Math.max(0, x - 1);
                 neighborX <= Math.min(width - 1, x + 1);
                 neighborX++) {
                int neighborIndex = neighborY * width + neighborX;
                if (centerEscaped != data.escaped(neighborIndex)) {
                    return DEEP_HIGH_SAMPLE_GRID;
                }
            }
        }
        return DEEP_LOW_SAMPLE_GRID;
    }

    /** Deep AA focuses on set boundaries and visually strong palette outliers. */
    static boolean isDeepSupersamplingCandidate(
            SamplePlane data,
            int[] colors,
            int x,
            int y
    ) {
        int width = data.width();
        int centerIndex = y * width + x;
        boolean centerEscaped = data.escaped(centerIndex);
        for (int neighborY = Math.max(0, y - 1);
             neighborY <= Math.min(data.height() - 1, y + 1);
             neighborY++) {
            for (int neighborX = Math.max(0, x - 1);
                 neighborX <= Math.min(width - 1, x + 1);
                 neighborX++) {
                int neighborIndex = neighborY * width + neighborX;
                if (centerEscaped != data.escaped(neighborIndex)
                        || AdaptivePngExportService.colorContrast(
                        colors[centerIndex], colors[neighborIndex])
                        > DEEP_COLOR_EDGE_THRESHOLD) {
                    return true;
                }
            }
        }
        return false;
    }

    private static FractalSample[] sampleGrid(
            FractalCalculator calculator,
            RenderGrid grid,
            int maxIterations,
            int pixelX,
            int pixelY,
            SamplingPattern pattern,
            FractalSample[] samples
    ) {
        double centerReal = grid.realAt(pixelX);
        double centerImaginary = grid.imaginaryAt(pixelY);
        int index = 0;
        for (int sampleY = 0; sampleY < SAMPLE_GRID; sampleY++) {
            for (int sampleX = 0; sampleX < SAMPLE_GRID; sampleX++) {
                double offsetX = ((sampleX + sampleOffset(pattern, pixelX, pixelY, sampleX, sampleY, 0))
                        / SAMPLE_GRID) - 0.5;
                double offsetY = ((sampleY + sampleOffset(pattern, pixelX, pixelY, sampleX, sampleY, 1))
                        / SAMPLE_GRID) - 0.5;
                samples[index++] = calculator.calculateSample(
                        centerReal + offsetX * grid.realStep(),
                        centerImaginary - offsetY * grid.imaginaryStep(),
                        maxIterations);
            }
        }
        return samples;
    }

    private static double sampleOffset(
            SamplingPattern pattern, int pixelX, int pixelY,
            int sampleX, int sampleY, int axis
    ) {
        if (pattern == SamplingPattern.REGULAR) {
            return 0.5;
        }
        int seed = pixelX * 0x1f123bb5 ^ pixelY * 0x5f356495
                ^ sampleX * 0x68bc21eb ^ sampleY * 0x02e5be93 ^ axis * 0x7f4a7c15;
        int mixed = seed;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return (mixed & 0xffffffffL) / 4294967296.0;
    }

    static List<RenderRegion> orderedTiles(
            RenderFrame frame,
            RefinedPixelSnapshot reusedPixels
    ) {
        List<RenderRegion> tiles = new ArrayList<>();

        for (int y = 0; y < frame.samplePlane().height(); y += TILE_SIZE) {
            for (int x = 0; x < frame.samplePlane().width(); x += TILE_SIZE) {
                RenderRegion tile = new RenderRegion(
                        x,
                        y,
                        Math.min(TILE_SIZE, frame.samplePlane().width() - x),
                        Math.min(TILE_SIZE, frame.samplePlane().height() - y)
                );
                if (!reusedPixels.isRegionRefined(tile)) {
                    tiles.add(tile);
                }
            }
        }

        double priorityX = frame.samplePlane().width() * frame.request().priority().x();
        double priorityY = frame.samplePlane().height() * frame.request().priority().y();
        Comparator<RenderRegion> byDistance = Comparator.comparingDouble(
                tile -> distanceSquared(tile, priorityX, priorityY));

        frame.request().approximateCoverage().ifPresentOrElse(
                coverage -> tiles.sort((first, second) -> compareForZoomOut(
                        first, second, coverage, priorityX, priorityY)),
                () -> tiles.sort(byDistance)
        );
        return tiles;
    }

    private static int compareForZoomOut(
            RenderRegion first,
            RenderRegion second,
            RenderRegion coverage,
            double priorityX,
            double priorityY
    ) {
        int firstUncovered = uncoveredArea(first, coverage);
        int secondUncovered = uncoveredArea(second, coverage);

        boolean firstExposed = firstUncovered > 0;
        boolean secondExposed = secondUncovered > 0;
        if (firstExposed != secondExposed) {
            return firstExposed ? -1 : 1;
        }

        double firstDistance = distanceSquared(first, priorityX, priorityY);
        double secondDistance = distanceSquared(second, priorityX, priorityY);

        if (firstExposed) {
            int byPreviewEdge = Double.compare(
                    distanceToCoverageSquared(first, coverage),
                    distanceToCoverageSquared(second, coverage));
            if (byPreviewEdge != 0) {
                return byPreviewEdge;
            }
        }

        return Double.compare(firstDistance, secondDistance);
    }

    private static int uncoveredArea(RenderRegion tile, RenderRegion coverage) {
        int overlapWidth = Math.max(0, Math.min(
                tile.x() + tile.width(), coverage.x() + coverage.width())
                - Math.max(tile.x(), coverage.x()));
        int overlapHeight = Math.max(0, Math.min(
                tile.y() + tile.height(), coverage.y() + coverage.height())
                - Math.max(tile.y(), coverage.y()));
        return tile.width() * tile.height() - overlapWidth * overlapHeight;
    }

    private static double distanceToCoverageSquared(
            RenderRegion tile,
            RenderRegion coverage
    ) {
        int tileRight = tile.x() + tile.width();
        int tileBottom = tile.y() + tile.height();
        int coverageRight = coverage.x() + coverage.width();
        int coverageBottom = coverage.y() + coverage.height();
        int dx = Math.max(0, Math.max(
                coverage.x() - tileRight,
                tile.x() - coverageRight));
        int dy = Math.max(0, Math.max(
                coverage.y() - tileBottom,
                tile.y() - coverageBottom));
        return (double) dx * dx + (double) dy * dy;
    }

    private static double distanceSquared(RenderRegion tile, double x, double y) {
        double dx = tile.x() + tile.width() / 2.0 - x;
        double dy = tile.y() + tile.height() / 2.0 - y;
        return dx * dx + dy * dy;
    }

    public synchronized void cancelCurrent() {
        generation.incrementAndGet();
        cancelCurrentFuture();
    }

    private synchronized void cancelCurrentFuture() {
        Future<?> refinement = currentRefinement;
        currentRefinement = null;
        if (refinement != null && !refinement.isDone()) {
            refinement.cancel(true);
        }
    }

    private boolean isCurrent(long refinementId) {
        return refinementId == generation.get();
    }

    private boolean shouldCancel(long refinementId) {
        return !isCurrent(refinementId) || Thread.currentThread().isInterrupted();
    }

    private void reportError(
            long refinementId,
            Throwable error,
            Executor callbackExecutor,
            Consumer<Throwable> onError
    ) {
        if (isCurrent(refinementId)) {
            callbackExecutor.execute(() -> {
                if (isCurrent(refinementId)) {
                    onError.accept(error);
                }
            });
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class MutableProfile {
        private long baseColorNanos;
        private long cachePreparationNanos;
        private final LongAdder cacheMergeNanos = new LongAdder();
        private final LongAdder cacheBatches = new LongAdder();
        private final LongAdder candidateNanos = new LongAdder();
        private final LongAdder samplingNanos = new LongAdder();
        private final LongAdder cacheColorNanos = new LongAdder();
        private final LongAdder testedPixels = new LongAdder();
        private final LongAdder candidates = new LongAdder();
        private final LongAdder samples = new LongAdder();
        private final ConcurrentHashMap<Thread, LongAdder> candidateByWorker = new ConcurrentHashMap<>();

        private void addTile(long candidate, long sampling, long cacheColor,
                             long tested, long candidateCount, long sampleCount) {
            candidateNanos.add(candidate);
            samplingNanos.add(sampling);
            cacheColorNanos.add(cacheColor);
            testedPixels.add(tested);
            candidates.add(candidateCount);
            samples.add(sampleCount);
            candidateByWorker.computeIfAbsent(Thread.currentThread(), ignored -> new LongAdder()).add(candidate);
        }

        private Profile snapshot(long totalNanos) {
            long candidateCriticalPath = candidateByWorker.values().stream()
                    .mapToLong(LongAdder::sum).max().orElse(0);
            return new Profile(totalNanos, baseColorNanos, candidateNanos.sum(), candidateCriticalPath,
                    samplingNanos.sum(), cacheColorNanos.sum(), testedPixels.sum(), candidates.sum(), samples.sum(),
                    cachePreparationNanos, cacheMergeNanos.sum(), cacheBatches.sum());
        }
    }

    @Override
    public synchronized void close() {
        generation.incrementAndGet();
        cancelCurrentFuture();
        coordinator.shutdownNow();
        workers.shutdownNow();
    }
}
