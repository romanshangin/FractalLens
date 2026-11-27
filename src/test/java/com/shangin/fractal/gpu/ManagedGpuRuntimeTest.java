package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.render.RenderPriority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.shangin.fractal.gpu.GpuNumericCapability.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedGpuRuntimeTest {
    private static final GpuDevice FLOAT_DEVICE = device("fp32", Set.of(FLOAT32));
    private static final GpuDevice DOUBLE_DEVICE = device("fp64", Set.of(FLOAT32, FLOAT64));

    @Test
    void gatesWorkOnTheSelectedDeviceRatherThanAnyEnumeratedDevice() throws Exception {
        FakeSession session = new FakeSession();
        try (GpuRuntime runtime = open(session)) {
            assertTrue(runtime.isUsableFor(FLOAT32));
            assertFalse(runtime.isUsableFor(FLOAT64));
            assertEquals("cpu", runtime.runOrFallback(FLOAT64, () -> fail("wrong GPU"), () -> "cpu"));
            assertEquals("gpu", runtime.runOrFallback(FLOAT32, () -> "gpu", () -> fail("wrong CPU")));
        }
        assertEquals(1, session.closes);
    }

    @Test
    void deviceLossReleasesResourcesAndFallsBackWithoutRetryingGpu() throws Exception {
        FakeSession session = new FakeSession();
        GpuRuntime runtime = open(session);
        assertEquals("cpu", runtime.runOrFallback(FLOAT32,
                () -> { throw new GpuException("device lost", true); }, () -> "cpu"));
        assertEquals(GpuRuntimeState.DEVICE_LOST, runtime.capabilityReport().state());
        assertTrue(session.closedAsLost);
        assertFalse(runtime.checkHealth());
        assertEquals("cpu", runtime.runOrFallback(FLOAT32, () -> fail("GPU retried"), () -> "cpu"));
        runtime.close();
        runtime.close();
        runtime.handleDeviceLoss(new Exception("late loss"));
        assertEquals(GpuRuntimeState.CLOSED, runtime.capabilityReport().state());
        assertEquals(1, session.closes);
    }

    @Test
    void nativeFailureDisablesGpuButDoesNotPretendThatTheDeviceWasLost() throws Exception {
        FakeSession session = new FakeSession();
        try (GpuRuntime runtime = open(session)) {
            assertEquals(42, runtime.runOrFallback(FLOAT32,
                    () -> { throw new GpuException("allocation failed", false); }, () -> 42));
            assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
            assertFalse(session.closedAsLost);
        }
        assertEquals(1, session.closes);
    }

    @Test
    void healthFailureIsMappedToDeviceLoss() {
        FakeSession session = new FakeSession();
        try (GpuRuntime runtime = open(session)) {
            session.healthFailure = new GpuException("queue lost", true);
            assertFalse(runtime.checkHealth());
            assertEquals(GpuRuntimeState.DEVICE_LOST, runtime.capabilityReport().state());
            assertEquals(1, session.closes);
        }
    }

    @Test
    void initializationFailureRollsBackAnOpenedSession() {
        FakeSession session = new FakeSession();
        session.healthFailure = new GpuException("initial health check failed", false);
        try (GpuRuntime runtime = open(session)) {
            assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
            assertTrue(runtime.capabilityReport().detail().contains("initial health check failed"));
        }
        assertEquals(1, session.closes);
    }

    @Test
    void missingNativeLibraryLeavesCpuAvailable() throws Exception {
        try (GpuRuntime runtime = ManagedGpuRuntime.open(GpuPlatform.MACOS,
                () -> { throw new UnsatisfiedLinkError("missing native"); })) {
            assertEquals("cpu", runtime.runOrFallback(FLOAT32, () -> fail("GPU used"), () -> "cpu"));
            assertTrue(runtime.capabilityReport().detail().contains("missing native"));
        }
    }

    @Test
    void residentKernelFailureReturnsEmptyAndDisablesTheExperimentalPath() throws Exception {
        FakeSession session = new FakeSession();
        try (GpuRuntime runtime = open(session)) {
            RenderJob job = new RenderJob(
                    FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                    FractalPreset.MANDELBROT.defaultViewport(), 8, 6, 40,
                    RenderPriority.center(), Optional.empty());
            var request = new GpuResidentMandelbrotRequest(
                    job, RenderGrid.from(job.viewport(), 8, 6),
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()), new int[48]);

            assertTrue(runtime.renderResidentMandelbrot(request).isEmpty());
            assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
            assertTrue(runtime.capabilityReport().detail().contains("Resident Mandelbrot spike unavailable"));
        }
        assertEquals(1, session.closes);
    }

    @Test
    void availabilitySnapshotNeverWaitsForNativeWorkInProgress() throws Exception {
        FakeSession session = new FakeSession();
        CountDownLatch working = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (GpuRuntime runtime = open(session); var worker = Executors.newSingleThreadExecutor()) {
            var work = worker.submit(() -> runtime.runOrFallback(FLOAT32, () -> {
                working.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return "gpu";
            }, () -> "cpu"));
            assertTrue(working.await(5, TimeUnit.SECONDS));
            var probe = Executors.newSingleThreadExecutor();
            try {
                // The monitor is held by the running work; the snapshot must still answer.
                assertTrue(probe.submit(() -> runtime.isUsableSnapshotFor(FLOAT32)).get(2, TimeUnit.SECONDS));
                assertFalse(probe.submit(() -> runtime.isUsableSnapshotFor(FLOAT64)).get(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                probe.shutdownNow();
            }
            assertEquals("gpu", work.get(5, TimeUnit.SECONDS));
            runtime.handleDeviceLoss(new GpuException("lost", true));
            assertFalse(runtime.isUsableSnapshotFor(FLOAT32));
        }
    }

    @Test
    void cancellationAndProgrammingErrorsAreNotSwallowedAsGpuFailures() throws Exception {
        try (GpuRuntime runtime = open(new FakeSession())) {
            assertThrows(InterruptedException.class, () -> runtime.runOrFallback(FLOAT32,
                    () -> { throw new InterruptedException(); }, () -> fail("fallback after cancellation")));
            assertThrows(IllegalArgumentException.class, () -> runtime.runOrFallback(FLOAT32,
                    () -> { throw new IllegalArgumentException(); }, () -> fail("hidden programming error")));
            assertTrue(runtime.checkHealth());
        }
    }

    @Test
    void cleanupErrorDoesNotSuppressCpuFallbackAndIsReported() throws Exception {
        FakeSession session = new FakeSession();
        session.failClose = true;
        try (GpuRuntime runtime = open(session)) {
            assertEquals("cpu", runtime.runOrFallback(FLOAT32,
                    () -> { throw new GpuException("lost", true); }, () -> "cpu"));
            assertTrue(runtime.capabilityReport().detail().contains("cleanup failed"));
        }
        assertEquals(1, session.closes);
    }

    @Test
    void cannotDestroyResourcesFromInsideTheOperationUsingThem() throws Exception {
        FakeSession session = new FakeSession();
        try (GpuRuntime runtime = open(session)) {
            runtime.runOrFallback(FLOAT32, () -> {
                assertThrows(IllegalStateException.class, runtime::close);
                assertEquals(0, session.closes);
                return "gpu";
            }, () -> fail("fallback"));
        }
        assertEquals(1, session.closes);
    }

    @Test
    void closeWaitsForAnActiveOperationBeforeDestroyingItsResources() throws Exception {
        FakeSession session = new FakeSession();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicBoolean closeFinished = new AtomicBoolean();
        try (GpuRuntime runtime = open(session); var executor = Executors.newFixedThreadPool(2)) {
            var work = executor.submit(() -> runtime.runOrFallback(FLOAT32, () -> {
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                assertEquals(0, session.closes);
                return "gpu";
            }, () -> fail("fallback")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = executor.submit(() -> {
                closeStarted.countDown();
                runtime.close();
                closeFinished.set(true);
            });
            try {
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                assertFalse(closeFinished.get());
            } finally {
                finish.countDown();
            }
            assertEquals("gpu", work.get(5, TimeUnit.SECONDS));
            closing.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, session.closes);
    }

    @Test
    void juliaWarmUpRunsTheKernelWithoutChangingTheRuntimeOnAnOrdinaryFailure() throws Exception {
        FakeSession session = new FakeSession();
        try (GpuRuntime runtime = open(session)) {
            assertTrue(runtime.warmUpJuliaPreview(juliaRequest()));
            assertEquals(1, session.juliaRenders);

            session.juliaFailure = new GpuException("shader compilation failed", false);
            assertFalse(runtime.warmUpJuliaPreview(juliaRequest()));
            // The first real frame reports such a failure; the warm-up leaves the runtime available.
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
            assertEquals(0, session.closes);
            assertTrue(runtime.renderJuliaPreview(juliaRequest()).isEmpty());
            assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
            assertFalse(runtime.warmUpJuliaPreview(juliaRequest()));
            assertEquals(3, session.juliaRenders);
        }
    }

    @Test
    void juliaWarmUpDisablesTheRuntimeWhenTheDeviceIsLost() throws Exception {
        FakeSession session = new FakeSession();
        GpuRuntime runtime = open(session);
        session.juliaFailure = new GpuException("device lost", true);
        assertFalse(runtime.warmUpJuliaPreview(juliaRequest()));
        assertEquals(GpuRuntimeState.DEVICE_LOST, runtime.capabilityReport().state());
        assertTrue(session.closedAsLost);
        runtime.close();
        assertEquals(1, session.closes);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> runtime.warmUpJuliaPreview(juliaRequest()));
        } finally {
            Thread.interrupted();
        }
    }

    private static GpuJuliaPreviewRequest juliaRequest() {
        var view = new com.shangin.fractal.math.Viewport(0, 0, 2.4);
        RenderJob job = new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE), view, 4, 4, 100);
        return new GpuJuliaPreviewRequest(job, RenderGrid.from(view, 4, 4),
                new SmoothPaletteColoring(PalettePreset.ICE.palette()), new int[16], true);
    }

    private static GpuRuntime open(FakeSession session) {
        return ManagedGpuRuntime.open(GpuPlatform.MACOS, () -> session);
    }

    private static GpuDevice device(String name, Set<GpuNumericCapability> capabilities) {
        return new GpuDevice(name, "1.1.0", 1, 1, 0, 256, 1_048_576, capabilities);
    }

    private static final class FakeSession implements GpuSession {
        int closes;
        boolean closedAsLost;
        boolean failClose;
        GpuException healthFailure;
        GpuException juliaFailure;
        int juliaRenders;

        public List<GpuDevice> devices() { return List.of(FLOAT_DEVICE, DOUBLE_DEVICE); }
        public GpuDevice selectedDevice() { return FLOAT_DEVICE; }
        public void checkHealth() {
            if (healthFailure != null) throw healthFailure;
        }
        public void close(boolean lost) {
            closes++;
            closedAsLost = lost;
            if (failClose) throw new IllegalStateException("cleanup failed");
        }
        @Override public GpuJuliaPreviewResult renderJuliaPreview(GpuJuliaPreviewRequest request) {
            juliaRenders++;
            if (juliaFailure != null) throw juliaFailure;
            return new GpuJuliaPreviewResult(request.colors(), 0, 0, 0, 0, 0, 0, request.filterBrightPoints());
        }
    }
}
