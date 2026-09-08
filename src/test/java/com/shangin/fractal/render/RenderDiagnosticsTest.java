package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RenderDiagnosticsTest {
    private interface TestBackend extends RenderBackend { @Override default void close() {} }
    private String previous;
    @BeforeEach void enable() {
        previous = System.getProperty(RenderDiagnostics.PROPERTY);
        System.setProperty(RenderDiagnostics.PROPERTY, "true");
    }
    @AfterEach void restore() {
        if (previous == null) System.clearProperty(RenderDiagnostics.PROPERTY);
        else System.setProperty(RenderDiagnostics.PROPERTY, previous);
    }
    static RenderFrame frame() {
        var preset = FractalPreset.MANDELBROT;
        return RenderFrame.create(new RenderRequest(new FractalCalculator(preset.createFormula()),
                preset.defaultViewport(), 64, 48, 100));
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test void cancellationDoesNotReportDrainUntilRunningWorkersExit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var backendExit = new CountDownLatch(1);
        var workers = RenderDiagnostics.workerPool(1, Executors.defaultThreadFactory());
        var snapshots = new LinkedBlockingQueue<RenderDiagnostics>();
        var callbacks = new AtomicInteger();
        TestBackend backend = (frame, cancelled, progress, timing) -> {
            try {
                workers.invokeAll(List.of(() -> {
                    entered.countDown();
                    awaitIgnoringInterrupt(release);
                    cancelled.getAsBoolean();
                    return null;
                }, () -> { fail("Queued task must not execute"); return null; }));
                return frame;
            } finally { backendExit.countDown(); }
        };
        try (var service = new FractalRenderService(backend)) {
            service.setDiagnosticsListener(snapshots::add);
            service.render(frame(), Runnable::run, p -> callbacks.incrementAndGet(),
                    f -> callbacks.incrementAndGet(), e -> callbacks.incrementAndGet());
            var diagnostics = snapshots.poll(5, TimeUnit.SECONDS);
            assertNotNull(diagnostics);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.cancelCurrent();
            assertTrue(backendExit.await(5, TimeUnit.SECONDS));
            assertFalse(diagnostics.completion().isDone(), "Future cancellation is not worker exit");
            release.countDown();
            var snapshot = diagnostics.completion().get(5, TimeUnit.SECONDS);
            var v = snapshot.values();
            assertEquals(2, v.get("worker_tasks_registered"));
            assertEquals(1, v.get("worker_tasks_started"));
            assertEquals(1, v.get("worker_tasks_cancelled_before_start"));
            assertEquals(2, v.get("worker_tasks_terminal"));
            assertTrue(v.get("request_drained") >= v.get("last_worker_exit"));
            assertTrue(v.get("last_worker_exit") >= v.get("cancel_requested"));
            assertTrue(v.get("cancel_observed") >= v.get("cancel_requested"));
            assertEquals(0, callbacks.get(), "Cancelled generation must not publish");
            assertThrows(UnsupportedOperationException.class, () -> v.put("bad", 1L));
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void queuedCoordinatorCancellationAndReplacementKeepIndependentGenerations() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new LinkedBlockingQueue<RenderDiagnostics>();
        var calls = new AtomicInteger();
        TestBackend backend = (frame, cancelled, progress, timing) -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); awaitIgnoringInterrupt(release); }
            return frame;
        };
        try (var service = new FractalRenderService(backend)) {
            service.setDiagnosticsListener(requests::add);
            service.render(frame(), Runnable::run, p -> {}, f -> {}, e -> {});
            var first = requests.take();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.render(frame(), Runnable::run, p -> {}, f -> {}, e -> {});
            var queued = requests.take();
            service.cancelCurrent();
            var skipped = queued.completion().get(5, TimeUnit.SECONDS);
            assertTrue(skipped.values().containsKey("coordinator_cancelled_before_start"));
            assertFalse(skipped.values().containsKey("backend_start"));
            assertEquals(0, skipped.values().get("worker_tasks_terminal"));
            assertFalse(first.completion().isDone());
            release.countDown();
            var old = first.completion().get(5, TimeUnit.SECONDS);
            assertTrue(old.generation() < skipped.generation());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void successfulRealBackendHasCompleteWorkerAccounting() throws Exception {
        var requests = new LinkedBlockingQueue<RenderDiagnostics>();
        var published = new CompletableFuture<RenderFrame>();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend())) {
            service.setDiagnosticsListener(requests::add);
            service.render(frame(), Runnable::run, p -> {}, published::complete, published::completeExceptionally);
            var diagnostics = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(diagnostics);
            assertTrue(published.get(5, TimeUnit.SECONDS).isComplete());
            var v = diagnostics.completion().get(5, TimeUnit.SECONDS).values();
            assertEquals(v.get("planned_tasks"), v.get("worker_tasks_registered"));
            assertEquals(v.get("worker_tasks_registered"), v.get("worker_tasks_started"));
            assertEquals(v.get("worker_tasks_registered"), v.get("worker_tasks_terminal"));
            assertTrue(v.get("planning_end") >= v.get("planning_start"));
            assertTrue(v.get("first_task_submitted") >= v.get("planning_end"));
            assertTrue(v.get("worker_queue_sum_ns") >= v.get("worker_queue_max_ns"));
        }
    }

    @Test void disabledDiagnosticsDoNotNotifyListener() throws Exception {
        System.clearProperty(RenderDiagnostics.PROPERTY);
        var notifications = new AtomicInteger();
        var done = new CompletableFuture<RenderFrame>();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend())) {
            service.setDiagnosticsListener(d -> notifications.incrementAndGet());
            service.render(frame(), Runnable::run, p -> {}, done::complete, done::completeExceptionally);
            assertTrue(done.get(5, TimeUnit.SECONDS).isComplete());
            assertEquals(0, notifications.get());
        }
    }

    @Test void repeatedRunAndExternalFutureCompletionCannotPrematurelyDrainRequest() throws Exception {
        var diagnostics = new RenderDiagnostics(42);
        var exposed = diagnostics.completion();
        exposed.complete(new RenderDiagnostics.Snapshot(0, java.util.Map.of()));
        assertFalse(diagnostics.completion().isDone());
        var calls = new AtomicInteger();
        var task = diagnostics.coordinatorTask(calls::incrementAndGet);
        task.run(); task.run();
        assertEquals(1, calls.get());
        assertEquals(42, diagnostics.completion().get(5, TimeUnit.SECONDS).generation());
    }
    @Test void completionCallbacksRunOutsideDiagnosticMonitor() throws Exception {
        var diagnostics = new RenderDiagnostics(1);
        try (var reader = Executors.newSingleThreadExecutor()) {
            var checked = diagnostics.completion().thenAccept(snapshot -> {
                try { assertEquals(snapshot.values(), reader.submit(diagnostics::currentValues).get(2, TimeUnit.SECONDS)); }
                catch (Exception e) { throw new AssertionError("Completion callback held the diagnostics lock", e); }
            });
            diagnostics.coordinatorTask(() -> {}).run();
            checked.get(5, TimeUnit.SECONDS);
        }
    }

}
