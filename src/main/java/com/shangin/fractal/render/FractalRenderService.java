package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
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
            RenderRequest request,
            Executor callbackExecutor,
            Consumer<FractalData> onSuccess,
            Consumer<Throwable> onError
    ) {
        long renderId = generation.incrementAndGet();

        cancelCurrentFuture();
        currentRender = coordinator.submit(() ->
                executeRender(
                        renderId,
                        request,
                        callbackExecutor,
                        onSuccess,
                        onError));
    }

    public synchronized void render(
            RenderRequest request,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            Consumer<FractalData> onSuccess,
            Consumer<Throwable> onError
    ) {
        long renderId = generation.incrementAndGet();

        cancelCurrentFuture();

        currentRender = coordinator.submit(() ->
                        executeRender(
                                renderId,
                                request,
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
            RenderRequest request,
            Executor callbackExecutor,
            Consumer<FractalData> onSuccess,
            Consumer<Throwable> onError
    ) {
        try {
            FractalData data = parallelCalculator.calculate(
                            request,
                            () -> shouldCancel(renderId));

            if (data == null || shouldCancel(renderId)) {
                return;
            }

            callbackExecutor.execute(() -> {
                if (isCurrent(renderId)) {
                    onSuccess.accept(data);
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } catch (RuntimeException e) {
            if (shouldCancel(renderId)) {
                return;
            }
            callbackExecutor.execute(
                    () -> onError.accept(e)
            );
        }
    }

    private void executeRender(
            long renderId,
            RenderRequest request,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            Consumer<FractalData> onSuccess,
            Consumer<Throwable> onError
    ) {
        ProgressBatcher progressBatcher =
                new ProgressBatcher(
                        renderId,
                        callbackExecutor,
                        onProgress
                );

        try {
            FractalData data =
                    parallelCalculator.calculate(
                            request,
                            () -> shouldCancel(renderId),
                            progressBatcher::add
                    );

            if (data == null || shouldCancel(renderId)) {
                progressBatcher.cancel();
                return;
            }

            progressBatcher.finish(
                    data,
                    () -> onSuccess.accept(data)
            );

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

        private volatile FractalData data;

        private boolean finished;

        private ProgressBatcher(
                long renderId,
                Executor callbackExecutor,
                Consumer<RenderProgressBatch> onProgress
        ) {
            this.renderId = renderId;
            this.callbackExecutor = callbackExecutor;
            this.onProgress = onProgress;
        }

        void add(
                FractalData data,
                RenderRegion region
        ) {
            if (shouldCancel(renderId)) {
                return;
            }

            this.data = data;
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
                dispatchProgress(
                        data,
                        regions
                );
            }

            if (!pendingRegions.isEmpty()) {
                scheduleFlush();
            }
        }

        private void dispatchProgress(
                FractalData data,
                List<RenderRegion> regions
        ) {
            RenderProgressBatch progress =
                    new RenderProgressBatch(
                            data,
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

        synchronized void finish(
                FractalData data,
                Runnable onFinished
        ) {
            if (finished) {
                return;
            }

            finished = true;

            List<RenderRegion> regions =
                    drainRegions();

            callbackExecutor.execute(() -> {
                if (!isCurrent(renderId)) {
                    return;
                }

                if (!regions.isEmpty()) {
                    onProgress.accept(
                            new RenderProgressBatch(
                                    data,
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