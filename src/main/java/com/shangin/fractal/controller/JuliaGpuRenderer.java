package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.GpuJuliaPreviewRequest;
import com.shangin.fractal.gpu.GpuJuliaPreviewResult;
import com.shangin.fractal.gpu.JuliaBrightPointFilter;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.scene.ColoringSettings;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs approximate whole-frame Julia renders on the GPU preview kernel for the
 * Julia GPU mode. Only the latest request runs; superseded requests are dropped
 * before they reach the GPU. Results return through the caller's executor (the
 * FX thread), which decides whether they are still current.
 */
final class JuliaGpuRenderer implements AutoCloseable {

    /** One frame to render with the given coloring. */
    record Request(long sequence, RenderJob job, ColoringSettings coloring, boolean filterBrightPoints) {
        Request {
            Objects.requireNonNull(job);
            Objects.requireNonNull(coloring);
        }
    }

    /**
     * Host wall-clock costs of one frame on the worker, for opt-in latency diagnostics.
     * {@code startNanos} is when the worker took the request; {@code gpu} holds the
     * kernel's setup, upload, dispatch (including the bright-point pass) and readback
     * times; {@code callNanos} spans the whole runtime call, including session or
     * kernel creation on first use; {@code cpuFilterNanos} is the CPU bright-point
     * filter, zero unless the GPU pass could not run; {@code readyNanos} is when the
     * result was offered to the result executor.
     */
    record FrameTiming(GpuJuliaPreviewResult gpu, long startNanos, long callNanos, long cpuFilterNanos,
                       long readyNanos) {}

    /** Receives the outcome on the result executor. Colors must be returned with {@link #release}. */
    interface Listener {
        void rendered(Request request, int[] colors, FrameTiming timing);
        void unavailable(Request request);
        void failed(Request request, Throwable error);
    }

    private final FractalRenderService renderService;
    private final Executor resultExecutor;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "fractal-julia-gpu");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicReference<Pending> latest = new AtomicReference<>();
    private final ConcurrentLinkedDeque<int[]> buffers = new ConcurrentLinkedDeque<>();
    private volatile boolean closed;
    /** True while a request may be using the runtime; paired with {@link #closed} for close. */
    private volatile boolean running;

    private record Pending(Request request, Listener listener) {}

    JuliaGpuRenderer(FractalRenderService renderService, Executor resultExecutor) {
        this.renderService = Objects.requireNonNull(renderService);
        this.resultExecutor = Objects.requireNonNull(resultExecutor);
    }

    /** Whether the GPU kernel accepts this job and coloring; side-effect free and allocation-light. */
    boolean supports(RenderJob job, ColoringSettings coloring) {
        return job.formula().preset() == FractalPreset.JULIA
                && strategy(coloring).filter(smooth -> GpuJuliaPreviewRequest.supports(job,
                        RenderGrid.from(job.viewport(), job.width(), job.height()), smooth)).isPresent()
                && renderService.isJuliaPreviewAvailable();
    }

    /** Replaces any request that has not started yet. */
    void submit(Request request, Listener listener) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(listener);
        if (closed) return;
        latest.set(new Pending(request, listener));
        worker.execute(this::runLatest);
    }

    /** Drops the request that has not started yet; a running native submission completes safely. */
    void cancelPending() {
        latest.set(null);
    }

    /** Returns a color buffer received by {@link Listener#rendered} for reuse. */
    void release(int[] colors) {
        if (colors != null && !closed && buffers.size() < 2) buffers.offerFirst(colors);
    }

    private void runLatest() {
        running = true;
        try {
            if (closed) return;
            Pending pending = latest.getAndSet(null);
            if (pending != null) run(pending);
        } finally {
            running = false;
        }
    }

    private void run(Pending pending) {
        Request request = pending.request();
        Listener listener = pending.listener();
        long startNanos = System.nanoTime();
        int[] colors = null;
        try {
            colors = acquire(request.job().width() * request.job().height());
            SmoothPaletteColoring coloring = strategy(request.coloring()).orElseThrow(
                    () -> new IllegalArgumentException("Julia GPU requires smooth palette coloring"));
            long callStart = System.nanoTime();
            Optional<GpuJuliaPreviewResult> result = renderService.renderJuliaPreview(new GpuJuliaPreviewRequest(
                    request.job(), RenderGrid.from(request.job().viewport(), request.job().width(),
                    request.job().height()), coloring, colors, request.filterBrightPoints()));
            long callNanos = System.nanoTime() - callStart;
            if (result.isEmpty()) {
                release(colors);
                resultExecutor.execute(() -> listener.unavailable(request));
                return;
            }
            long cpuFilterNanos = 0;
            if (request.filterBrightPoints() && !result.get().brightPointsFiltered()) {
                // The GPU pass did not fit the memory budget; apply the same filter here.
                long filterStart = System.nanoTime();
                int[] filtered = acquire(colors.length);
                JuliaBrightPointFilter.apply(colors, filtered, request.job().width(), request.job().height());
                release(colors);
                colors = filtered;
                cpuFilterNanos = System.nanoTime() - filterStart;
            }
            int[] rendered = colors;
            colors = null;
            var timing = new FrameTiming(result.get(), startNanos, callNanos, cpuFilterNanos, System.nanoTime());
            resultExecutor.execute(() -> listener.rendered(request, rendered, timing));
        } catch (InterruptedException interrupted) {
            release(colors);
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            release(colors);
            resultExecutor.execute(() -> listener.failed(request, error));
        }
    }

    private int[] acquire(int size) {
        int[] buffer;
        while ((buffer = buffers.pollFirst()) != null) {
            if (buffer.length == size) return buffer;
        }
        return new int[size];
    }

    private static Optional<SmoothPaletteColoring> strategy(ColoringSettings coloring) {
        if (coloring.histogramColoring()) return Optional.empty();
        ColoringStrategy strategy = coloring.createStrategy();
        return strategy instanceof SmoothPaletteColoring smooth ? Optional.of(smooth) : Optional.empty();
    }

    /** Closes without waiting; equivalent to {@code close(() -> {})}. */
    @Override
    public void close() {
        close(() -> {});
    }

    /**
     * Stops accepting work without waiting on the caller's thread. {@code afterNativeWork}
     * (for example closing the runtime) runs once no request can use the runtime any more:
     * at once when none is running, otherwise on a background thread after the running
     * native submission drains. That thread is not a daemon, so the JVM stays alive until
     * the callback (for example a persistent-frame write) finishes. Runs it at most once.
     */
    void close(Runnable afterNativeWork) {
        Objects.requireNonNull(afterNativeWork);
        if (closed) return;
        closed = true;
        latest.set(null);
        buffers.clear();
        worker.shutdown();
        // Volatile closed/running form a handshake: a request either sees closed and
        // returns, or close sees it running and defers the callback.
        if (!running) {
            afterNativeWork.run();
            return;
        }
        Thread closer = new Thread(() -> {
            try {
                // A native fence wait is not interruptible; keep waiting for it to drain.
                boolean drained = false;
                while (!drained) drained = worker.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                afterNativeWork.run();
            }
        }, "fractal-julia-gpu-close");
        // Holds the JVM open: the callback closes the service and may still persist frames.
        closer.setDaemon(false);
        closer.start();
    }
}
