package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.GpuJuliaPreviewRequest;
import com.shangin.fractal.gpu.GpuJuliaPreviewResult;
import com.shangin.fractal.gpu.JuliaBrightPointFilter;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.JuliaCpuPreview;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs approximate whole-frame Julia renders on the GPU preview kernel for the
 * Julia GPU mode, and CPU preview frames for Explore Julia Parameters where the GPU
 * cannot render the view. Only the latest request runs; superseded requests are
 * dropped before they start, and a cancelled CPU frame stops early. Results return
 * through the caller's executor (the FX thread), which decides whether they are
 * still current.
 */
final class JuliaGpuRenderer implements AutoCloseable {

    /** One frame to render with the given coloring, on the GPU kernel or, with {@code cpu}, the CPU preview. */
    record Request(long sequence, RenderJob job, ColoringSettings coloring, boolean filterBrightPoints, boolean cpu) {
        Request {
            Objects.requireNonNull(job);
            Objects.requireNonNull(coloring);
        }

        Request(long sequence, RenderJob job, ColoringSettings coloring, boolean filterBrightPoints) {
            this(sequence, job, coloring, filterBrightPoints, false);
        }
    }

    /**
     * Host wall-clock costs of one frame on the worker, for opt-in latency diagnostics.
     * {@code startNanos} is when the worker took the request; {@code gpu} holds the
     * kernel's setup, upload, dispatch (including the bright-point pass) and readback
     * times, and is {@code null} for a CPU frame; {@code callNanos} spans the whole
     * runtime call, including session or kernel creation on first use, or the whole CPU
     * preview; {@code cpuFilterNanos} is the CPU bright-point filter after a GPU frame,
     * zero unless the GPU pass could not run; {@code cpuScale} is a CPU frame's
     * enlargement (zero for the GPU) and {@code cpuSampleNanos} its calculation, coloring
     * and filter before enlargement; {@code readyNanos} is when the result was offered
     * to the result executor.
     */
    record FrameTiming(GpuJuliaPreviewResult gpu, long startNanos, long callNanos, long cpuFilterNanos,
                       int cpuScale, long cpuSampleNanos, long readyNanos) {
        FrameTiming(GpuJuliaPreviewResult gpu, long startNanos, long callNanos, long cpuFilterNanos,
                    long readyNanos) {
            this(gpu, startNanos, callNanos, cpuFilterNanos, 0, 0, readyNanos);
        }
    }

    /** Receives the outcome on the result executor. Colors must be returned with {@link #release}. */
    interface Listener {
        void rendered(Request request, int[] colors, FrameTiming timing);
        void unavailable(Request request);
        void failed(Request request, Throwable error);
    }

    /** Width and height of the warm-up frame: enough for both passes, negligible to render. */
    static final int WARM_UP_SIZE = 16;

    /**
     * Target cost of a CPU preview frame's samples (calculation, coloring and filter):
     * the coarsest-needed scale keeps it within this, about 40 frames per second.
     */
    static final long CPU_FRAME_BUDGET_NANOS = 25_000_000;

    /** Assumed cost per sample before the first CPU frame has been measured. */
    static final double INITIAL_CPU_NANOS_PER_SAMPLE = 40;

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
    private final JuliaCpuPreview cpuPreview;
    /** Incremented by {@link #cancelPending}: a CPU frame submitted before it stops. */
    private final AtomicLong cancellations = new AtomicLong();
    /** Worker only: measured cost per sample of the latest CPU frame. */
    private double cpuNanosPerSample = INITIAL_CPU_NANOS_PER_SAMPLE;
    private volatile boolean closed;
    /** Set on the worker once the kernel has run, by a frame or the warm-up. */
    private volatile boolean warm;
    /** FX thread only: the warm-up is queued at most once per renderer. */
    private boolean warmUpRequested;
    /** True while a request may be using the runtime; paired with {@link #closed} for close. */
    private volatile boolean running;

    /** {@code cancellation} is {@link #cancellations} at submission: a later cancelPending cancels it. */
    private record Pending(Request request, Listener listener, long cancellation) {}

    JuliaGpuRenderer(FractalRenderService renderService, Executor resultExecutor) {
        this(renderService, resultExecutor, new JuliaCpuPreview());
    }

    JuliaGpuRenderer(FractalRenderService renderService, Executor resultExecutor, JuliaCpuPreview cpuPreview) {
        this.renderService = Objects.requireNonNull(renderService);
        this.resultExecutor = Objects.requireNonNull(resultExecutor);
        this.cpuPreview = Objects.requireNonNull(cpuPreview);
    }

    /**
     * Whether the CPU preview accepts this job and coloring: a Julia view within direct
     * double precision with smooth palette coloring, as the GPU kernel requires. Side-effect free.
     */
    boolean supportsCpu(RenderJob job, ColoringSettings coloring) {
        return cpuPreview.supports(job) && strategy(coloring).isPresent();
    }

    /** Whether the GPU kernel accepts this job and coloring; side-effect free and allocation-light. */
    boolean supports(RenderJob job, ColoringSettings coloring) {
        return job.formula().preset() == FractalPreset.JULIA
                && renderService.isJuliaPreviewAvailable()
                && strategy(coloring).filter(smooth -> GpuJuliaPreviewRequest.supports(job,
                        RenderGrid.from(job.viewport(), job.width(), job.height()), smooth)).isPresent();
    }

    /** Replaces any request that has not started yet. */
    void submit(Request request, Listener listener) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(listener);
        if (closed) return;
        latest.set(new Pending(request, listener, cancellations.get()));
        worker.execute(this::runLatest);
    }

    /**
     * Whether {@link #warmUp} would still queue work: false once the warm-up was requested
     * or the renderer closed. A frame that already ran makes the queued warm-up a no-op.
     * FX thread only.
     */
    boolean needsWarmUp() {
        return !closed && !warmUpRequested && !warm;
    }

    /**
     * Queues, at most once, a small filtered frame cut from the center of this job's grid,
     * with its formula, limit and coloring, and discards its colors. It compiles the
     * kernel's shaders and creates its pipelines before the first real frame needs them.
     * The worker skips it only when a frame already ran; a frame submitted meanwhile waits
     * for it, as it would wait for kernel creation. Unsupported jobs queue nothing.
     * FX thread only.
     */
    void warmUp(RenderJob job, ColoringSettings coloring) {
        Objects.requireNonNull(job);
        Objects.requireNonNull(coloring);
        if (!needsWarmUp() || !supports(job, coloring)) return;
        int width = Math.min(WARM_UP_SIZE, job.width());
        int height = Math.min(WARM_UP_SIZE, job.height());
        RenderGrid frame = RenderGrid.from(job.viewport(), job.width(), job.height());
        // Pixels of the frame's own grid: the precision gate accepts them whenever it accepts the frame.
        RenderGrid crop = new RenderGrid(frame.originReal(), frame.originImaginary(), frame.realStep(),
                frame.imaginaryStep(), frame.offsetX() + (job.width() - width) / 2,
                frame.offsetY() + (job.height() - height) / 2, frame.conjugateHeight());
        RenderJob small = new RenderJob(job.formula(), job.viewport(), width, height, job.maxIterations());
        warmUpRequested = true;
        GpuJuliaPreviewRequest request = new GpuJuliaPreviewRequest(small, crop,
                strategy(coloring).orElseThrow(), new int[width * height], true);
        worker.execute(() -> runWarmUp(request));
    }

    private void runWarmUp(GpuJuliaPreviewRequest request) {
        running = true;
        try {
            if (closed || warm) return;
            if (renderService.warmUpJuliaPreview(request)) warm = true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            // The first real frame repeats this work and reports its failure to its listener.
            System.getLogger(JuliaGpuRenderer.class.getName()).log(System.Logger.Level.WARNING,
                    "Julia GPU kernel warm-up failed", error);
        } finally {
            running = false;
        }
    }

    /**
     * Drops the request that has not started yet and stops a running CPU frame; a running
     * native submission completes safely.
     */
    void cancelPending() {
        cancellations.incrementAndGet();
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
            if (pending == null) return;
            if (pending.request().cpu()) runCpu(pending);
            else run(pending);
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
            if (result.isPresent()) warm = true;
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

    private void runCpu(Pending pending) {
        Request request = pending.request();
        Listener listener = pending.listener();
        long startNanos = System.nanoTime();
        int[] colors = null;
        try {
            ColoringStrategy coloring = strategy(request.coloring()).orElseThrow(
                    () -> new IllegalArgumentException("The Julia CPU preview requires smooth palette coloring"));
            RenderJob job = request.job();
            colors = acquire(job.width() * job.height());
            int scale = cpuScale(job.width(), job.height(), cpuNanosPerSample);
            long callStart = System.nanoTime();
            JuliaCpuPreview.Frame frame = cpuPreview.render(job, scale, coloring, request.filterBrightPoints(),
                    colors, () -> closed || cancellations.get() != pending.cancellation());
            long callNanos = System.nanoTime() - callStart;
            if (frame == null) {
                release(colors);
                return;
            }
            cpuNanosPerSample = Math.max(1, (double) frame.sampleNanos() / (frame.width() * frame.height()));
            int[] rendered = colors;
            colors = null;
            var timing = new FrameTiming(null, startNanos, callNanos, 0, frame.scale(), frame.sampleNanos(),
                    System.nanoTime());
            resultExecutor.execute(() -> listener.rendered(request, rendered, timing));
        } catch (InterruptedException interrupted) {
            release(colors);
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            release(colors);
            resultExecutor.execute(() -> listener.failed(request, error));
        }
    }

    /**
     * The finest scale whose samples are predicted to cost at most
     * {@link #CPU_FRAME_BUDGET_NANOS} at {@code nanosPerSample}, never coarser than
     * {@link JuliaCpuPreview#MAX_SCALE}.
     */
    static int cpuScale(int width, int height, double nanosPerSample) {
        for (int scale = 1; scale < JuliaCpuPreview.MAX_SCALE; scale++) {
            double samples = (double) JuliaCpuPreview.coarseLength(width, scale)
                    * JuliaCpuPreview.coarseLength(height, scale);
            if (samples * nanosPerSample <= CPU_FRAME_BUDGET_NANOS) return scale;
        }
        return JuliaCpuPreview.MAX_SCALE;
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
        // The CPU preview's workers are idle once no request runs: a running CPU frame sees closed and stops.
        Runnable afterWork = () -> {
            try {
                cpuPreview.close();
            } finally {
                afterNativeWork.run();
            }
        };
        // Volatile closed/running form a handshake: a request either sees closed and
        // returns, or close sees it running and defers the callback.
        if (!running) {
            afterWork.run();
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
                afterWork.run();
            }
        }, "fractal-julia-gpu-close");
        // Holds the JVM open: the callback closes the service and may still persist frames.
        closer.setDaemon(false);
        closer.start();
    }
}
