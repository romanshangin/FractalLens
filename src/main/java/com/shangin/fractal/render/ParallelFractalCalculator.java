package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class ParallelFractalCalculator implements AutoCloseable {

    private static final int TILE_SIZE = 32;
    private final ExecutorService workers;

    public ParallelFractalCalculator() {
        this(defaultWorkerCount());
    }

    public ParallelFractalCalculator(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("Worker count must be at least 1");
        }
        workers = Executors.newFixedThreadPool(workerCount, daemonThreadFactory("fractal-worker"));
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

    public FractalData calculate(
            RenderRequest request,
            BooleanSupplier cancelled
    ) throws InterruptedException
    {
        if (cancelled.getAsBoolean()) {
            return null;
        }

        FractalData data = new FractalData(request.width(), request.height(), request.maxIterations());

        List<Callable<Void>> tasks = createTileTasks(request, data, cancelled);

        List<Future<Void>> futures = workers.invokeAll(tasks);

        for (Future<Void> future : futures) {
            if (cancelled.getAsBoolean()) {
                return null;
            }

            try {
                future.get();

            } catch (CancellationException e) {
                return null;

            } catch (ExecutionException e) {
                throw new IllegalStateException("Tile calculation failed", e.getCause());
            }
        }

        if (cancelled.getAsBoolean()) {
            return null;
        }

        return data;
    }

    private List<Callable<Void>> createTileTasks(
            RenderRequest request,
            FractalData data,
            BooleanSupplier cancelled
    ) {
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int y = 0; y < request.height(); y += TILE_SIZE) {

            int yFrom = y;
            int yTo = Math.min(y + TILE_SIZE, request.height());

            for (int x = 0; x < request.width(); x += TILE_SIZE) {

                int xFrom = x;
                int xTo = Math.min(x + TILE_SIZE, request.width());

                tasks.add(() -> {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }

                    request.calculator().calculateTile(
                            data,
                            request.viewport(),
                            request.width(),
                            request.height(),
                            xFrom,
                            xTo,
                            yFrom,
                            yTo,
                            request.maxIterations(),
                            cancelled);

                    return null;
                });
            }
        }

        return tasks;
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}