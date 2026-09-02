package com.shangin.fractal.gpu;

import org.junit.jupiter.api.Test;

import java.util.List;
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
    }
}
