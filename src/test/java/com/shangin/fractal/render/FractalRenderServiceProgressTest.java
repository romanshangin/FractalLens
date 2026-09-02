package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Tests publication delays and races without sleeping for a real frame interval. */
class FractalRenderServiceProgressTest {

    private static final RenderRegion LEFT = new RenderRegion(0, 0, 1, 2);
    private static final RenderRegion MIDDLE = new RenderRegion(1, 0, 1, 2);
    private static final RenderRegion RIGHT = new RenderRegion(2, 0, 1, 2);
    private static final RenderRegion WHOLE = new RenderRegion(0, 0, 3, 2);

    private final ManualProgressScheduler scheduler = new ManualProgressScheduler();
    private final List<RenderProgressBatch> batches = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch completed = new CountDownLatch(1);
    private FractalRenderService service;

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
        scheduler.shutdownNow();
    }

    @Test
    void firstFlushHasNoDelayAndLaterRegionsStillShareASixteenMillisecondBatch()
            throws InterruptedException {
        CountDownLatch firstPublished = new CountDownLatch(1);
        CountDownLatch laterProduced = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);
        service = new FractalRenderService(backend((frame, progress) -> {
            progress.accept(LEFT);
            await(firstPublished);
            progress.accept(MIDDLE);
            progress.accept(RIGHT);
            laterProduced.countDown();
            await(allowCompletion);
        }), scheduler);

        service.render(frame(), Runnable::run, progress -> {
            recordProgress(progress);
            firstPublished.countDown();
        }, ignored -> recordSuccess(), this::recordError);

        ScheduledFlush first = scheduler.nextFlush();
        assertEquals(0L, first.delayMs());
        assertTrue(batches.isEmpty());
        first.run();
        await(laterProduced);
        assertEquals(List.of(LEFT), batches.getFirst().regions());

        ScheduledFlush later = scheduler.nextFlush();
        assertEquals(16L, later.delayMs());
        assertEquals(1, batches.size());
        assertTrue(scheduler.pending.isEmpty(), "Later regions should share one scheduled flush");
        later.run();
        assertEquals(List.of(MIDDLE, RIGHT), batches.getLast().regions());

        allowCompletion.countDown();
        await(completed);
        assertEquals(List.of("progress", "progress", "success"), events);
    }

    @Test
    void completionDrainsTheFirstBatchWithoutWaitingOrPublishingItTwice()
            throws InterruptedException {
        service = new FractalRenderService(backend((frame, progress) -> progress.accept(WHOLE)), scheduler);
        service.render(frame(), Runnable::run, this::recordProgress,
                ignored -> recordSuccess(), this::recordError);

        await(completed);
        assertEquals(List.of("progress", "success"), events);
        assertEquals(List.of(WHOLE), batches.getFirst().regions());

        ScheduledFlush pending = scheduler.nextFlush();
        assertEquals(0L, pending.delayMs());
        pending.run();
        assertEquals(List.of("progress", "success"), events);
    }

    @Test
    void cancellationSuppressesAnAlreadyScheduledFirstFlush() throws InterruptedException {
        service = new FractalRenderService(backend((frame, progress) -> {
            progress.accept(LEFT);
            await(new CountDownLatch(1));
        }), scheduler);
        service.render(frame(), Runnable::run, this::recordProgress,
                ignored -> recordSuccess(), this::recordError);

        ScheduledFlush pending = scheduler.nextFlush();
        service.cancelCurrent();
        pending.run();

        assertTrue(events.isEmpty());
        assertTrue(batches.isEmpty());
    }

    @Test
    void replacementSuppressesQueuedCallbacksAndGetsItsOwnImmediateFirstFlush()
            throws InterruptedException {
        RenderFrame oldFrame = frame();
        RenderFrame newFrame = frame();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        service = new FractalRenderService(backend((frame, progress) -> {
            if (frame == oldFrame) {
                progress.accept(LEFT);
                await(new CountDownLatch(1));
            } else {
                progress.accept(WHOLE);
            }
        }), scheduler);

        service.render(oldFrame, callbacks::add, this::recordProgress,
                ignored -> recordSuccess(), this::recordError);
        ScheduledFlush oldFlush = scheduler.nextFlush();
        assertEquals(0L, oldFlush.delayMs());
        oldFlush.run();
        Runnable obsoleteCallback = next(callbacks);

        service.render(newFrame, callbacks::add, this::recordProgress,
                ignored -> recordSuccess(), this::recordError);
        ScheduledFlush newFlush = scheduler.nextFlush();
        assertEquals(0L, newFlush.delayMs());
        Runnable completion = next(callbacks);

        obsoleteCallback.run();
        assertTrue(events.isEmpty());
        completion.run();
        newFlush.run();

        assertEquals(List.of("progress", "success"), events);
        assertEquals(1, batches.size());
        assertSame(newFrame, batches.getFirst().frame());
    }

    private void recordProgress(RenderProgressBatch progress) {
        batches.add(progress);
        events.add("progress");
    }

    private void recordSuccess() {
        events.add("success");
        completed.countDown();
    }

    private void recordError(Throwable error) {
        events.add("error: " + error);
        completed.countDown();
    }

    private static RenderFrame frame() {
        FractalPreset preset = FractalPreset.MANDELBROT;
        return RenderFrame.create(new RenderJob(
                FormulaDefinition.forPreset(preset, OrbitTrap.NONE), preset.defaultViewport(), 3, 2, 10));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for render synchronization");
    }

    private static <T> T next(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(value, "Timed out waiting for scheduled work");
        return value;
    }

    @FunctionalInterface
    private interface RenderAction {
        void run(RenderFrame frame, Consumer<RenderRegion> progress) throws InterruptedException;
    }

    private static RenderBackend backend(RenderAction action) {
        return new RenderBackend() {
            @Override
            public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                                      Consumer<RenderRegion> progress,
                                      Consumer<TileTimingStats> timingCompleted) throws InterruptedException {
                action.run(frame, progress);
                frame.validity().markReady(WHOLE);
                return frame;
            }

            @Override
            public void close() {}
        };
    }

    private record ScheduledFlush(long delayMs, Runnable task) {
        void run() {
            task.run();
        }
    }

    private static final class ManualProgressScheduler extends ScheduledThreadPoolExecutor {
        private final BlockingQueue<ScheduledFlush> pending = new LinkedBlockingQueue<>();

        private ManualProgressScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            // Capture the requested delay; only the test explicitly runs the task.
            ScheduledFuture<?> future = super.schedule(command, 1L, TimeUnit.DAYS);
            pending.add(new ScheduledFlush(unit.toMillis(delay), (Runnable) future));
            return future;
        }

        ScheduledFlush nextFlush() throws InterruptedException {
            return next(pending);
        }
    }
}
