package com.shangin.fractal.export;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.SamplingPattern;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveAntialiasServiceTest {

    @Test
    void refinementShouldPublishTilesBeforeCompletingWithoutChangingSamples() throws Exception {
        FractalPreset preset = FractalPreset.MANDELBROT;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(preset.createFormula()),
                preset.defaultViewport(),
                48,
                32,
                200
        ));

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2);
             InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            calculator.calculate(frame, () -> false, ignored -> {});
            int readyPixels = frame.validity().readyPixelCount();
            int[] baseColors = AdaptivePngExportService.colorBaseFrame(
                    frame.fractalData(),
                    new SmoothPaletteColoring(PalettePreset.ICE.palette())
            );
            CountDownLatch completed = new CountDownLatch(1);
            int[] result = baseColors.clone();
            List<RenderRegion> publishedRegions = new CopyOnWriteArrayList<>();
            AtomicBoolean completedCallbackCalled = new AtomicBoolean();
            AtomicBoolean tileArrivedBeforeCompletion = new AtomicBoolean();
            AtomicReference<Throwable> error = new AtomicReference<>();

            service.refine(
                    frame,
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    SamplingPattern.REGULAR,
                    Runnable::run,
                    (region, colors) -> {
                        tileArrivedBeforeCompletion.compareAndSet(
                                false,
                                !completedCallbackCalled.get()
                        );
                        publishedRegions.add(region);
                        for (int row = 0; row < region.height(); row++) {
                            System.arraycopy(
                                    colors,
                                    row * region.width(),
                                    result,
                                    (region.y() + row) * frame.fractalData().width() + region.x(),
                                    region.width()
                            );
                        }
                    },
                    () -> {
                        completedCallbackCalled.set(true);
                        completed.countDown();
                    },
                    exception -> {
                        error.set(exception);
                        completed.countDown();
                    }
            );

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                completed.await();
            });
            assertNull(error.get());
            assertTrue(tileArrivedBeforeCompletion.get());
            assertFalse(publishedRegions.isEmpty());
            assertTrue(publishedRegions.stream().allMatch(region ->
                    region.width() <= 32 && region.height() <= 32
            ));
            assertFalse(Arrays.equals(baseColors, result));
            assertEquals(readyPixels, frame.validity().readyPixelCount());
        }
    }

    @Test
    void cancellationShouldSuppressQueuedTileCallbacks() throws Exception {
        FractalPreset preset = FractalPreset.MANDELBROT;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(preset.createFormula()),
                preset.defaultViewport(),
                32,
                32,
                200
        ));

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2);
             InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            calculator.calculate(frame, () -> false, ignored -> {});
            ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
            CountDownLatch callbackQueued = new CountDownLatch(1);
            AtomicInteger publishedTiles = new AtomicInteger();

            service.refine(
                    frame,
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    SamplingPattern.REGULAR,
                    callback -> {
                        callbacks.add(callback);
                        callbackQueued.countDown();
                    },
                    (region, colors) -> publishedTiles.incrementAndGet(),
                    () -> {},
                    exception -> fail(exception)
            );

            assertTrue(callbackQueued.await(5, TimeUnit.SECONDS));
            service.cancelCurrent();

            Runnable callback;
            while ((callback = callbacks.poll()) != null) {
                callback.run();
            }

            assertEquals(0, publishedTiles.get());
        }
    }
}
