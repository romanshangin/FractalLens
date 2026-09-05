package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Opt-in base pass: certify GPU samples and recover rejected pixels at original CPU coordinates. */
public final class GpuMandelbrotRenderBackend implements RenderBackend {
    private static final int DEFAULT_REGION_SIZE = 192;
    private final GpuRuntime runtime;
    private final RenderBackend cpu;
    private final Object renderLock = new Object();
    private final AtomicLong generation = new AtomicLong();
    private final ThreadPoolExecutor gpuWorker;
    private final ThreadPoolExecutor hostWorkers;
    private final int hostWorkerCount;
    private final int regionSize;
    private volatile boolean closed;
    private volatile CalculationStats lastStats = new CalculationStats(0, 0, 0, false);

    private final boolean profiling = Boolean.getBoolean("fractal.gpu.mandelbrot.profile");
    private volatile Profile lastProfile = Profile.empty(0, 0);

    /** Component sums overlap; do not add them to estimate frame wall time. */
    public record Profile(long packNanos, long uploadNanos, long dispatchNanos, long readbackNanos,
                          long kernelNanos, long recoverPublishNanos, long certificationNanos,
                          long recoveryNanos, long publicationNanos, long batches, long nativeBytes,
                          long stagingBytes, int hostWorkers, int regionSize) {
        public static Profile empty(int hostWorkers, int regionSize) {
            return new Profile(0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0,
                    0, hostWorkers, regionSize);
        }
    }
    public Profile lastProfile() { return lastProfile; }

    public record CalculationStats(long dispatchedPixels, long certifiedPixels, long recoveredPixels, boolean cpuFallback) {}

    public GpuMandelbrotRenderBackend(GpuRuntime runtime, RenderBackend cpu) {
        this(runtime, cpu, defaultHostWorkerCount(), configuredRegionSize());
    }

    GpuMandelbrotRenderBackend(GpuRuntime runtime, RenderBackend cpu, int hostWorkerCount) {
        this(runtime, cpu, hostWorkerCount, configuredRegionSize());
    }

    GpuMandelbrotRenderBackend(GpuRuntime runtime, RenderBackend cpu, int hostWorkerCount, int regionSize) {
        this.runtime = Objects.requireNonNull(runtime);
        this.cpu = Objects.requireNonNull(cpu);
        if (hostWorkerCount < 1 || hostWorkerCount > 64) {
            throw new IllegalArgumentException("Host worker count must be in [1, 64]");
        }
        this.hostWorkerCount = hostWorkerCount;
        if (regionSize < 32 || regionSize > DEFAULT_REGION_SIZE
                || (long) regionSize * regionSize > VulkanMandelbrotKernel.CAPACITY) {
            throw new IllegalArgumentException("GPU region size must be in [32, " + DEFAULT_REGION_SIZE + "]");
        }
        this.regionSize = regionSize;
        gpuWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, "fractal-gpu-calculation");
            thread.setDaemon(true);
            return thread;
        });
        hostWorkers = new ThreadPoolExecutor(hostWorkerCount, hostWorkerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(hostWorkerCount), daemonThreadFactory("fractal-gpu-host"));
    }

    private static int defaultHostWorkerCount() {
        int configured = Integer.getInteger("fractal.gpu.mandelbrot.hostWorkers", 0);
        if (configured != 0) return configured;
        return Math.min(10, Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
    }

    private static int configuredRegionSize() {
        return Integer.getInteger("fractal.gpu.mandelbrot.regionSize", DEFAULT_REGION_SIZE);
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
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
            long[] profile = new long[11];
            profile[4] = -1;
            lastProfile = Profile.empty(hostWorkerCount, regionSize);
            if (!eligible(frame) || !runtime.isUsableFor(GpuNumericCapability.FLOAT32)) {
                lastStats = new CalculationStats(0, 0, 0, true);
                return cpu.render(frame, stop, progress, timing);
            }
            lastProfile = configuredProfile();
            var regions = ParallelFractalCalculator.orderedRegions(frame.job(), regionSize).iterator();
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
                        profile[9]++; profile[10] = Math.max(profile[10], t.nativeBytes());
                    }
                    packing = profiling ? System.nanoTime() : 0;
                    Work next = prepare(frame, regions, second, stop);
                    if (profiling) profile[0] += System.nanoTime() - packing;
                    if (next != null) pending = submit(next.batch, stop);
                    long recovery = profiling ? System.nanoTime() : 0;
                    HostStages stages = recoverAndPublish(frame, current, stop, progress, totals);
                    if (profiling) profile[5] += System.nanoTime() - recovery;
                    if (profiling) {
                        profile[6] += stages.certificationNanos;
                        profile[7] += stages.recoveryNanos;
                        profile[8] += stages.publicationNanos;
                    }
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
                        profile[4], profile[5], profile[6], profile[7], profile[8], profile[9], profile[10],
                        2 * MandelbrotStaging.bytesPerBatch(), hostWorkerCount, regionSize);
                if (pending != null) pending.cancel(true);
                gpuWorker.purge();
            }
        }
    }

    private Profile configuredProfile() {
        return new Profile(0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0,
                2 * MandelbrotStaging.bytesPerBatch(), hostWorkerCount, regionSize);
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

    private HostStages recoverAndPublish(RenderFrame frame, Work work, BooleanSupplier stop,
                                         Consumer<RenderRegion> progress, long[] totals) throws InterruptedException {
        var calculator = frame.job().formula().createDirectCalculator();
        MandelbrotBatch batch = work.batch;
        MandelbrotStaging staging = batch.staging;
        // Stage the entire region so cancellation cannot mark partly recovered data as ready.
        long started = profiling ? System.nanoTime() : 0;
        long certified = parallel(batch.count, stop, (from, to) -> {
            long accepted = 0;
            for (int i = from; i < to; i++) {
                if ((i & 31) == 0) checkCancelled(stop);
                if (MandelbrotPrecisionGate.certify(batch.output, i, batch.maxIterations, true, staging)) {
                    accepted++;
                }
            }
            return accepted;
        });
        long certifiedAt = profiling ? System.nanoTime() : 0;
        long recovered = batch.count - certified;
        if (recovered > 0) {
            long completed = parallel(batch.count, stop, (from, to) -> {
                long count = 0;
                for (int i = from; i < to; i++) {
                    if ((i & 31) == 0) checkCancelled(stop);
                    if (!staging.rejected(i)) continue;
                    int pixel = batch.pixels[i];
                    FractalSample sample = calculator.calculateSample(
                            frame.renderGrid().realAt(pixel % frame.job().width()),
                            frame.renderGrid().imaginaryAt(pixel / frame.job().width()), batch.maxIterations);
                    staging.recover(i, sample.iterations(), sample.smoothIterations(), sample.escaped());
                    count++;
                }
                return count;
            });
            if (completed != recovered) throw new IllegalStateException("Incomplete GPU recovery staging");
        }
        long recoveredAt = profiling ? System.nanoTime() : 0;
        checkCancelled(stop);
        for (int i = 0; i < batch.count; i++) {
            frame.samplePlane().setValues(batch.pixels[i], staging.iterations[i], staging.smoothIterations[i],
                    staging.escaped(i), Double.NaN);
        }
        checkCancelled(stop);
        frame.validity().markReady(work.region);
        checkCancelled(stop);
        progress.accept(work.region);
        totals[1] += certified;
        totals[2] += recovered;
        long publishedAt = profiling ? System.nanoTime() : 0;
        return profiling ? new HostStages(certifiedAt - started, recoveredAt - certifiedAt,
                publishedAt - recoveredAt) : HostStages.NONE;
    }

    private long parallel(int count, BooleanSupplier stop, RangeTask task) throws InterruptedException {
        int tasks = Math.min(hostWorkerCount, Math.max(1, (count + 511) / 512));
        int chunk = (count + tasks - 1) / tasks;
        CountDownLatch drained = new CountDownLatch(tasks);
        List<Future<Long>> futures = new ArrayList<>(tasks);
        try {
            for (int taskIndex = 0; taskIndex < tasks; taskIndex++) {
                int from = taskIndex * chunk;
                int to = Math.min(count, from + chunk);
                try {
                    DrainingTask future = new DrainingTask(() -> task.run(from, to), drained);
                    hostWorkers.execute(future);
                    futures.add(future);
                } catch (RuntimeException failure) {
                    for (int unsubmitted = taskIndex; unsubmitted < tasks; unsubmitted++) drained.countDown();
                    throw failure;
                }
            }
            long total = 0;
            for (Future<Long> future : futures) total += awaitHost(future);
            return total;
        } catch (InterruptedException | RuntimeException | Error failure) {
            futures.forEach(future -> future.cancel(true));
            awaitDrain(drained);
            throw failure;
        }
    }

    private static long awaitHost(Future<Long> future) throws InterruptedException {
        try {
            return future.get();
        } catch (CancellationException e) {
            throw new InterruptedException("GPU host stage cancelled");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof InterruptedException cancelled) throw cancelled;
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            if (e.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException("GPU host stage failed", e.getCause());
        }
    }

    private static void awaitDrain(CountDownLatch drained) {
        boolean interrupted = false;
        while (drained.getCount() != 0) {
            try {
                drained.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
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
        hostWorkers.shutdownNow();
        cpu.close();
    }

    private record Work(MandelbrotBatch batch, RenderRegion region) {}
    private record HostStages(long certificationNanos, long recoveryNanos, long publicationNanos) {
        private static final HostStages NONE = new HostStages(0, 0, 0);
    }
    @FunctionalInterface private interface RangeTask {
        long run(int from, int to) throws InterruptedException;
    }

    /** Counts queued cancellation immediately, but running work only after its callable exits. */
    private static final class DrainingTask extends FutureTask<Long> {
        private final CountDownLatch drained;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean accounted = new AtomicBoolean();

        DrainingTask(Callable<Long> callable, CountDownLatch drained) {
            super(callable);
            this.drained = drained;
        }

        @Override public void run() {
            if (!started.compareAndSet(false, true)) return;
            try {
                super.run();
            } finally {
                account();
            }
        }

        @Override protected void done() {
            if (!started.get()) account();
        }

        private void account() {
            if (accounted.compareAndSet(false, true)) drained.countDown();
        }
    }
}
