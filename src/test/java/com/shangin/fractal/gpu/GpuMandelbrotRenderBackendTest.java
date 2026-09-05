package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class GpuMandelbrotRenderBackendTest {
    @Test
    void recoversRejectedSamplesAtOriginalCoordinatesAndPublishesCompleteRegions() throws Exception {
        var session = new RejectingSession();
        RenderFrame frame = RenderFrame.create(job(new Viewport(-0.743643887037151, 0.13182590420533, 0.024), 257, 129));
        List<RenderRegion> published = new ArrayList<>();
        try (GpuRuntime runtime = runtime(session); var backend = backend(runtime)) {
            backend.render(frame, () -> false, region -> {
                assertTrue(frame.validity().isRegionReady(region));
                published.add(region);
            }, stats -> assertEquals(2, stats.tileCount()));
            assertTrue(frame.isComplete());
            assertEquals(2, published.size());
            assertEquals(frame.samplePlane().size(), session.pixels);
            assertEquals(frame.samplePlane().size(), backend.lastStats().recoveredPixels());
            assertEquals(0, backend.lastStats().certifiedPixels());
            assertFalse(backend.lastStats().cpuFallback());
            assertEquals(2 * MandelbrotStaging.bytesPerBatch(), backend.lastProfile().stagingBytes());
            assertMatchesCpu(frame, 0);
        }
    }

    @Test
    void keepsPanOverlapAndSkipsFullyCachedFrame() throws Exception {
        var session = new RejectingSession();
        RenderFrame source = RenderFrame.create(job(new Viewport(-0.75, 0, 2.4), 257, 129));
        try (GpuRuntime runtime = runtime(session); var backend = backend(runtime)) {
            backend.render(source, () -> false, ignored -> {}, null);
            session.pixels = 0;
            Viewport nextView = source.job().viewport().shiftedByPixels(5, -3, 257, 129);
            RenderFrame next = new FrameReusePlanner().createFrame(source, job(nextView, 257, 129));
            int reused = next.validity().readyPixelCount();
            assertTrue(reused > 0);
            backend.render(next, () -> false, ignored -> {}, null);
            assertEquals(next.samplePlane().size() - reused, session.pixels);
            assertMatchesCpu(next, 0);
            session.pixels = 0;
            backend.render(next, () -> false, ignored -> fail("Cached frame published new work"), null);
            assertEquals(0, session.pixels);
        }
    }

    @Test
    void deviceLossAfterPartialProgressCompletesOnlyMissingPixelsOnCpu() throws Exception {
        var session = new RejectingSession();
        session.failAt = 2;
        var frame = RenderFrame.create(job(new Viewport(-0.75, 0, 2.4), 257, 129));
        try (GpuRuntime runtime = runtime(session); var backend = backend(runtime)) {
            backend.render(frame, () -> false, region -> assertTrue(frame.validity().isRegionReady(region)), null);
            assertTrue(frame.isComplete());
            assertEquals(GpuRuntimeState.DEVICE_LOST, runtime.capabilityReport().state());
            assertTrue(session.closedAsLost);
            assertEquals(2, session.calls.get());
            assertTrue(backend.lastStats().cpuFallback());
            assertMatchesCpu(frame, 0);
        }
    }

    @Test
    void unsupportedAccuracyFormulaAndPrecisionRetainCpuPolicy() throws Exception {
        var session = new RejectingSession();
        try (GpuRuntime runtime = runtime(session); var backend = backend(runtime)) {
            var normal = new Viewport(-0.75, 0, 2.4);
            List<RenderJob> jobs = List.of(
                    new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE), normal, 12, 8, 300),
                    new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE), normal, 12, 8, 300,
                            RenderPriority.center(), Optional.empty(), SampleAccuracy.CERTIFIED_FP32),
                    new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.CROSS), normal, 12, 8, 300,
                            RenderPriority.center(), Optional.empty(), SampleAccuracy.CERTIFIED_FP32),
                    job(new Viewport("-0.743643887037151", "0.13182590420533", "1e-50"), 12, 8));
            for (RenderJob job : jobs) {
                var frame = RenderFrame.create(job);
                backend.render(frame, () -> false, ignored -> {}, null);
                assertTrue(frame.isComplete());
                assertTrue(backend.lastStats().cpuFallback());
                assertEquals(0, backend.lastProfile().stagingBytes());
            }
            assertEquals(0, session.calls.get());
        }
    }

    @Test
    void cancellationDuringReadbackPublishesNothingAndDoesNotDisableRuntime() throws Exception {
        var session = new RejectingSession();
        session.blockFirst = true;
        var frame = RenderFrame.create(job(new Viewport(-0.75, 0, 2.4), 257, 129));
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger published = new AtomicInteger();
        try (GpuRuntime runtime = runtime(session); var backend = backend(runtime); var caller = Executors.newSingleThreadExecutor()) {
            Future<?> rendering = caller.submit(() -> {
                assertThrows(InterruptedException.class, () -> backend.render(frame, cancelled::get,
                        ignored -> published.incrementAndGet(), null));
            });
            assertTrue(session.started.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            session.release.countDown();
            rendering.get(5, TimeUnit.SECONDS);
            assertEquals(0, published.get());
            assertEquals(0, frame.validity().readyPixelCount());
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
        } finally { session.release.countDown(); }
    }

    @Test
    void cancellationDuringParallelRecoveryDrainsWorkersAndPublishesNothing() throws Exception {
        var session = new RejectingSession();
        var frame = RenderFrame.create(job(new Viewport(-0.75, 0, 2.4), 128, 128));
        AtomicInteger hostChecks = new AtomicInteger();
        try (GpuRuntime runtime = runtime(session); var backend = new GpuMandelbrotRenderBackend(runtime,
                new DirectDoubleRenderBackend(), 4)) {
            BooleanSupplier cancelled = () -> session.completed
                    && Thread.currentThread().getName().startsWith("fractal-gpu-host")
                    && hostChecks.incrementAndGet() > 4;
            assertThrows(InterruptedException.class,
                    () -> backend.render(frame, cancelled, ignored -> fail("Cancelled region was published"), null));
            assertEquals(0, frame.validity().readyPixelCount());
            assertTrue(hostChecks.get() > 4);
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
        }
    }

    @Test
    void replacementGenerationSuppressesStaleCallbacksAndCompletesNewFrame() throws Exception {
        var session = new RejectingSession();
        session.blockFirst = true;
        GpuRuntime runtime = runtime(session);
        var backend = backend(runtime);
        AtomicInteger stale = new AtomicInteger();
        CompletableFuture<RenderFrame> result = new CompletableFuture<>();
        try (var service = new FractalRenderService(backend, runtime)) {
            var first = RenderFrame.create(job(new Viewport(-0.75, 0, 2.4), 257, 129));
            service.render(first, Runnable::run, ignored -> stale.incrementAndGet(), ignored -> stale.incrementAndGet(), result::completeExceptionally);
            assertTrue(session.started.await(5, TimeUnit.SECONDS));
            var second = RenderFrame.create(job(new Viewport(1, 1, 0.2), 129, 65));
            service.render(second, Runnable::run, ignored -> {}, result::complete, result::completeExceptionally);
            session.release.countDown();
            assertSame(second, result.get(5, TimeUnit.SECONDS));
            assertEquals(0, stale.get());
            assertTrue(second.isComplete());
        } finally { session.release.countDown(); }
    }

    @Test
    void sampleAccuracySeparatesCacheAndPanReuse() throws Exception {
        var job = job(new Viewport(-0.75, 0, 2.4), 12, 8);
        var certified = RenderFrame.create(job);
        try (var cpu = new DirectDoubleRenderBackend()) { cpu.render(certified, () -> false, ignored -> {}, null); }
        var reference = new RenderJob(job.formula(), job.viewport(), job.width(), job.height(), job.maxIterations());
        var cache = new RenderFrameCache();
        cache.put(certified);
        assertTrue(cache.findExact(reference).isEmpty());
        assertSame(certified, cache.findExact(job).orElseThrow());
        assertEquals(0, new FrameReusePlanner().createFrame(certified, reference).validity().readyPixelCount());
    }

    @Test
    void boundsHostParallelismAndRegionStagingConfiguration() {
        var session = new RejectingSession();
        try (GpuRuntime runtime = runtime(session); var cpu = new DirectDoubleRenderBackend()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GpuMandelbrotRenderBackend(runtime, cpu, 0, 192));
            assertThrows(IllegalArgumentException.class,
                    () -> new GpuMandelbrotRenderBackend(runtime, cpu, 4, 193));
            assertThrows(IllegalArgumentException.class,
                    () -> new GpuMandelbrotRenderBackend(runtime, cpu, 4, 31));
        }
    }

    static RenderJob job(Viewport view, int width, int height) {
        return new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE), view, width, height, 300,
                RenderPriority.center(), Optional.empty(), SampleAccuracy.CERTIFIED_FP32);
    }

    static GpuMandelbrotRenderBackend backend(GpuRuntime runtime) {
        return new GpuMandelbrotRenderBackend(runtime, new PrecisionSelectingRenderBackend(
                new DirectDoubleRenderBackend(), new MandelbrotPerturbationRenderBackend()));
    }

    static void assertMatchesCpu(RenderFrame frame, double tolerance) {
        var calculator = frame.job().formula().createDirectCalculator();
        for (int i = 0; i < frame.samplePlane().size(); i++) {
            var expected = calculator.calculateSample(frame.renderGrid().realAt(i % frame.job().width()),
                    frame.renderGrid().imaginaryAt(i / frame.job().width()), frame.job().maxIterations());
            assertEquals(expected.escaped(), frame.samplePlane().escaped(i), "escape at " + i);
            assertEquals(expected.iterations(), frame.samplePlane().iterations(i), "iteration at " + i);
            assertEquals(expected.smoothIterations(), frame.samplePlane().smoothIterations(i), tolerance, "smooth at " + i);
        }
    }

    private static GpuRuntime runtime(RejectingSession session) { return ManagedGpuRuntime.open(GpuPlatform.MACOS, () -> session); }

    private static final class RejectingSession implements GpuSession {
        private final GpuDevice device = new GpuDevice("fake", "1.1", 0, 0, 0, 1024, 1L << 30, Set.of(GpuNumericCapability.FLOAT32));
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        int failAt, pixels;
        volatile boolean completed;
        boolean blockFirst, closedAsLost;
        public List<GpuDevice> devices() { return List.of(device); }
        public GpuDevice selectedDevice() { return device; }
        public void checkHealth() {}
        public void close(boolean lost) { closedAsLost = lost; }
        public void calculateMandelbrot(MandelbrotBatch batch) throws InterruptedException {
            int call = calls.incrementAndGet();
            started.countDown();
            if (blockFirst && call == 1) assertTrue(release.await(5, TimeUnit.SECONDS));
            if (call == failAt) throw new GpuException("injected loss", true);
            pixels += batch.count;
            // Deliberately invalid raw output: only full CPU recovery may make any pixel ready.
            Arrays.fill(batch.output, -1);
            completed = true;
        }
    }
}
