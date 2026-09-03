package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Opt-in base pass: certify GPU samples and recover rejected pixels at original CPU coordinates. */
public final class GpuMandelbrotRenderBackend implements RenderBackend {
    private static final int REGION_SIZE = 128;
    private final GpuRuntime runtime;
    private final RenderBackend cpu;
    private final Object renderLock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final ThreadPoolExecutor gpuWorker;
    private volatile boolean closed;
    private volatile CalculationStats lastStats = new CalculationStats(0, 0, 0, false);

    private final boolean profiling = Boolean.getBoolean("fractal.gpu.mandelbrot.profile");
    private volatile Profile lastProfile = new Profile(0, 0, 0, 0, -1, 0, 0, 0);

    /** Component sums overlap; do not add them to estimate frame wall time. */
    public record Profile(long packNanos, long uploadNanos, long dispatchNanos, long readbackNanos,
                          long kernelNanos, long recoverPublishNanos, long batches, long nativeBytes) {}
    public Profile lastProfile() { return lastProfile; }

    public record CalculationStats(long dispatchedPixels, long certifiedPixels, long recoveredPixels, boolean cpuFallback) {}

    public GpuMandelbrotRenderBackend(GpuRuntime runtime, RenderBackend cpu) {
        this.runtime = Objects.requireNonNull(runtime);
        this.cpu = Objects.requireNonNull(cpu);
        gpuWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "fractal-gpu-calculation");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public boolean supports(RenderJob job) { return cpu.supports(job); }
    public CalculationStats lastStats() { return lastStats; }

    @Override
    public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled, Consumer<RenderRegion> progress,
                              Consumer<TileTimingStats> timing) throws InterruptedException {
        Objects.requireNonNull(frame); Objects.requireNonNull(cancelled); Objects.requireNonNull(progress);
        long id = generation.incrementAndGet();
        BooleanSupplier stop = () -> closed || id != generation.get() || cancelled.getAsBoolean()
                || Thread.currentThread().isInterrupted();
        synchronized (renderLock) {
            checkCancelled(stop);
            long[] totals = new long[3];
            long[] profile = new long[8];
            profile[4] = -1;
            lastProfile = new Profile(0, 0, 0, 0, -1, 0, 0, 0);
            if (!eligible(frame) || !runtime.isUsableFor(GpuNumericCapability.FLOAT32)) {
                lastStats = new CalculationStats(0, 0, 0, true);
                return cpu.render(frame, stop, progress, timing);
            }
            var regions = ParallelFractalCalculator.orderedRegions(frame.job(), REGION_SIZE).iterator();
            MandelbrotBatch first = new MandelbrotBatch(), second = new MandelbrotBatch();
            long packing = profiling ? System.nanoTime() : 0;
            Work current = prepare(frame, regions, first, stop);
            if (profiling) profile[0] += System.nanoTime() - packing;
            Future<Boolean> pending = null;
            List<Long> elapsed = new ArrayList<>();
            try {
                if (current != null) pending = submit(current.batch, stop);
                while (current != null) {
                    checkCancelled(stop);
                    long started = System.nanoTime();
                    boolean gpuUsed = await(pending);
                    pending = null;
                    checkCancelled(stop);
                    if (!gpuUsed) {
                        lastStats = new CalculationStats(totals[0], totals[1], totals[2], true);
                        // No samples from the failed batch have entered the frame. Previously certified
                        // regions and pan overlap stay valid; CPU completes the original remaining job.
                        return cpu.render(frame, stop, progress, timing);
                    }
                    totals[0] += current.batch.count;
                    if (profiling && current.batch.timing != null) {
                        MandelbrotTiming t = current.batch.timing;
                        profile[1] += t.uploadNanos(); profile[2] += t.dispatchNanos();
                        profile[3] += t.readbackNanos();
                        if (t.kernelNanos() >= 0) profile[4] = Math.max(0, profile[4]) + t.kernelNanos();
                        profile[6]++; profile[7] = Math.max(profile[7], t.nativeBytes());
                    }
                    packing = profiling ? System.nanoTime() : 0;
                    Work next = prepare(frame, regions, second, stop);
                    if (profiling) profile[0] += System.nanoTime() - packing;
                    if (next != null) pending = submit(next.batch, stop);
                    long recovery = profiling ? System.nanoTime() : 0;
                    recoverAndPublish(frame, current, stop, progress, totals);
                    if (profiling) profile[5] += System.nanoTime() - recovery;
                    if (timing != null) elapsed.add(System.nanoTime() - started);
                    second = current.batch;
                    current = next;
                }
                checkCancelled(stop);
                lastStats = new CalculationStats(totals[0], totals[1], totals[2], false);
                if (timing != null) timing.accept(timing(elapsed));
                return frame;
            } finally {
                if (profiling) lastProfile = new Profile(profile[0], profile[1], profile[2], profile[3],
                        profile[4], profile[5], profile[6], profile[7]);
                if (pending != null) pending.cancel(true);
                gpuWorker.purge();
            }
        }
    }

    private static boolean eligible(RenderFrame frame) {
        RenderJob job = frame.job();
        return job.sampleAccuracy() == SampleAccuracy.CERTIFIED_FP32
                && job.formula().preset() == FractalPreset.MANDELBROT
                && job.formula().orbitTrap() == OrbitTrap.NONE
                && job.formula().parameters().isEmpty()
                && job.maxIterations() <= 1000
                && job.viewport().hasSufficientPrecision(job.width(), job.height(), 16)
                && MandelbrotPrecisionGate.supportsGrid(frame.renderGrid(), job.width(), job.height());
    }

    private Future<Boolean> submit(MandelbrotBatch batch, BooleanSupplier stop) throws InterruptedException {
        checkCancelled(stop);
        gpuWorker.purge();
        try {
            return gpuWorker.submit(() -> {
                checkCancelled(stop);
                boolean used = runtime.calculateMandelbrot(batch);
                checkCancelled(stop);
                return used;
            });
        } catch (RejectedExecutionException failure) {
            checkCancelled(stop);
            throw failure;
        }
    }

    private static boolean await(Future<Boolean> pending) throws InterruptedException {
        try { return pending.get(); }
        catch (CancellationException e) { throw new InterruptedException("GPU generation cancelled"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException cancelled) throw cancelled;
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException("GPU calculation failed", e.getCause());
        }
    }

    private static Work prepare(RenderFrame frame, Iterator<RenderRegion> regions, MandelbrotBatch batch,
                                BooleanSupplier stop) throws InterruptedException {
        while (regions.hasNext()) {
            checkCancelled(stop);
            RenderRegion region = regions.next();
            List<RenderRegion> spans = frame.validity().missingRowSpans(region);
            if (spans.isEmpty()) continue;
            batch.count = 0;
            batch.maxIterations = frame.job().maxIterations();
            for (RenderRegion span : spans) {
                checkCancelled(stop);
                for (int x = span.x(); x < span.x() + span.width(); x++) {
                    int i = batch.count++;
                    batch.pixels[i] = span.y() * frame.job().width() + x;
                    MandelbrotPrecisionGate.pack(batch.input, i, frame.renderGrid().realAt(x), frame.renderGrid().imaginaryAt(span.y()));
                }
            }
            return new Work(batch, region);
        }
        return null;
    }

    private static void recoverAndPublish(RenderFrame frame, Work work, BooleanSupplier stop,
                                         Consumer<RenderRegion> progress, long[] totals) throws InterruptedException {
        var calculator = frame.job().formula().createDirectCalculator();
        MandelbrotBatch batch = work.batch;
        FractalSample[] samples = new FractalSample[batch.count];
        // Stage the entire region so cancellation cannot mark partly recovered data as ready.
        for (int i = 0; i < batch.count; i++) {
            if ((i & 31) == 0) checkCancelled(stop);
            if (MandelbrotPrecisionGate.accepts(batch.output, i, batch.maxIterations, true)) {
                samples[i] = MandelbrotPrecisionGate.sample(batch.output, i);
                totals[1]++;
            } else {
                int pixel = batch.pixels[i];
                samples[i] = calculator.calculateSample(frame.renderGrid().realAt(pixel % frame.job().width()),
                        frame.renderGrid().imaginaryAt(pixel / frame.job().width()), batch.maxIterations);
                totals[2]++;
            }
        }
        checkCancelled(stop);
        for (int i = 0; i < batch.count; i++) frame.samplePlane().set(batch.pixels[i], samples[i]);
        checkCancelled(stop);
        frame.validity().markReady(work.region);
        checkCancelled(stop);
        progress.accept(work.region);
    }

    private static void checkCancelled(BooleanSupplier stop) throws InterruptedException {
        if (stop.getAsBoolean()) throw new InterruptedException("GPU render cancelled");
    }

    private static TileTimingStats timing(List<Long> values) {
        if (values.isEmpty()) return new TileTimingStats(0, 0, 0, 0);
        values.sort(Long::compare);
        int n = values.size();
        double median = n % 2 == 1 ? values.get(n / 2)
                : (values.get(n / 2 - 1).doubleValue() + values.get(n / 2)) / 2;
        return new TileTimingStats(n, values.getFirst() / 1e6, median / 1e6, values.getLast() / 1e6);
    }

    @Override public void close() {
        closed = true;
        generation.incrementAndGet();
        for (Runnable queued : gpuWorker.shutdownNow()) {
            if (queued instanceof Future<?> future) future.cancel(false);
        }
        cpu.close();
    }

    private record Work(MandelbrotBatch batch, RenderRegion region) {}
}
