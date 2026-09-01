package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Calculates incomplete regions of a render frame on a fixed worker pool.
 * Tiles are ordered around the request priority point and completed regions
 * are reported incrementally to support progressive display.
 */
public final class ParallelFractalCalculator implements AutoCloseable {

    private static final int DEFAULT_TILE_SIZE = 32;
    private final ExecutorService workers;
    private final int tileSize;

    record Tile(
            int xFrom,
            int xTo,
            int yFrom,
            int yTo
    ) {}

    public ParallelFractalCalculator() {
        this(defaultWorkerCount(), DEFAULT_TILE_SIZE);
    }

    public ParallelFractalCalculator(int workerCount) {
        this(workerCount, DEFAULT_TILE_SIZE);
    }

    public ParallelFractalCalculator(
            int workerCount,
            int tileSize
    ) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be at least 1");
        }
        if (tileSize < 1) {
            throw new IllegalArgumentException("Tile size must be at least 1");
        }
        this.tileSize = tileSize;
        workers = Executors.newFixedThreadPool(
                workerCount,
                daemonThreadFactory("fractal-worker"));
    }

    private static int defaultWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();

        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Calculates all missing tiles unless cancellation is requested. */
    public RenderFrame calculate(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted
    ) throws InterruptedException {
        return calculateInternal(
                frame,
                cancelled,
                regionCompleted,
                null
        );
    }

    /** Calculates missing tiles and reports aggregate per-tile timing data. */
    public RenderFrame calculate(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException {
        Objects.requireNonNull(timingCompleted);

        return calculateInternal(
                frame,
                cancelled,
                regionCompleted,
                timingCompleted
        );
    }

    private RenderFrame calculateInternal(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            Consumer<TileTimingStats> timingCompleted
    ) throws InterruptedException {

        Objects.requireNonNull(frame);
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(regionCompleted);

        if (cancelled.getAsBoolean()) {
            return frame;
        }

        ConcurrentLinkedQueue<Long> tileTimesNanos = timingCompleted == null
                ? null
                : new ConcurrentLinkedQueue<>();

        List<Callable<Void>> tasks =
                createTileTasks(
                        frame,
                        cancelled,
                        regionCompleted,
                        tileTimesNanos
                );

        List<Future<Void>> futures =
                workers.invokeAll(tasks);

        for (Future<Void> future : futures) {

            if (cancelled.getAsBoolean()) {
                return frame;
            }

            try {
                future.get();

            } catch (CancellationException e) {

                return frame;

            } catch (ExecutionException e) {

                throw new IllegalStateException(
                        "Tile calculation failed",
                        e.getCause()
                );
            }
        }

        /* invokeAll has completed, so the timing collection is now stable. */
        if (timingCompleted != null) {
            timingCompleted.accept(
                    createTimingStats(
                            tileTimesNanos
                    )
            );
        }

        return frame;
    }

    private static TileTimingStats createTimingStats(
            ConcurrentLinkedQueue<Long> timings
    ) {
        if (timings.isEmpty()) {
            return new TileTimingStats(
                    0,
                    0.0,
                    0.0,
                    0.0
            );
        }

        List<Long> sorted =
                new ArrayList<>(
                        timings
                );

        sorted.sort(
                Long::compare
        );

        int size =
                sorted.size();

        long minNanos =
                sorted.get(0);

        long maxNanos =
                sorted.get(
                        size - 1
                );

        double medianNanos;

        int middle =
                size / 2;

        if (size % 2 == 0) {

            medianNanos =
                    (
                            sorted.get(middle - 1)
                                    .doubleValue()
                                    +
                                    sorted.get(middle)
                                            .doubleValue()
                    ) / 2.0;

        } else {

            medianNanos =
                    sorted.get(middle);
        }

        return new TileTimingStats(
                size,
                nanosToMs(minNanos),
                nanosToMs(medianNanos),
                nanosToMs(maxNanos)
        );
    }

    private static double nanosToMs(
            double nanos
    ) {
        return nanos / 1_000_000.0;
    }

    private static double distanceSquared(
            Tile tile,
            double centerX,
            double centerY
    ) {
        double tileCenterX = (tile.xFrom() + tile.xTo()) / 2.0;

        double tileCenterY = (tile.yFrom() + tile.yTo()) / 2.0;

        double dx = tileCenterX - centerX;

        double dy = tileCenterY - centerY;

        return dx * dx + dy * dy;
    }

    private List<Callable<Void>> createTileTasks(
            RenderFrame renderFrame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted,
            ConcurrentLinkedQueue<Long> tileTimesNanos
    ) {
        RenderRequest renderRequest =
                renderFrame.request();

        FractalData fractalData =
                renderFrame.fractalData();

        RenderGrid renderGrid =
                renderFrame.renderGrid();

        boolean useConjugateSymmetry =
                fractalData.height() > 1
                        && renderFrame.validity().readyPixelCount() == 0
                        && renderRequest.calculator().hasConjugateSymmetry()
                        && renderGrid.isConjugateSymmetric(renderRequest.height());

        boolean hasReusablePixels =
                renderFrame.validity().readyPixelCount() > 0;

        List<Tile> tiles =
                createOrderedTiles(
                        renderRequest,
                        useConjugateSymmetry
                                ? (renderRequest.height() + 1) / 2
                                : renderRequest.height()
                );

        List<Callable<Void>> tasks =
                new ArrayList<>(
                        tiles.size()
                );

        for (Tile tile : tiles) {

            RenderRegion region =
                    new RenderRegion(
                            tile.xFrom(),
                            tile.yFrom(),
                            tile.xTo() - tile.xFrom(),
                            tile.yTo() - tile.yFrom()
                    );

            List<RenderRegion> missingSpans = hasReusablePixels
                    ? renderFrame.validity().missingRowSpans(region)
                    : List.of(region);

            /* Fully reused tiles do not need worker tasks. */
            if (missingSpans.isEmpty()) {

                continue;
            }

            tasks.add(() -> {

                if (cancelled.getAsBoolean()) {
                    return null;
                }

                /* Measure calculation only; mask and progress updates are excluded. */
                long tileStart = tileTimesNanos == null
                        ? 0L
                        : System.nanoTime();

                boolean completed = true;

                for (RenderRegion missingSpan : missingSpans) {
                    completed = renderRequest
                            .calculator()
                            .calculateTile(
                                    fractalData,
                                    renderGrid,
                                    missingSpan.x(),
                                    missingSpan.x() + missingSpan.width(),
                                    missingSpan.y(),
                                    missingSpan.y() + missingSpan.height(),
                                    renderRequest.maxIterations(),
                                    cancelled,
                                    (x, y) -> false,
                                    completedRow -> {
                                        renderFrame.validity().markReady(completedRow);

                                        if (hasReusablePixels) {
                                            regionCompleted.accept(completedRow);
                                        }

                                        if (useConjugateSymmetry) {
                                            int mirrorY = renderRequest.height()
                                                    - 1
                                                    - completedRow.y();

                                            if (mirrorY != completedRow.y()) {
                                                fractalData.copyRowFrom(
                                                        fractalData,
                                                        completedRow.y(),
                                                        mirrorY,
                                                        completedRow.x(),
                                                        completedRow.x() + completedRow.width()
                                                );

                                                RenderRegion mirrorRow = new RenderRegion(
                                                        completedRow.x(),
                                                        mirrorY,
                                                        completedRow.width(),
                                                        1
                                                );

                                                renderFrame.validity().markReady(mirrorRow);
                                            }
                                        }
                                    }
                            );

                    if (!completed) {
                        break;
                    }
                }

                if (!completed) {
                    return null;
                }

                if (tileTimesNanos != null) {
                    tileTimesNanos.add(
                            System.nanoTime() - tileStart
                    );
                }

                if (!hasReusablePixels) {
                    regionCompleted.accept(region);
                }

                if (!hasReusablePixels && useConjugateSymmetry) {
                    RenderRegion mirrorRegion = new RenderRegion(
                            tile.xFrom(),
                            renderRequest.height() - tile.yTo(),
                            tile.xTo() - tile.xFrom(),
                            tile.yTo() - tile.yFrom()
                    );

                    if (!mirrorRegion.equals(region)) {
                        regionCompleted.accept(mirrorRegion);
                    }
                }

                return null;
            });
        }

        return tasks;
    }

    List<Tile> createOrderedTiles(
            RenderRequest request
    ) {
        return createOrderedTiles(request, request.height());
    }

    private List<Tile> createOrderedTiles(
            RenderRequest request,
            int renderedHeight
    ) {
        List<Tile> tiles = new ArrayList<>();

        for (int y = 0; y < renderedHeight; y += tileSize) {
            int yTo = Math.min(y + tileSize, renderedHeight);

            for (int x = 0; x < request.width(); x += tileSize) {
                int xTo = Math.min(x + tileSize, request.width());

                tiles.add(new Tile(x, xTo, y, yTo));
            }
        }

        double priorityX = request.width() * request.priority().x();
        double priorityY = request.height() * request.priority().y();

        Comparator<Tile> byDistance = Comparator.comparingDouble(
                tile -> distanceSquared(tile, priorityX, priorityY));

        /*
         * A zoomed-out previous frame already gives the center an approximate
         * image. Calculate newly exposed or boundary-crossing tiles first,
         * then replace the approximate center with exact samples.
         */
        request.approximateCoverage().ifPresentOrElse(
                coverage -> tiles.sort(
                        Comparator.comparingInt(
                                        (Tile tile) -> isFullyCovered(tile, coverage) ? 1 : 0)
                                .thenComparing(byDistance)),
                () -> tiles.sort(byDistance)
        );

        return tiles;
    }

    private static boolean isFullyCovered(Tile tile, RenderRegion coverage) {
        return tile.xFrom() >= coverage.x()
                && tile.xTo() <= coverage.x() + coverage.width()
                && tile.yFrom() >= coverage.y()
                && tile.yTo() <= coverage.y() + coverage.height();
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
