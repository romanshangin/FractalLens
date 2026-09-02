package com.shangin.fractal.render;

import com.shangin.fractal.gpu.GpuCapabilityReport;
import com.shangin.fractal.gpu.GpuRuntime;
import com.shangin.fractal.gpu.GpuRuntimeFactory;
import com.shangin.fractal.gpu.PaletteRecolorBackend;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Owns the asynchronous render lifecycle, cancels superseded generations, and
 * batches worker progress before dispatching callbacks on a caller-provided executor.
 */
public final class FractalRenderService implements AutoCloseable {

    private final ExecutorService coordinator;
    private final RenderBackend backend;
    private final GpuRuntime gpuRuntime;
    private final AtomicLong generation = new AtomicLong();
    private Future<?> currentRender;

    private static final long PROGRESS_INTERVAL_MS = 16;

    private final ScheduledExecutorService progressScheduler;

    public FractalRenderService() {
        this(new PrecisionSelectingRenderBackend(
                new DirectDoubleRenderBackend(),
                new MandelbrotPerturbationRenderBackend()),
                GpuRuntimeFactory.createDefault());
    }

    public FractalRenderService(RenderBackend backend) {
        this(backend, GpuRuntimeFactory.createDefault());
    }

    /**
     * The runtime is owned here rather than by the controller or JavaFX surface.
     * Until a GPU calculation backend exists, all render requests stay on CPU.
     */
    public FractalRenderService(RenderBackend backend, GpuRuntime gpuRuntime) {
        this(backend, gpuRuntime,
                Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("fractal-progress")));
    }

    FractalRenderService(RenderBackend backend, ScheduledExecutorService progressScheduler) {
        this(backend, GpuRuntimeFactory.createDefault(), progressScheduler);
    }

    private FractalRenderService(
            RenderBackend backend,
            GpuRuntime gpuRuntime,
            ScheduledExecutorService progressScheduler
    ) {
        coordinator = Executors.newSingleThreadExecutor(daemonThreadFactory("fractal-render"));
        this.progressScheduler = Objects.requireNonNull(progressScheduler);
        this.backend = Objects.requireNonNull(backend);
        this.gpuRuntime = Objects.requireNonNull(gpuRuntime);
    }

    /** Safe diagnostic snapshot for UI/logging; contains no native handles. */
    public GpuCapabilityReport gpuCapabilityReport() {
        return gpuRuntime.capabilityReport();
    }

    /** Shares the runtime-owned palette backend without exposing native handles. */
    public PaletteRecolorBackend paletteRecolorBackend() {
        return new PaletteRecolorBackend(gpuRuntime);
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();

        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());

            thread.setDaemon(true);

            return thread;
        };
    }

    /** Starts a render without collecting diagnostic timing information. */
    public void render(
            RenderFrame frame,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            Consumer<RenderFrame> onSuccess,
            Consumer<Throwable> onError
    ) {
        submitRender(
                frame,
                callbackExecutor,
                onProgress,
                null,
                null,
                onSuccess,
                onError
        );
    }

    /** Starts a render and reports calculation and per-tile timings. */
    public synchronized void render(
            RenderFrame frame,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            LongConsumer onCalculationComplete,
            Consumer<TileTimingStats> onTileTimingComplete,
            Consumer<RenderFrame> onSuccess,
            Consumer<Throwable> onError
    ) {
        Objects.requireNonNull(onCalculationComplete);
        Objects.requireNonNull(onTileTimingComplete);

        submitRender(
                frame,
                callbackExecutor,
                onProgress,
                onCalculationComplete,
                onTileTimingComplete,
                onSuccess,
                onError
        );
    }

    private synchronized void submitRender(
            RenderFrame frame,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            LongConsumer onCalculationComplete,
            Consumer<TileTimingStats> onTileTimingComplete,
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
                                onCalculationComplete,
                                onTileTimingComplete,
                                onSuccess,
                                onError
                        )
                );
    }

    /** Cancels the active generation and suppresses any pending callbacks. */
    public synchronized void cancelCurrent() {
        generation.incrementAndGet();

        cancelCurrentFuture();
    }

    private void executeRender(
            long renderId,
            RenderFrame frame,
            Executor callbackExecutor,
            Consumer<RenderProgressBatch> onProgress,
            LongConsumer onCalculationComplete,
            Consumer<TileTimingStats> onTileTimingComplete,
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

            long calculationStart = onCalculationComplete == null
                    ? 0L
                    : System.nanoTime();

            RenderFrame resultFrame;

            if (onTileTimingComplete == null) {
                resultFrame = backend.render(frame, () -> shouldCancel(renderId),
                        progressBatcher::add, null);
            } else {
                resultFrame = backend.render(frame, () -> shouldCancel(renderId),
                        progressBatcher::add, onTileTimingComplete);
            }

            if (shouldCancel(renderId)) {
                progressBatcher.cancel();
                return;
            }

            if (!resultFrame.isComplete()) {
                throw new IllegalStateException(
                        "Completed render contains invalid pixels"
                );
            }

            if (onCalculationComplete != null) {
                onCalculationComplete.accept(
                        System.nanoTime() - calculationStart
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

        try {
            backend.close();
        } finally {
            gpuRuntime.close();
        }
    }

    private final class ProgressBatcher {

        private final long renderId;
        private final Executor callbackExecutor;
        private final Consumer<RenderProgressBatch> onProgress;

        private final ConcurrentLinkedQueue<RenderRegion> pendingRegions =
                new ConcurrentLinkedQueue<>();

        private final AtomicBoolean flushScheduled =
                new AtomicBoolean();

        private final AtomicBoolean firstFlush = new AtomicBoolean(true);

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
                    // Do not hold the first ready pixels for a batching interval.
                    firstFlush.getAndSet(false) ? 0L : PROGRESS_INTERVAL_MS,
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
