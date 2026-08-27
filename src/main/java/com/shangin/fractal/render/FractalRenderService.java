package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class FractalRenderService implements AutoCloseable {

    private final ExecutorService coordinator;
    private final ParallelFractalCalculator parallelCalculator;
    private final AtomicLong generation = new AtomicLong();
    private Future<?> currentRender;

    private static final long PROGRESS_INTERVAL_MS = 16;

    private final ScheduledExecutorService progressScheduler;

    public FractalRenderService() {
        coordinator = Executors.newSingleThreadExecutor(daemonThreadFactory("fractal-render"));
        progressScheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("fractal-progress"));
        parallelCalculator = new ParallelFractalCalculator();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();

        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());

            thread.setDaemon(true);

            return thread;
        };
    }

    public synchronized void render(
            RenderFrame frame,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            Consumer<RenderFrame> onSuccess,
            Consumer<Throwable> onError
    ) {
        long renderId = generation.incrementAndGet();

        cancelCurrentFuture();

        currentRender = coordinator.submit(() ->
                        executeRender(
                                renderId,
                                frame,
                                callbackExecutor,
                                onProgress,
                                onSuccess,
                                onError
                        )
                );
    }

    public synchronized void cancelCurrent() {
        generation.incrementAndGet();

        cancelCurrentFuture();
    }

    private void executeRender(
            long renderId,
            RenderFrame frame,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            Consumer<RenderFrame> onSuccess,
            Consumer<Throwable> onError
    ) {
        ProgressBatcher progressBatcher =
                new ProgressBatcher(
                        renderId,
                        frame,
                        callbackExecutor,
                        onProgress
                );

        try {
            RenderFrame resultFrame =
                    parallelCalculator.calculate(
                            frame,
                            () -> shouldCancel(renderId),
                            progressBatcher::add
                    );

            if (shouldCancel(renderId)) {
                progressBatcher.cancel();
                return;
            }

            if (!resultFrame.isComplete()) {
                throw new IllegalStateException(
                        "Completed render contains invalid pixels"
                );
            }

            progressBatcher.finish(() -> onSuccess.accept(resultFrame));

        } catch (InterruptedException e) {
            progressBatcher.cancel();

            Thread.currentThread().interrupt();

        } catch (RuntimeException e) {
            progressBatcher.cancel();

            if (shouldCancel(renderId)) {
                return;
            }

            callbackExecutor.execute(() -> {
                if (isCurrent(renderId)) {
                    onError.accept(e);
                }
            });
        }
    }

    private void cancelCurrentFuture() {
        Future<?> render = currentRender;
        currentRender = null;

        if (render != null && !render.isDone()) {
            render.cancel(true);
        }
    }

    private boolean isCurrent(long renderId) {
        return renderId == generation.get();
    }

    private boolean shouldCancel(long renderId) {
        return !isCurrent(renderId) || Thread.currentThread().isInterrupted();
    }

    @Override
    public synchronized void close() {
        generation.incrementAndGet();

        cancelCurrentFuture();

        coordinator.shutdownNow();
        progressScheduler.shutdownNow();

        parallelCalculator.close();
    }

    private final class ProgressBatcher {

        private final long renderId;
        private final Executor callbackExecutor;
        private final Consumer<RenderProgressBatch> onProgress;

        private final ConcurrentLinkedQueue<RenderRegion> pendingRegions =
                new ConcurrentLinkedQueue<>();

        private final AtomicBoolean flushScheduled =
                new AtomicBoolean();

        private final RenderFrame renderFrame;

        private boolean finished;

        private ProgressBatcher(
                long renderId,
                RenderFrame renderFrame,
                Executor callbackExecutor,
                Consumer<RenderProgressBatch> onProgress
        ) {
            this.renderId = renderId;
            this.renderFrame = Objects.requireNonNull(renderFrame);
            this.callbackExecutor = callbackExecutor;
            this.onProgress = onProgress;
        }

        void add(RenderRegion region) {
            if (shouldCancel(renderId)) {
                return;
            }

            pendingRegions.add(region);

            scheduleFlush();
        }

        private void scheduleFlush() {
            if (!flushScheduled.compareAndSet(
                    false,
                    true
            )) {
                return;
            }

            progressScheduler.schedule(
                    this::flush,
                    PROGRESS_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );
        }

        private synchronized void flush() {
            flushScheduled.set(false);

            if (finished || !isCurrent(renderId)) {
                pendingRegions.clear();
                return;
            }

            List<RenderRegion> regions =
                    drainRegions();

            if (!regions.isEmpty()) {
                dispatchProgress(regions);
            }

            if (!pendingRegions.isEmpty()) {
                scheduleFlush();
            }
        }

        private void dispatchProgress(
                List<RenderRegion> regions
        ) {
            RenderProgressBatch progress =
                    new RenderProgressBatch(
                            renderFrame,
                            regions
                    );

            callbackExecutor.execute(() -> {
                if (isCurrent(renderId)) {
                    onProgress.accept(progress);
                }
            });
        }

        private List<RenderRegion> drainRegions() {
            List<RenderRegion> regions =
                    new ArrayList<>();

            RenderRegion region;

            while ((region = pendingRegions.poll()) != null) {
                regions.add(region);
            }

            return regions;
        }

        synchronized void finish(Runnable onFinished) {
            if (finished) {
                return;
            }

            finished = true;

            List<RenderRegion> regions = drainRegions();

            callbackExecutor.execute(() -> {
                if (!isCurrent(renderId)) {
                    return;
                }

                if (!regions.isEmpty()) {
                    onProgress.accept(
                            new RenderProgressBatch(
                                    renderFrame,
                                    regions
                            )
                    );
                }
                onFinished.run();
            });
        }

        synchronized void cancel() {
            finished = true;
            pendingRegions.clear();
        }
    }

}