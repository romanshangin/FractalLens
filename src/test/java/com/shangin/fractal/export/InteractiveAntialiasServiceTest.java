package com.shangin.fractal.export;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.SamplingPattern;
import com.shangin.fractal.ui.FractalCamera;
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
    void deepPanShouldQueueAntialiasingOnlyForExposedEdgeTiles() {
        FractalPreset preset = FractalPreset.MANDELBROT;
        FractalCamera camera = new FractalCamera(preset);
        int logicalWidth = 48;
        int logicalHeight = 32;
        int renderWidth = 96;
        int renderHeight = 64;
        camera.resize(logicalWidth, logicalHeight);

        while (camera.zoomBy(
                logicalWidth / 2.0,
                logicalHeight / 2.0,
                0.8,
                logicalWidth,
                logicalHeight,
                renderWidth,
                renderHeight
        )) {
            // Exercise the same precision boundary as the interactive camera.
        }

        var sourceViewport = camera.viewport();
        FractalCalculator calculator = new FractalCalculator(preset.createFormula());
        RenderFrame sourceFrame = RenderFrame.create(new RenderRequest(
                calculator,
                sourceViewport,
                renderWidth,
                renderHeight,
                1_000
        ));
        sourceFrame.validity().markReady(
                new RenderRegion(0, 0, renderWidth, renderHeight)
        );

        assertTrue(camera.pan(3.0, -2.0, logicalWidth, logicalHeight));
        camera.snapToRenderGrid(sourceViewport, renderWidth, renderHeight);
        FrameReuseResult reuse = new FrameReusePlanner().plan(
                sourceFrame,
                new RenderRequest(
                        calculator,
                        camera.viewport(),
                        renderWidth,
                        renderHeight,
                        1_000
                )
        );
        assertTrue(reuse.reused());
        assertEquals(new PixelShift(6, -4), reuse.shift().orElseThrow());

        ValidityMask sourceRefinement = completeMask(renderWidth, renderHeight);
        ValidityMask shiftedRefinement = new ValidityMask(renderWidth, renderHeight);
        shiftedRefinement.copyShiftedFrom(
                sourceRefinement,
                reuse.shift().orElseThrow()
        );
        List<RenderRegion> pending = InteractiveAntialiasService.orderedTiles(
                reuse.frame(),
                new RefinedPixelSnapshot(
                        renderWidth,
                        renderHeight,
                        new int[renderWidth * renderHeight],
                        shiftedRefinement
                )
        );

        assertEquals(4, pending.size());
        assertTrue(pending.stream().allMatch(tile -> tile.x() == 0 || tile.y() == 32));
    }

    @Test
    void schedulerShouldExcludeFullyRefinedTilesBeforeSubmittingWorkers() {
        int width = 96;
        int height = 64;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                FractalPreset.MANDELBROT.defaultViewport(),
                width,
                height,
                100
        ));
        ValidityMask almostComplete = new ValidityMask(width, height);
        almostComplete.markReady(new RenderRegion(0, 0, 90, height));
        RefinedPixelSnapshot snapshot = new RefinedPixelSnapshot(
                width,
                height,
                new int[width * height],
                almostComplete
        );

        List<RenderRegion> pending = InteractiveAntialiasService.orderedTiles(
                frame,
                snapshot
        );

        assertEquals(2, pending.size());
        assertTrue(pending.stream().allMatch(tile -> tile.x() == 64));
        assertEquals(
                0,
                InteractiveAntialiasService.orderedTiles(
                        frame,
                        new RefinedPixelSnapshot(
                                width,
                                height,
                                new int[width * height],
                                completeMask(width, height)
                        )
                ).size()
        );
        assertEquals(
                6,
                InteractiveAntialiasService.orderedTiles(
                        frame,
                        RefinedPixelSnapshot.empty(width, height)
                ).size()
        );
    }

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
                    RefinedPixelSnapshot.empty(48, 32),
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
                    RefinedPixelSnapshot.empty(32, 32),
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

    @Test
    void qualityModeShouldPublishTilesWithoutRefinementCandidates() throws Exception {
        FractalPreset preset = FractalPreset.BURNING_SHIP;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator((real, imaginary, maxIterations) ->
                        new FractalSample(1, true, 3.0, 0.0)),
                preset.defaultViewport(),
                40,
                40,
                20
        ));

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2);
             InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            calculator.calculate(frame, () -> false, ignored -> {});
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger publishedTiles = new AtomicInteger();

            service.refine(
                    frame,
                    (iterations, smooth, escaped, maximum) -> 0xFF123456,
                    SamplingPattern.REGULAR,
                    RefinedPixelSnapshot.empty(40, 40),
                    Runnable::run,
                    (region, colors) -> {
                        assertTrue(Arrays.stream(colors).allMatch(color -> color == 0xFF123456));
                        publishedTiles.incrementAndGet();
                    },
                    completed::countDown,
                    exception -> fail(exception)
            );

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(4, publishedTiles.get());
        }
    }

    @Test
    void refinementShouldSkipAlreadyRefinedOverlap() throws Exception {
        AtomicInteger sampleCalculations = new AtomicInteger();
        int width = 40;
        int height = 32;
        FractalCalculator fractalCalculator = new FractalCalculator(
                (real, imaginary, maximum) -> {
                    sampleCalculations.incrementAndGet();
                    return new FractalSample(1, true, 3.0, 0.0);
                }
        );
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                fractalCalculator,
                FractalPreset.BURNING_SHIP.defaultViewport(),
                width,
                height,
                20
        ));
        int[] baseColors = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int iterations = x & 1;
                frame.fractalData().set(
                        x,
                        y,
                        new FractalSample(iterations, true, 3.0, 0.0)
                );
                baseColors[y * width + x] = iterations == 0
                        ? 0xFF000000
                        : 0xFFFFFFFF;
            }
        }
        frame.validity().markReady(new RenderRegion(0, 0, width, height));

        ValidityMask refined = new ValidityMask(width, height);
        refined.markReady(new RenderRegion(0, 0, 32, height));
        RefinedPixelSnapshot reused = new RefinedPixelSnapshot(
                width,
                height,
                baseColors,
                refined
        );

        try (InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger publishedTiles = new AtomicInteger();

            service.refine(
                    frame,
                    (iterations, smooth, escaped, maximum) -> iterations == 0
                            ? 0xFF000000
                            : 0xFFFFFFFF,
                    SamplingPattern.REGULAR,
                    reused,
                    Runnable::run,
                    (region, colors) -> publishedTiles.incrementAndGet(),
                    completed::countDown,
                    exception -> fail(exception)
            );

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, publishedTiles.get());
            assertEquals(8 * height * 16, sampleCalculations.get());
        }
    }

    @Test
    void fullyRefinedFrameShouldCompleteWithoutSamplingOrPublishing() throws Exception {
        int width = 32;
        int height = 16;
        AtomicInteger sampleCalculations = new AtomicInteger();
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator((real, imaginary, maximum) -> {
                    sampleCalculations.incrementAndGet();
                    return new FractalSample(1, true, 3.0, 0.0);
                }),
                FractalPreset.BURNING_SHIP.defaultViewport(),
                width,
                height,
                20
        ));
        frame.validity().markReady(new RenderRegion(0, 0, width, height));
        ValidityMask refined = new ValidityMask(width, height);
        refined.markReady(new RenderRegion(0, 0, width, height));

        try (InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger publishedTiles = new AtomicInteger();

            service.refine(
                    frame,
                    (iterations, smooth, escaped, maximum) -> 0xFF123456,
                    SamplingPattern.REGULAR,
                    new RefinedPixelSnapshot(
                            width,
                            height,
                            new int[width * height],
                            refined
                    ),
                    Runnable::run,
                    (region, colors) -> publishedTiles.incrementAndGet(),
                    completed::countDown,
                    exception -> fail(exception)
            );

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(0, publishedTiles.get());
            assertEquals(0, sampleCalculations.get());
        }
    }

    private static ValidityMask completeMask(int width, int height) {
        ValidityMask mask = new ValidityMask(width, height);
        mask.markReady(new RenderRegion(0, 0, width, height));
        return mask;
    }
}
