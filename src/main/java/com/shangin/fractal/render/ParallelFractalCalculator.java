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

    private static final int TILE_SIZE = 32;
    private final ExecutorService workers;

    record Tile(
            int xFrom,
            int xTo,
            int yFrom,
            int yTo
    ) {}

    public ParallelFractalCalculator() {
        this(defaultWorkerCount());
    }

    public ParallelFractalCalculator(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be at least 1");
        }
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

        List<Tile> tiles =
                createOrderedTiles(
                        renderRequest
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

            /* Fully reused tiles do not need worker tasks. */
            if (renderFrame.validity()
                    .isRegionReady(region)) {

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

                boolean completed =
                        renderRequest
                                .calculator()
                                .calculateTile(
                                        fractalData,
                                        renderGrid,
                                        tile.xFrom(),
                                        tile.xTo(),
                                        tile.yFrom(),
                                        tile.yTo(),
                                        renderRequest.maxIterations(),
                                        cancelled
                                );

                if (!completed) {
                    return null;
                }

                if (tileTimesNanos != null) {
                    tileTimesNanos.add(
                            System.nanoTime() - tileStart
                    );
                }

                renderFrame.validity()
                        .markReady(region);

                regionCompleted.accept(
                        region
                );

                return null;
            });
        }

        return tasks;
    }

    List<Tile> createOrderedTiles(
            RenderRequest request
    ) {
        List<Tile> tiles = new ArrayList<>();

        for (int y = 0; y < request.height(); y += TILE_SIZE) {
            int yTo = Math.min(y + TILE_SIZE, request.height());

            for (int x = 0; x < request.width(); x += TILE_SIZE) {
                int xTo = Math.min(x + TILE_SIZE, request.width());

                tiles.add(new Tile(x, xTo, y, yTo));
            }
        }

        double priorityX = request.width() * request.priority().x();
        double priorityY = request.height() * request.priority().y();

        tiles.sort(Comparator.comparingDouble(
                tile ->
                        distanceSquared(
                                tile,
                                priorityX,
                                priorityY)));

        return tiles;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
