package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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

    public RenderFrame calculate(
            RenderFrame frame,
            BooleanSupplier cancelled,
            Consumer<RenderRegion> regionCompleted
    ) throws InterruptedException
    {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(regionCompleted);

        if (cancelled.getAsBoolean()) {
            return frame;
        }

        List<Callable<Void>> tasks = createTileTasks(
                frame,
                cancelled,
                regionCompleted);

        List<Future<Void>> futures = workers.invokeAll(tasks);

        for (Future<Void> future : futures) {
            if (cancelled.getAsBoolean()) {
                return frame;
            }

            try {
                future.get();
            } catch (CancellationException e) {
                return frame;
            } catch (ExecutionException e) {
                throw new IllegalStateException("Tile calculation failed",e.getCause());
            }
        }
        return frame;
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
            Consumer<RenderRegion> regionCompleted
    ) {
        RenderRequest renderRequest = renderFrame.request();
        FractalData fractalData = renderFrame.fractalData();
        RenderGrid renderGrid = renderFrame.renderGrid();

        List<Tile> tiles = createOrderedTiles(renderRequest);

        List<Callable<Void>> tasks = new ArrayList<>(tiles.size());

        for (Tile tile : tiles) {
            RenderRegion region = new RenderRegion(
                    tile.xFrom(),
                    tile.yFrom(),
                    tile.xTo() - tile.xFrom(),
                    tile.yTo() - tile.yFrom());

            if (renderFrame.validity().isRegionReady(region)) {
                continue;
            }

            tasks.add(() -> {
                if (cancelled.getAsBoolean()) {
                    return null;
                }

                boolean completed = renderRequest
                        .calculator()
                        .calculateTile(
                                fractalData,
                                renderGrid,
                                tile.xFrom(),
                                tile.xTo(),
                                tile.yFrom(),
                                tile.yTo(),
                                renderRequest.maxIterations(),
                                cancelled);

                if (!completed) {
                    return null;
                }

                renderFrame.validity().markReady(region);
                regionCompleted.accept(region);

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