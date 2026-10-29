package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FreshFramePreparationExperimentTest {
    private static RenderJob job(int iterations) {
        var preset = FractalPreset.MANDELBROT;
        return new RenderJob(FormulaDefinition.forPreset(preset, com.shangin.fractal.coloring.OrbitTrap.NONE), preset.defaultViewport(), 96, 66, iterations);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Deterministic preparation checkpoint timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }

    @Test void burstCoalescesPendingJobsAndNeverAllocatesInParallel() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var calls = new CopyOnWriteArrayList<Integer>();
        var published = new CopyOnWriteArrayList<Integer>();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        try (var preparer = new FreshFramePreparationExperiment(callbacks::add, request -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            calls.add(request.maxIterations());
            try {
                if (request.maxIterations() == 1) { entered.countDown(); await(release); }
                return RenderFrame.create(request);
            } finally { active.decrementAndGet(); }
        })) {
            preparer.submit(job(1), p -> published.add(p.frame().job().maxIterations()), error -> fail(error));
            await(entered);
            CompletableFuture<Void> drained = null;
            for (int cap = 2; cap <= 100; cap++) {
                drained = preparer.submit(job(cap), p -> published.add(p.frame().job().maxIterations()), error -> fail(error));
            }
            assertTrue(preparer.snapshot().allocating());
            assertTrue(preparer.snapshot().pending());
            assertFalse(preparer.snapshot().ready());
            release.countDown();
            drained.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 100), calls);
            assertEquals(1, maximum.get());
            assertEquals(1, callbacks.size());
            callbacks.remove().run();
            assertEquals(List.of(100), published);
            assertFalse(preparer.snapshot().ready());
        } finally { release.countDown(); }
    }

    @Test void queuedCallbackOwnsOnlyTheLatestReadyResult() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var published = new CopyOnWriteArrayList<RenderJob>();
        var first = job(1); var latest = job(2);
        try (var preparer = new FreshFramePreparationExperiment(callbacks::add)) {
            preparer.submit(first, p -> published.add(p.frame().job()), error -> fail(error)).get(5, TimeUnit.SECONDS);
            assertTrue(preparer.snapshot().ready());
            preparer.submit(latest, p -> published.add(p.frame().job()), error -> fail(error)).get(5, TimeUnit.SECONDS);
            assertEquals(1, callbacks.size(), "Replacement must not accumulate callbacks retaining frames");
            callbacks.remove().run();
            assertEquals(List.of(latest), published);
        }
    }

    @Test void cancellationAndIdempotentCloseDrainTheActualAllocationBeforeCompleting() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var preparer = new FreshFramePreparationExperiment(callbacks::add, request -> {
            entered.countDown(); await(release); return RenderFrame.create(request);
        });
        try {
            preparer.submit(job(1), ignored -> fail("Cancelled frame published"), error -> fail(error));
            await(entered);
            var drained = preparer.cancel();
            assertFalse(drained.isDone(), "Cancellation is not allocation exit");
            preparer.close(); preparer.close();
            assertFalse(drained.isDone());
            release.countDown();
            drained.get(5, TimeUnit.SECONDS);
            assertTrue(preparer.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callbacks.isEmpty());
            assertFalse(preparer.snapshot().allocating());
            assertFalse(preparer.snapshot().ready());
            assertThrows(IllegalStateException.class,
                    () -> preparer.submit(job(2), ignored -> {}, ignored -> {}));
        } finally { release.countDown(); preparer.close(); }
    }

    @Test void closeDropsReadyPixelsEvenWhenDeliveryWasAlreadyQueued() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var preparer = new FreshFramePreparationExperiment(callbacks::add);
        preparer.submit(job(1), ignored -> fail("Closed frame published"), error -> fail(error)).get(5, TimeUnit.SECONDS);
        preparer.close();
        assertFalse(preparer.snapshot().ready());
        callbacks.remove().run();
        assertTrue(preparer.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test void factoryFailureIsDeliveredAndDoesNotPoisonTheNextRequest() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var published = new CompletableFuture<RenderFrame>();
        var failure = new IllegalStateException("Synthetic allocation failure");
        try (var preparer = new FreshFramePreparationExperiment(callbacks::add, request -> {
            if (request.maxIterations() == 1) throw failure;
            return RenderFrame.create(request);
        })) {
            preparer.submit(job(1), ignored -> fail("Failed frame published"), errors::add).get(5, TimeUnit.SECONDS);
            callbacks.remove().run();
            assertEquals(List.of(failure), errors);
            preparer.submit(job(2), p -> published.complete(p.frame()), errors::add).get(5, TimeUnit.SECONDS);
            callbacks.remove().run();
            assertEquals(2, published.get(5, TimeUnit.SECONDS).job().maxIterations());
        }
    }

    @Test void dispatchFailureIsReportedByTheDrainStageAndClearsReadyStorage() throws Exception {
        try (var preparer = new FreshFramePreparationExperiment(ignored -> { throw new RejectedExecutionException("Synthetic dispatcher rejection"); })) {
            var result = preparer.submit(job(1), ignored -> {}, ignored -> {});
            var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, error.getCause());
            assertFalse(preparer.snapshot().ready());
            assertFalse(preparer.snapshot().callbackQueued());
        }
    }

    @Test void invalidSubmissionDoesNotSupersedeAValidReadyFrame() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var published = new CompletableFuture<RenderFrame>();
        try (var preparer = new FreshFramePreparationExperiment(callbacks::add)) {
            preparer.submit(job(1), p -> published.complete(p.frame()), error -> fail(error)).get(5, TimeUnit.SECONDS);
            assertThrows(NullPointerException.class, () -> preparer.submit(null, ignored -> {}, ignored -> {}));
            callbacks.remove().run();
            assertEquals(1, published.get(5, TimeUnit.SECONDS).job().maxIterations());
        }
    }

    @Test void directCallbackMaySubmitTheNextRequestWithoutLosingIt() throws Exception {
        var published = new CompletableFuture<RenderFrame>();
        try (var preparer = new FreshFramePreparationExperiment(Runnable::run)) {
            preparer.submit(job(1), first -> preparer.submit(job(2), p -> published.complete(p.frame()), published::completeExceptionally),
                    published::completeExceptionally);
            assertEquals(2, published.get(5, TimeUnit.SECONDS).job().maxIterations());
        }
    }
}
