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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveAntialiasServiceTest {

    @Test
    void deepGridSizeUsesFourByFourOnlyAcrossSetBoundary() {
        FractalData data = new FractalData(3, 3, 20);
        for (int index = 0; index < data.size(); index++) {
            data.set(index, new FractalSample(10, true, 3.0, 0.0));
        }

        assertEquals(2, InteractiveAntialiasService.deepSampleGridSize(
                data, 1, 1));

        data.set(0, new FractalSample(20, false, 0.0, 0.0));
        assertEquals(4, InteractiveAntialiasService.deepSampleGridSize(
                data, 1, 1));
    }

    @Test
    void deepCandidatesIgnoreGentleGradientsButKeepWhiteOutliersAndSetEdges() {
        FractalData data = new FractalData(3, 3, 20);
        int[] colors = new int[9];
        Arrays.fill(colors, 0xFF000000);
        for (int index = 0; index < data.size(); index++) {
            data.set(index, new FractalSample(10, true, 3.0, 0.0));
        }

        colors[4] = 0xFF555555;
        assertFalse(InteractiveAntialiasService.isDeepSupersamplingCandidate(
                data, colors, 1, 1));

        colors[4] = 0xFFFFFFFF;
        assertTrue(InteractiveAntialiasService.isDeepSupersamplingCandidate(
                data, colors, 1, 1));

        Arrays.fill(colors, 0xFF000000);
        data.set(0, new FractalSample(20, false, 0.0, 0.0));
        assertTrue(InteractiveAntialiasService.isDeepSupersamplingCandidate(
                data, colors, 1, 1));
    }

    @Test
    void zoomOutShouldRefineNewlyExposedEdgesBeforeReprojectedCenter() {
        int width = 128;
        int height = 128;
        RenderRegion approximateCenter = new RenderRegion(32, 32, 64, 64);
        FractalPreset preset = FractalPreset.MANDELBROT;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(preset.createFormula()),
                preset.defaultViewport().zoom(1.25),
                width,
                height,
                100,
                RenderPriority.center(),
                Optional.of(approximateCenter)
        ));

        List<RenderRegion> tiles = InteractiveAntialiasService.orderedTiles(
                frame,
                RefinedPixelSnapshot.empty(width, height)
        );

        int firstCenterTile = -1;
        for (int index = 0; index < tiles.size(); index++) {
            RenderRegion tile = tiles.get(index);
            boolean covered = tile.x() >= approximateCenter.x()
                    && tile.x() + tile.width()
                    <= approximateCenter.x() + approximateCenter.width()
                    && tile.y() >= approximateCenter.y()
                    && tile.y() + tile.height()
                    <= approximateCenter.y() + approximateCenter.height();

            if (covered && firstCenterTile < 0) {
                firstCenterTile = index;
            }
            if (!covered && firstCenterTile >= 0) {
                fail("An exposed AA tile was scheduled after the reprojected center");
            }
        }

        assertEquals(12, firstCenterTile);
        assertEquals(16, tiles.size());
        assertEquals(new RenderRegion(32, 0, 32, 32), tiles.getFirst());
        assertTrue(tiles.indexOf(new RenderRegion(32, 0, 32, 32))
                < tiles.indexOf(new RenderRegion(0, 0, 32, 32)));
    }

    @Test
    void deepPanShouldQueueAntialiasingOnlyForExposedEdgeTiles() {
        FractalPreset preset = FractalPreset.MANDELBROT;
        FractalCamera camera = new FractalCamera(preset);
        int logicalWidth = 48;
        int logicalHeight = 32;
        int renderWidth = 96;
        int renderHeight = 64;
        camera.resize(logicalWidth, logicalHeight);

        for (int zoom = 0; zoom < 120; zoom++) {
            assertTrue(camera.zoomBy(
                    logicalWidth / 2.0,
                    logicalHeight / 2.0,
                    0.8,
                    logicalWidth,
                    logicalHeight,
                    renderWidth,
                    renderHeight
            ));
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
            assertArrayEquals(
                    result,
                    service.recolorCached(
                            frame,
                            new SmoothPaletteColoring(PalettePreset.ICE.palette())),
                    "Animated recolor must preserve the completed AA frame"
            );
            assertEquals(readyPixels, frame.validity().readyPixelCount());
        }
    }

    @Test
    void deepRefinementPublishesPreciseCandidateTiles() throws Exception {
        RenderFrame frame = renderDeepFrame();
        RenderJob job = frame.job();

        SmoothPaletteColoring coloring = new SmoothPaletteColoring(
                PalettePreset.ICE.palette());
        int[] baseColors = AdaptivePngExportService.colorBaseFrame(
                frame.samplePlane(), coloring);
        int[] refinedColors = baseColors.clone();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger publishedTiles = new AtomicInteger();

        try (InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            service.refineDeep(
                    frame,
                    coloring,
                    SamplingPattern.REGULAR,
                    RefinedPixelSnapshot.empty(job.width(), job.height()),
                    Runnable::run,
                    (region, colors) -> {
                        publishedTiles.incrementAndGet();
                        for (int row = 0; row < region.height(); row++) {
                            System.arraycopy(
                                    colors,
                                    row * region.width(),
                                    refinedColors,
                                    (region.y() + row) * job.width() + region.x(),
                                    region.width());
                        }
                    },
                    completed::countDown,
                    exception -> {
                        error.set(exception);
                        completed.countDown();
                    }
            );

            assertTrue(completed.await(15, TimeUnit.SECONDS));
            assertNull(error.get());
            assertTrue(publishedTiles.get() > 0);
            assertFalse(Arrays.equals(baseColors, refinedColors));
            int[] recolored = service.recolorCached(frame, coloring);
            for (int index = 0; index < recolored.length; index++) {
                assertTrue(colorsDifferByAtMostOne(refinedColors[index], recolored[index]),
                        "cached deep-AA color drift at pixel " + index);
            }
        }
    }

    @Test
    void cancellingDeepRefinementSuppressesQueuedCallbacks() throws Exception {
        RenderFrame frame = renderDeepFrame();
        ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicInteger publishedTiles = new AtomicInteger();

        try (InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            service.refineDeep(
                    frame,
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    SamplingPattern.REGULAR,
                    RefinedPixelSnapshot.empty(
                            frame.job().width(), frame.job().height()),
                    callback -> {
                        callbacks.add(callback);
                        callbackQueued.countDown();
                    },
                    (region, colors) -> publishedTiles.incrementAndGet(),
                    () -> {},
                    exception -> fail(exception)
            );

            assertTrue(callbackQueued.await(15, TimeUnit.SECONDS));
            service.cancelCurrent();

            Runnable callback;
            while ((callback = callbacks.poll()) != null) {
                callback.run();
            }
            assertEquals(0, publishedTiles.get());
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

    private static RenderFrame renderDeepFrame() throws InterruptedException {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT,
                        com.shangin.fractal.coloring.OrbitTrap.NONE),
                new com.shangin.fractal.math.Viewport(
                        "-0.8317528516858322713653476366999",
                        "0.207813754242134522471317257011028",
                        "1.6e-13"),
                24, 18, 2_700);
        RenderFrame frame = RenderFrame.create(job);
        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(2, ignored -> {})) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }
        return frame;
    }

    @Test
    void cancelledCachedTileMustBePublishedWhenRefinementResumes() throws Exception {
        AtomicInteger samples = new AtomicInteger();
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator((real, imaginary, maximum) -> {
                    samples.incrementAndGet();
                    return new FractalSample(1, true, 3.0, 0.0);
                }), FractalPreset.BURNING_SHIP.defaultViewport(), 4, 4, 20));
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                frame.fractalData().set(y * 4 + x,
                        new FractalSample(1, (x + y) % 2 == 0, 3.0, 0.0));
            }
        }
        frame.validity().markReady(new RenderRegion(0, 0, 4, 4));
        try (InteractiveAntialiasService service = new InteractiveAntialiasService()) {
            ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
            CountDownLatch queued = new CountDownLatch(2); // tile and completion
            var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
            service.refine(frame, coloring, SamplingPattern.REGULAR, RefinedPixelSnapshot.empty(4, 4),
                    callback -> { callbacks.add(callback); queued.countDown(); },
                    (region, colors) -> fail("Cancelled tile must not be published"),
                    () -> {}, exception -> fail(exception));
            assertTrue(queued.await(5, TimeUnit.SECONDS));
            service.cancelCurrent();
            callbacks.forEach(Runnable::run);
            int calculated = samples.get();
            AtomicInteger published = new AtomicInteger();
            CountDownLatch completed = new CountDownLatch(1);
            service.refine(frame, coloring, SamplingPattern.REGULAR, RefinedPixelSnapshot.empty(4, 4),
                    Runnable::run, (region, colors) -> published.incrementAndGet(),
                    completed::countDown, exception -> fail(exception));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(calculated, samples.get(), "Cached samples must not be recalculated");
            assertEquals(1, published.get(), "Cached colors still need to reach the display");
        }
    }

    private static boolean colorsDifferByAtMostOne(int first, int second) {
        for (int shift : new int[]{24, 16, 8, 0}) {
            int firstChannel = (first >>> shift) & 0xFF;
            int secondChannel = (second >>> shift) & 0xFF;
            if (Math.abs(firstChannel - secondChannel) > 1) {
                return false;
            }
        }
        return true;
    }
}
