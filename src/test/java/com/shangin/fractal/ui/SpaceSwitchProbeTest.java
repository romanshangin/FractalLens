package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SpaceSwitchProbeTest {
    @Test
    void calculationWaitsForFocusLossAndTheDeclaredReleaseDelay() throws Exception {
        var scheduler = new ManualScheduler();
        var backend = new SpaceSwitchProbe.FocusGatedBackend(new DirectDoubleRenderBackend(), scheduler);
        RenderFrame frame = frame();
        try (var worker = Executors.newSingleThreadExecutor(); backend) {
            backend.focusChanged(true);
            var waiting = backend.arm();
            var rendered = worker.submit(() -> backend.render(frame, () -> false, ignored -> {}, null));
            waiting.get(5, TimeUnit.SECONDS);
            assertFalse(rendered.isDone());
            assertEquals(0, frame.validity().readyPixelCount());
            backend.focusChanged(false);
            ScheduledRelease release = scheduler.pending.poll(5, TimeUnit.SECONDS);
            assertNotNull(release);
            assertEquals(3_000, release.delayMillis());
            assertFalse(rendered.isDone(), "Focus loss alone must not release calculation");
            release.task().run();
            assertSame(frame, rendered.get(5, TimeUnit.SECONDS));
            assertTrue(frame.isComplete());
            assertEquals(64, frame.validity().readyPixelCount());
            var expected = new FractalCalculator(FractalPreset.MANDELBROT.createFormula())
                    .calculate(8, 8, frame.renderGrid(), 300);
            for (int pixel = 0; pixel < expected.size(); pixel++) {
                assertEquals(expected.iterations(pixel), frame.samplePlane().iterations(pixel));
                assertEquals(expected.escaped(pixel), frame.samplePlane().escaped(pixel));
            }
        }
        assertTrue(scheduler.isShutdown());
    }

    @Test
    void cancellationStopsAHeldRenderWithoutPublishingPixels() throws Exception {
        var backend = new SpaceSwitchProbe.FocusGatedBackend();
        RenderFrame frame = frame();
        var cancelled = new AtomicBoolean();
        try (var worker = Executors.newSingleThreadExecutor(); backend) {
            var waiting = backend.arm();
            var rendered = worker.submit(() -> backend.render(frame, cancelled::get, ignored -> {}, null));
            waiting.get(5, TimeUnit.SECONDS);
            cancelled.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rendered.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(0, frame.validity().readyPixelCount());
        }
    }

    @Test
    void closeIsIdempotentAndDrainsAHeldRenderWithoutCalculation() throws Exception {
        var backend = new SpaceSwitchProbe.FocusGatedBackend();
        RenderFrame frame = frame();
        try (var worker = Executors.newSingleThreadExecutor(); backend) {
            var waiting = backend.arm();
            var rendered = worker.submit(() -> backend.render(frame, () -> false, ignored -> {}, null));
            waiting.get(5, TimeUnit.SECONDS);
            backend.close();
            backend.close();
            assertDoesNotThrow(() -> backend.focusChanged(false),
                    "Late window focus loss must not schedule work after close");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> rendered.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(0, frame.validity().readyPixelCount());
        }
    }

    private static RenderFrame frame() {
        FractalPreset preset = FractalPreset.MANDELBROT;
        return RenderFrame.create(new RenderRequest(new FractalCalculator(preset.createFormula()),
                preset.defaultViewport(), 8, 8, 300));
    }

    private record ScheduledRelease(long delayMillis, Runnable task) {}

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final BlockingQueue<ScheduledRelease> pending = new LinkedBlockingQueue<>();

        ManualScheduler() { super(1); }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> future = super.schedule(command, 1, TimeUnit.DAYS);
            pending.add(new ScheduledRelease(unit.toMillis(delay), (Runnable) future));
            return future;
        }
    }
}
