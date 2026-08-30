package com.shangin.fractal.export;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalData;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.scene.SamplingPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
    private Future<?> currentRefinement;

    public synchronized void refine(
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            Runnable onSuccess,
            Consumer<Throwable> onError
    ) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(samplingPattern);
        Objects.requireNonNull(callbackExecutor);
        Objects.requireNonNull(onTileReady);
        Objects.requireNonNull(onSuccess);
        Objects.requireNonNull(onError);

        if (!frame.isComplete()) {
            throw new IllegalArgumentException("Interactive AA requires a completed frame");
        }

        long refinementId = generation.incrementAndGet();
        cancelCurrentFuture();
        currentRefinement = coordinator.submit(() -> runRefinement(
                refinementId,
                frame,
                coloring,
                samplingPattern,
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
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady,
            Runnable onSuccess,
            Consumer<Throwable> onError
    ) {
        try {
            FractalData data = frame.fractalData();
            int[] baseColors = AdaptivePngExportService.colorBaseFrame(data, coloring);
            List<Future<?>> tasks = new ArrayList<>();

            for (RenderRegion tile : orderedTiles(frame)) {
                tasks.add(workers.submit(() -> refineTile(
                        refinementId,
                        frame,
                        coloring,
                        samplingPattern,
                        baseColors,
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
            RenderRegion tile,
            Executor callbackExecutor,
            BiConsumer<RenderRegion, int[]> onTileReady
    ) {
        FractalData data = frame.fractalData();
        RenderGrid grid = frame.renderGrid();
        FractalCalculator calculator = frame.request().calculator();
        int[] tileColors = new int[tile.width() * tile.height()];

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

                tileColors[tileIndex] =
                        AdaptivePngExportService.sampleGridColor(
                                calculator,
                                coloring,
                                grid,
                                frame.request().maxIterations(),
                                x,
                                y,
                                SAMPLE_GRID,
                                samplingPattern
                        );
            }
        }

        if (shouldCancel(refinementId)) {
            return;
        }

        callbackExecutor.execute(() -> {
            if (isCurrent(refinementId)) {
                onTileReady.accept(tile, tileColors);
            }
        });
    }

    private static List<RenderRegion> orderedTiles(RenderFrame frame) {
        List<RenderRegion> tiles = new ArrayList<>();

        for (int y = 0; y < frame.fractalData().height(); y += TILE_SIZE) {
            for (int x = 0; x < frame.fractalData().width(); x += TILE_SIZE) {
                tiles.add(new RenderRegion(
                        x,
                        y,
                        Math.min(TILE_SIZE, frame.fractalData().width() - x),
                        Math.min(TILE_SIZE, frame.fractalData().height() - y)
                ));
            }
        }

        double priorityX = frame.fractalData().width() * frame.request().priority().x();
        double priorityY = frame.fractalData().height() * frame.request().priority().y();
        tiles.sort((first, second) -> Double.compare(
                distanceSquared(first, priorityX, priorityY),
                distanceSquared(second, priorityX, priorityY)
        ));
        return tiles;
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
