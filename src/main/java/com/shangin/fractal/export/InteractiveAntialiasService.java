package com.shangin.fractal.export;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalData;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.RefinedPixelSnapshot;
import com.shangin.fractal.render.AntialiasSampleCache;
import com.shangin.fractal.render.PixelShift;
import com.shangin.fractal.render.BaseColorPhaseCache;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.scene.SamplingPattern;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int TILE_SIZE = 32;

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            daemonThreadFactory("fractal-aa")
    );
    private final ExecutorService workers = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            daemonThreadFactory("fractal-aa-worker")
    );
    private final AtomicLong generation = new AtomicLong();
    private final AntialiasSampleCache sampleCache;
    private RenderFrame cachedFrame;
    private RenderFrame basePhaseFrame;
    private BaseColorPhaseCache basePhaseCache;
    private final ThreadLocal<SmoothColorLookup> recolorLookup =
            ThreadLocal.withInitial(SmoothColorLookup::new);
    private Future<?> currentRefinement;

    public InteractiveAntialiasService() {
        this(new AntialiasSampleCache());
    }

    InteractiveAntialiasService(AntialiasSampleCache sampleCache) {
        this.sampleCache = Objects.requireNonNull(sampleCache);
    }

    public synchronized void refine(
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            RefinedPixelSnapshot reusedPixels,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            Runnable onSuccess,
            Consumer<Throwable> onError
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
        if (reusedPixels.width() != frame.fractalData().width()
                || reusedPixels.height() != frame.fractalData().height()) {
            throw new IllegalArgumentException("Refined pixel dimensions must match frame");
        }

        long refinementId = generation.incrementAndGet();
        cancelCurrentFuture();
        synchronized (this) {
            if (cachedFrame != frame) {
                sampleCache.clear();
                cachedFrame = frame;
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
                onError
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
            Consumer<Throwable> onError
    ) {
        try {
            FractalData data = frame.fractalData();
            int[] baseColors = AdaptivePngExportService.colorBaseFrame(data, coloring);
            SmoothColorLookup lookup = coloring instanceof SmoothPaletteColoring smooth
                    ? new SmoothColorLookup(smooth)
                    : null;
            List<Future<?>> tasks = new ArrayList<>();

            for (RenderRegion tile : orderedTiles(frame, reusedPixels)) {
                tasks.add(workers.submit(() -> refineTile(
                        refinementId,
                        frame,
                        coloring,
                        samplingPattern,
                        baseColors,
                        reusedPixels,
                        lookup,
                        tile,
                        callbackExecutor,
                        onTileReady
                )));
            }

            for (Future<?> task : tasks) {
                task.get();
            }

            if (shouldCancel(refinementId)) {
                return;
            }

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
            RefinedPixelSnapshot reusedPixels,
            SmoothColorLookup lookup,
            RenderRegion tile,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady
    ) {
        FractalData data = frame.fractalData();
        RenderGrid grid = frame.renderGrid();
        FractalCalculator calculator = frame.request().calculator();
        int[] tileColors = new int[tile.width() * tile.height()];
        boolean calculatedPixel = false;

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

                Integer cachedColor = lookup == null
                        ? null
                        : sampleCache.color(frameIndex, lookup);
                if (cachedColor != null) {
                    tileColors[tileIndex] = cachedColor;
                    continue;
                }

                if (reusedPixels.isRefined(x, y)) {
                    tileColors[tileIndex] = reusedPixels.color(x, y);
                    continue;
                }

                calculatedPixel = true;
                tileColors[tileIndex] = baseColors[frameIndex];

                if (!AdaptivePngExportService.isSupersamplingCandidate(
                        calculator,
                        grid,
                        frame.request().maxIterations(),
                        data,
                        baseColors,
                        x,
                        y
                )) {
                    continue;
                }

                FractalSample[] samples = sampleGrid(
                        calculator, grid, frame.request().maxIterations(), x, y, samplingPattern);
                if (coloring instanceof SmoothPaletteColoring smooth) {
                    sampleCache.put(frameIndex, samples, smooth);
                }
                tileColors[tileIndex] = AdaptivePngExportService.colorSamples(
                        samples, coloring, frame.request().maxIterations());
            }
        }

        if (!calculatedPixel || shouldCancel(refinementId)) {
            return;
        }

        callbackExecutor.execute(() -> {
            if (isCurrent(refinementId)) {
                onTileReady.accept(tile, tileColors);
            }
        });
    }

    /** Recolors the completed base frame and every cached AA candidate. */
    public int[] recolorCached(RenderFrame frame, ColoringStrategy coloring) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(coloring);
        int[] colors = new int[frame.fractalData().size()];
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
        FractalData data = frame.fractalData();
        if (colors.length != data.size()) {
            throw new IllegalArgumentException("Recolor buffer dimensions do not match frame");
        }

        if (coloring instanceof SmoothPaletteColoring smooth) {
            BaseColorPhaseCache phases = basePhases(frame, smooth);
            SmoothColorLookup lookup = recolorLookup.get();
            lookup.update(smooth);
            phases.recolorInto(colors, lookup);
            synchronized (this) {
                if (cachedFrame != frame) {
                    return;
                }
            }
            sampleCache.recolorInto(colors, lookup);
            return;
        }

        IntStream.range(0, data.size()).parallel().forEach(index ->
                colors[index] = coloring.color(
                        data.iterations(index), data.smoothIterations(index),
                        data.escaped(index), data.maxIterations(),
                        data.orbitTrapDistance(index)));
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
        BaseColorPhaseCache created = BaseColorPhaseCache.create(frame.fractalData(), coloring);
        synchronized (this) {
            basePhaseFrame = frame;
            basePhaseCache = created;
        }
        return created;
    }

    /** Transfers cached AA samples along the exact pixel shift used for frame reuse. */
    public synchronized void reuseFrame(RenderFrame source, RenderFrame target, PixelShift shift) {
        if (cachedFrame != source
                || source.fractalData().width() != target.fractalData().width()
                || source.fractalData().height() != target.fractalData().height()) {
            sampleCache.clear();
        } else {
            sampleCache.shift(source.fractalData().width(), source.fractalData().height(), shift);
        }
        cachedFrame = target;
        basePhaseFrame = null;
        basePhaseCache = null;
    }

    private static FractalSample[] sampleGrid(
            FractalCalculator calculator,
            RenderGrid grid,
            int maxIterations,
            int pixelX,
            int pixelY,
            SamplingPattern pattern
    ) {
        FractalSample[] samples = new FractalSample[SAMPLE_GRID * SAMPLE_GRID];
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

        for (int y = 0; y < frame.fractalData().height(); y += TILE_SIZE) {
            for (int x = 0; x < frame.fractalData().width(); x += TILE_SIZE) {
                RenderRegion tile = new RenderRegion(
                        x,
                        y,
                        Math.min(TILE_SIZE, frame.fractalData().width() - x),
                        Math.min(TILE_SIZE, frame.fractalData().height() - y)
                );
                if (!reusedPixels.isRegionRefined(tile)) {
                    tiles.add(tile);
                }
            }
        }

        double priorityX = frame.fractalData().width() * frame.request().priority().x();
        double priorityY = frame.fractalData().height() * frame.request().priority().y();
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

        int byUncoveredArea = Integer.compare(secondUncovered, firstUncovered);
        if (byUncoveredArea != 0) {
            return byUncoveredArea;
        }

        double firstDistance = distanceSquared(first, priorityX, priorityY);
        double secondDistance = distanceSquared(second, priorityX, priorityY);
        return firstUncovered > 0
                ? Double.compare(secondDistance, firstDistance)
                : Double.compare(firstDistance, secondDistance);
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

    @Override
    public synchronized void close() {
        generation.incrementAndGet();
        cancelCurrentFuture();
        coordinator.shutdownNow();
        workers.shutdownNow();
    }
}
