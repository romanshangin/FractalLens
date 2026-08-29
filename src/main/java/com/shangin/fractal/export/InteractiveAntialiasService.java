package com.shangin.fractal.export;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalData;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.scene.SamplingPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs a cancellable, edge-only antialiasing pass after the interactive base
 * frame is already visible. Navigation never waits for this refinement.
 */
public final class InteractiveAntialiasService implements AutoCloseable {

    private static final int SAMPLE_GRID = 4;
    private static final int ROWS_PER_TASK = 8;

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
            Consumer<int[]> onSuccess,
            Consumer<Throwable> onError
    ) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(samplingPattern);
        Objects.requireNonNull(callbackExecutor);
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
            Consumer<int[]> onSuccess,
            Consumer<Throwable> onError
    ) {
        try {
            FractalData data = frame.fractalData();
            int[] baseColors = AdaptivePngExportService.colorBaseFrame(data, coloring);
            int[] refinedColors = baseColors.clone();
            List<Future<?>> tasks = new ArrayList<>();

            for (int y = 0; y < data.height(); y += ROWS_PER_TASK) {
                int yFrom = y;
                int yTo = Math.min(y + ROWS_PER_TASK, data.height());
                tasks.add(workers.submit(() -> refineRows(
                        refinementId,
                        frame,
                        coloring,
                        samplingPattern,
                        baseColors,
                        refinedColors,
                        yFrom,
                        yTo
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
                    onSuccess.accept(refinedColors);
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

    private void refineRows(
            long refinementId,
            RenderFrame frame,
            ColoringStrategy coloring,
            SamplingPattern samplingPattern,
            int[] baseColors,
            int[] refinedColors,
            int yFrom,
            int yTo
    ) {
        FractalData data = frame.fractalData();
        RenderGrid grid = frame.renderGrid();
        FractalCalculator calculator = frame.request().calculator();

        for (int y = yFrom; y < yTo; y++) {
            if (shouldCancel(refinementId)) {
                throw new CancellationException();
            }

            for (int x = 0; x < data.width(); x++) {
                if ((x & 15) == 0 && shouldCancel(refinementId)) {
                    throw new CancellationException();
                }

                if (!AdaptivePngExportService.isBaseEdge(data, baseColors, x, y)) {
                    continue;
                }

                refinedColors[y * data.width() + x] =
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
