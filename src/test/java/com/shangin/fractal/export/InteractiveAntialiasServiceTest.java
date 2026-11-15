package com.shangin.fractal.export;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.coloring.PaletteEdgeThresholds;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.*;
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
             InteractiveAntialiasService service = new InteractiveAntialiasService(
                     new AntialiasSampleCache(), true)) {
            calculator.calculate(frame, () -> false, ignored -> {});
            CountDownLatch completed = new CountDownLatch(1);
            AtomicInteger publishedTiles = new AtomicInteger();

            service.refine(
                    frame,
                    (iterations, smooth, escaped, maximum) -> 0xFF123456,
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
            InteractiveAntialiasService.Profile profile = service.lastProfile();
            assertTrue(profile.totalNanos() >= profile.baseColorNanos());
            assertEquals(40L * 40, profile.testedPixels());
            assertEquals(0, profile.candidates());
            assertEquals(0, profile.samples());
        }
    }

    @Test
    void hiddenCachedSamplesAreExcludedFromRecolorAndReusedByTheNextRefinement() throws Exception {
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                FractalPreset.MANDELBROT.defaultViewport(), 80, 60, 100));
        SmoothPaletteColoring coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2);
             InteractiveAntialiasService service = new InteractiveAntialiasService(
                     new AntialiasSampleCache(), true)) {
            calculator.calculate(frame, () -> false, ignored -> {});
            refineWholeFrame(service, frame, coloring);
            long firstPassSamples = service.lastProfile().samples();
            assertTrue(firstPassSamples > 0, "The fixture must contain AA candidates");
            int[] antialiased = service.recolorCached(frame, coloring);
            int[] base;
            try (InteractiveAntialiasService withoutSamples = new InteractiveAntialiasService()) {
                base = withoutSamples.recolorCached(frame, coloring);
            }
            assertFalse(Arrays.equals(base, antialiased));

            // Base only hides the finished samples from recoloring but keeps them.
            service.showCachedSamples(false);
            assertArrayEquals(base, service.recolorCached(frame, coloring));

            // A later antialiasing mode publishes the same AA colors without new samples.
            service.showCachedSamples(true);
            refineWholeFrame(service, frame, coloring);
            assertEquals(0, service.lastProfile().samples(), "Finished AA samples must not be recalculated");
            assertArrayEquals(antialiased, service.recolorCached(frame, coloring));
        }
    }

    private static void refineWholeFrame(InteractiveAntialiasService service, RenderFrame frame,
                                         SmoothPaletteColoring coloring) throws InterruptedException {
        refineWholeFrame(service, frame, coloring, false);
    }

    private static void refineWholeFrame(InteractiveAntialiasService service, RenderFrame frame,
                                         SmoothPaletteColoring coloring, boolean deepZoom)
            throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RefinedPixelSnapshot empty = RefinedPixelSnapshot.empty(
                frame.samplePlane().width(), frame.samplePlane().height());
        if (deepZoom) {
            service.refineDeep(frame, coloring, empty, Runnable::run, (region, colors) -> {},
                    completed::countDown, failure::set);
        } else {
            service.refine(frame, coloring, empty, Runnable::run, (region, colors) -> {},
                    completed::countDown, failure::set);
        }
        assertTrue(completed.await(15, TimeUnit.SECONDS));
        assertNull(failure.get());
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

    @Test
    void juliaDeepRefinementCompletesThroughSharedPreciseSampler() throws Exception {
        var job = new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA,
                com.shangin.fractal.coloring.OrbitTrap.NONE),
                new com.shangin.fractal.math.Viewport("2", "0", "1e-80"), 5, 5, 20);
        RenderFrame frame;
        try (var backend = new com.shangin.fractal.render.JuliaDeepZoomRenderBackend()) {
            frame = backend.render(RenderFrame.create(job), () -> false, ignored -> {}, null);
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger published = new AtomicInteger();
        try (var service = new InteractiveAntialiasService()) {
            service.refineDeep(frame, new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    RefinedPixelSnapshot.empty(5, 5), Runnable::run,
                    (region, colors) -> published.incrementAndGet(), completed::countDown,
                    failure -> { error.set(failure); completed.countDown(); });
            assertTrue(completed.await(10, TimeUnit.SECONDS));
        }
        assertNull(error.get());
        assertTrue(published.get() > 0);
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
            service.refine(frame, coloring, RefinedPixelSnapshot.empty(4, 4),
                    callback -> { callbacks.add(callback); queued.countDown(); },
                    (region, colors) -> fail("Cancelled tile must not be published"),
                    () -> {}, exception -> fail(exception));
            assertTrue(queued.await(5, TimeUnit.SECONDS));
            service.cancelCurrent();
            callbacks.forEach(Runnable::run);
            int calculated = samples.get();
            AtomicInteger published = new AtomicInteger();
            CountDownLatch completed = new CountDownLatch(1);
            service.refine(frame, coloring, RefinedPixelSnapshot.empty(4, 4),
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

    @Test
    void lateCancelledWorkerCannotInsertSamplesIntoReplacementFramesCache() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        RenderFrame old = checkerFrame(new FractalCalculator((r, i, limit) -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker was not released");
            } catch (InterruptedException e) { throw new AssertionError(e); }
            return new FractalSample(1, true, 3, 0);
        }));
        RenderFrame replacement = checkerFrame(new FractalCalculator((r, i, limit) ->
                new FractalSample(limit, false, 0, 0)));
        for (int i = 0; i < 16; i++) replacement.fractalData().set(i, new FractalSample(20, false, 0, 0));
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var cache = new AntialiasSampleCache();
        try (var service = new InteractiveAntialiasService(cache, false, 1)) {
            service.refine(old, coloring, RefinedPixelSnapshot.empty(4, 4),
                    Runnable::run, (region, colors) -> fail("Stale tile published"),
                    () -> fail("Stale completion"), exception -> fail(exception));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var completed = new java.util.concurrent.CompletableFuture<int[]>();
            int[] output = new int[16];
            service.refine(replacement, coloring, RefinedPixelSnapshot.empty(4, 4),
                    Runnable::run, (region, colors) -> System.arraycopy(colors, 0, output, 0, colors.length),
                    () -> completed.complete(output), completed::completeExceptionally);
            release.countDown();
            int[] black = new int[16];
            Arrays.fill(black, 0xFF000000);
            assertArrayEquals(black, completed.get(5, TimeUnit.SECONDS));
            assertArrayEquals(black, service.recolorCached(replacement, coloring));
            assertEquals(0, cache.size(), "Cancelled work must not contaminate the new frame");
        } finally { release.countDown(); }
    }

    @Test
    void cachedRefinementRecolorsWithoutSamplingButInvalidatesChangedScale() throws Exception {
        AtomicInteger samples = new AtomicInteger();
        RenderFrame frame = checkerFrame(new FractalCalculator((r, i, limit) -> {
            samples.incrementAndGet();
            return new FractalSample(1, true, 3 + r * 0.01, i * 0.01);
        }));
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0);
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.02, 0.5);
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            int[] first = refineAndWait(service, frame, ice);
            assertEquals(16 * 16, samples.get());
            assertEquals(1, service.lastProfile().cacheBatches());
            assertEquals(16 * 16, service.lastProfile().samples());
            int[] recolored = refineAndWait(service, frame, fire);
            assertFalse(Arrays.equals(first, recolored));
            assertArrayEquals(service.recolorCached(frame, fire), recolored);
            assertEquals(16 * 16, samples.get(), "Retained samples should not be recalculated");
            assertEquals(0, service.lastProfile().cacheBatches());
            var scale = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.04, 0.5);
            refineAndWait(service, frame, scale);
            assertEquals(2 * 16 * 16, samples.get(), "Encoded phases depend on color scale");
        }
    }

    @Test
    void restoredSamplesServeRecolorAndRefinementWithoutSamplingOrAdmission() throws Exception {
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0);
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.02, 0.5);
        RenderFrame source = checkerFrame(new FractalCalculator((r, i, limit) ->
                new FractalSample(1, true, 3 + r * 0.01, i * 0.01)));
        AntialiasSampleCache.Retained retained;
        int[] sourceFire;
        try (var original = new InteractiveAntialiasService(new AntialiasSampleCache(), false)) {
            refineAndWait(original, source, ice);
            retained = original.retainSamples(source);
            assertNull(original.retainSamples(checkerFrame(new FractalCalculator((r, i, l) -> null))));
            sourceFire = original.recolorCached(source, fire);
        }
        assertEquals(16, retained.size());

        AtomicInteger samples = new AtomicInteger();
        FractalCalculator counting = new FractalCalculator((r, i, limit) -> {
            samples.incrementAndGet();
            return new FractalSample(1, true, 3 + r * 0.01, i * 0.01);
        });
        RenderFrame cached = checkerFrame(counting);
        RenderFrame target = checkerFrame(counting);
        var cache = new AntialiasSampleCache();
        try (var service = new InteractiveAntialiasService(cache, true)) {
            service.restoreSamples(cached, retained);
            assertSame(retained, service.retainSamples(cached));
            assertArrayEquals(sourceFire, service.recolorCached(cached, fire), "Reads use restored samples at once");
            assertEquals(0, cache.size(), "Restored samples wait for a write before admission");

            // An unshifted copy keeps them pending; a fully cached pass adds nothing and admits nothing.
            service.reuseFrame(cached, target, new PixelShift(0, 0));
            assertSame(retained, service.retainSamples(target));
            assertArrayEquals(sourceFire, refineAndWait(service, target, fire));
            assertEquals(0, samples.get(), "Restored samples must not be recalculated");
            assertEquals(0, service.lastProfile().cacheBatches());
            assertEquals(0, cache.size());
            assertArrayEquals(sourceFire, service.recolorCached(target, fire));
        }
    }

    @Test
    void restoredSamplesAreAdmittedBeforeShiftsAndNewSamplesAndDroppedForOtherFrames() throws Exception {
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0);
        FractalSample[] grid = new FractalSample[16];
        Arrays.fill(grid, new FractalSample(1, true, 3.5, 0));
        var partial = new AntialiasSampleCache();
        for (int pixel = 0; pixel < 8; pixel++) partial.put(pixel, grid, ice);
        AntialiasSampleCache.Retained retained = partial.retain();

        AtomicInteger samples = new AtomicInteger();
        FractalCalculator counting = new FractalCalculator((r, i, limit) -> {
            samples.incrementAndGet();
            return new FractalSample(1, true, 3, 0);
        });
        var cache = new AntialiasSampleCache();
        try (var service = new InteractiveAntialiasService(cache, false)) {
            // A pan admits the pending samples and shifts them with the frame.
            RenderFrame cached = checkerFrame(counting);
            RenderFrame panned = checkerFrame(counting);
            service.restoreSamples(cached, retained);
            service.reuseFrame(cached, panned, new PixelShift(1, 0));
            assertEquals(6, cache.size(), "Rows 0-1 keep three of their four pixels after a one-pixel pan");
            assertTrue(cache.contains(1) && cache.contains(7) && !cache.contains(0) && !cache.contains(4));
            assertEquals(6, service.retainSamples(panned).size());

            // New candidates admit the pending samples first, so both survive in the cache.
            RenderFrame restored = checkerFrame(counting);
            service.restoreSamples(restored, retained);
            refineAndWait(service, restored, ice);
            assertEquals(8 * 16, samples.get(), "Only the eight pixels without restored samples are sampled");
            assertEquals(16, cache.size());

            // Another frame drops pending samples instead of attaching them to the wrong pixels.
            RenderFrame stale = checkerFrame(counting);
            service.restoreSamples(stale, retained);
            RenderFrame other = checkerFrame(counting);
            refineAndWait(service, other, ice);
            assertNull(service.retainSamples(stale));
            assertEquals(16, service.retainSamples(other).size(), "The other frame keeps only its own samples");
            service.restoreSamples(stale, retained);
            service.reuseFrame(other, checkerFrame(counting), new PixelShift(0, 0));
            assertNull(service.retainSamples(stale));
            assertEquals(0, cache.size());
        }
    }

    @Test
    void everyBuiltInPaletteSharesOnePhaseThreshold() {
        for (PalettePreset preset : PalettePreset.values()) {
            for (boolean deep : new boolean[] {false, true}) {
                assertEquals(PaletteEdgeThresholds.sharedThreshold(deep),
                        PaletteEdgeThresholds.phaseThreshold(preset.palette(), deep),
                        preset + (deep ? " deep" : " direct"));
            }
        }
    }

    @Test
    void steepCustomPalettesGetTheThresholdTheirGradientNeedsBoundedBelow() {
        double shared = PaletteEdgeThresholds.sharedThreshold(false);
        double previous = shared;
        for (double gap : new double[] {0.1, 0.05, 0.02}) {
            Palette steep = sharpStop(gap);
            double threshold = PaletteEdgeThresholds.phaseThreshold(steep, false);
            assertTrue(threshold < previous, "A sharper stop needs a smaller threshold: " + gap);
            // No two palette positions this close may differ by the color edge contrast.
            int span = (int) Math.floor(threshold * 65_535);
            for (int index = 0; index + span < 65_536; index += 7) {
                assertTrue(AdaptivePngExportService.colorContrast(
                                steep.color(index / 65_535.0), steep.color((index + span) / 65_535.0))
                                <= PaletteEdgeThresholds.DIRECT_CONTRAST,
                        "gap " + gap + " at " + index);
            }
            previous = threshold;
        }
        assertEquals(shared * PaletteEdgeThresholds.MIN_THRESHOLD_FRACTION,
                PaletteEdgeThresholds.phaseThreshold(sharpStop(0.0), false),
                "A discontinuous stop must not make every pixel a candidate");
    }

    @Test
    void phaseEdgesCoverTheColorEdgesOfEveryPaletteAndOffset() throws Exception {
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                FractalPreset.MANDELBROT.defaultViewport().zoom(4), 160, 120, 400));
        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2)) {
            calculator.calculate(frame, () -> false, ignored -> {});
        }
        SamplePlane data = frame.samplePlane();
        List<Palette> palettes = new java.util.ArrayList<>();
        for (PalettePreset preset : PalettePreset.values()) palettes.add(preset.palette());
        palettes.add(sharpStop(0.1));
        palettes.add(sharpStop(0.02));
        int colorEdges = 0;
        for (Palette palette : palettes) {
            double direct = PaletteEdgeThresholds.phaseThreshold(palette, false);
            double deep = PaletteEdgeThresholds.phaseThreshold(palette, true);
            for (double offset : new double[] {0.0, 0.37, 1.2}) {
                var coloring = new SmoothPaletteColoring(palette, SmoothPaletteColoring.DEFAULT_COLOR_SCALE, offset);
                int[] colors = AdaptivePngExportService.colorBaseFrame(data, coloring);
                for (int y = 0; y < data.height(); y++) for (int x = 0; x < data.width(); x++) {
                    double strength = InteractiveAntialiasService.phaseEdgeStrength(data, coloring, x, y);
                    if (AdaptivePngExportService.isBaseEdge(data, colors, x, y)) {
                        colorEdges++;
                        assertTrue(strength > direct, palette + " offset " + offset + " edge at " + x + "," + y);
                    }
                    if (InteractiveAntialiasService.isDeepSupersamplingCandidate(data, colors, x, y)) {
                        assertTrue(strength > deep, palette + " offset " + offset + " deep edge at " + x + "," + y);
                    }
                }
            }
        }
        assertTrue(colorEdges > 0, "The fixture must contain color edges");
        long iceEdges = 0;
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        for (int y = 0; y < data.height(); y++) for (int x = 0; x < data.width(); x++) {
            if (InteractiveAntialiasService.isPhaseEdge(data, ice, PaletteEdgeThresholds.sharedThreshold(false), x, y)) {
                iceEdges++;
            }
        }
        assertTrue(iceEdges < data.size() / 4, "Phase edges must stay candidate-only: " + iceEdges);
    }

    @Test
    void paletteSwitchesReproduceADirectPassExactlyWithoutNewSamples() throws Exception {
        assertPaletteSwitchesAreRepeatable(mandelbrotFrame(), false);
    }

    @Test
    void deepPaletteSwitchesReproduceADirectPassExactlyWithoutNewSamples() throws Exception {
        assertPaletteSwitchesAreRepeatable(renderDeepFrame(), true);
    }

    @Test
    void steepCustomPaletteSamplesItsMissingCandidatesOnceAndSwitchesRepeatExactly() throws Exception {
        assertSteepPaletteSwitchesAreRepeatable(mandelbrotFrame(), false);
    }

    @Test
    void deepSteepCustomPaletteSamplesItsMissingCandidatesOnceAndSwitchesRepeatExactly() throws Exception {
        assertSteepPaletteSwitchesAreRepeatable(renderDeepFrame(), true);
    }

    @Test
    void completenessFollowsRetainedSamplesAndIsForgottenByShiftsAndOtherFrames() throws Exception {
        RenderFrame frame = mandelbrotFrame();
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var steep = new SmoothPaletteColoring(sharpStop(0.02), SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0);
        var scaled = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0);
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            assertTrue(service.missesCandidates(frame, ice, false), "No pass has finished yet");
            assertFalse(service.missesCandidates(frame, new com.shangin.fractal.coloring.HistogramPaletteColoring(
                    PalettePreset.ICE.palette(), 0.0, frame.samplePlane()), false), "Only smooth palettes have cached AA");
            refineWholeFrame(service, frame, ice, false);
            assertFalse(service.missesCandidates(frame, ice, false));
            assertTrue(service.missesCandidates(frame, steep, false));
            assertTrue(service.missesCandidates(frame, scaled, false), "Another color scale needs other samples");
            var retained = service.retainSamples(frame);
            assertEquals(PaletteEdgeThresholds.sharedThreshold(false), retained.completeThreshold());

            RenderFrame other = mandelbrotFrame();
            refineWholeFrame(service, other, ice, false);
            assertTrue(service.missesCandidates(frame, ice, false), "The cache now belongs to another frame");
            service.restoreSamples(frame, retained);
            assertFalse(service.missesCandidates(frame, ice, false), "Retained samples keep their completeness");
            // A pass that skips reused AA pixels keeps the larger of the two thresholds.
            service.reuseFrame(frame, frame, new PixelShift(3, 0));
            assertFalse(service.missesCandidates(frame, ice, false), "Shifted samples keep their completeness");
            int width = frame.samplePlane().width(), height = frame.samplePlane().height();
            var reusedValidity = new ValidityMask(width, height);
            reusedValidity.markReady(new RenderRegion(0, 0, 8, 8));
            refineWith(service, frame, steep,
                    new RefinedPixelSnapshot(width, height, new int[width * height], reusedValidity));
            assertTrue(service.missesCandidates(frame, steep, false), "A pass skipping nothing is needed for steep");
            refineWholeFrame(service, frame, steep, false);
            assertFalse(service.missesCandidates(frame, steep, false));
        }
    }

    @Test
    void aPhaseStepJustAboveTheThresholdIsDecidedAsStoredSoRecoloringMatchesThePass() throws Exception {
        double threshold = PaletteEdgeThresholds.sharedThreshold(false);
        double justAbove = Math.nextUp(threshold);
        assertTrue((float) justAbove <= threshold, "The fixture must sit in the float rounding window");
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator((r, i, limit) -> new FractalSample(1, true, 8 + r, 0)),
                FractalPreset.BURNING_SHIP.defaultViewport(), 4, 4, 20));
        for (int index = 0; index < 16; index++) {
            frame.fractalData().setValues(index, 1, index == 5 ? justAbove : 0.0, true, Double.NaN);
        }
        frame.validity().markReady(new RenderRegion(0, 0, 4, 4));
        // Color scale one makes the stored smooth iterations the base phases themselves.
        var coloring = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 1.0, 0);
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            int[] published = refineAndWait(service, frame, coloring);
            assertArrayEquals(published, service.recolorCached(frame, coloring),
                    "Recoloring must show exactly the pixels the pass antialiased");
            assertFalse(service.missesCandidates(frame, coloring, false));
        }
    }

    @Test
    void samplesTheCacheRejectedAreMissingSoEveryRecolorPassStillMatchesADirectPass() throws Exception {
        RenderFrame frame = mandelbrotFrame();
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(),
                SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.7);
        int[] directIce;
        int[] directFire;
        int candidates;
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            refineWholeFrame(service, frame, fire);
            directFire = service.recolorCached(frame, fire);
            refineWholeFrame(service, frame, ice);
            directIce = service.recolorCached(frame, ice);
            candidates = service.retainSamples(frame).size();
        }
        // A budget that holds about half of the frame's candidates.
        var probe = new AntialiasSampleCache();
        FractalSample[] sixteen = new FractalSample[16];
        java.util.Arrays.fill(sixteen, new FractalSample(10, true, 10, 0));
        for (int pixel = 0; pixel < candidates / 2; pixel++) probe.put(pixel, sixteen, ice);
        var tight = new AntialiasSampleCache(probe.usedBytes());
        AntialiasSampleCache.Retained overflowed;
        try (var service = new InteractiveAntialiasService(tight, true)) {
            assertArrayEquals(directIce, refinePublished(service, frame, ice),
                    "The pass shows the AA of every candidate, cached or not");
            assertTrue(tight.dropped());
            int cached = tight.size();
            assertTrue(cached > 0 && cached < candidates, "The fixture must overflow the cache: " + cached);
            assertFalse(service.leavesCandidatesUntested(frame, ice, false), "The pass tested every candidate");
            assertTrue(service.missesCandidates(frame, fire, false), "Rejected samples are missing for a recolor");
            assertTrue(service.missesCandidates(frame, ice, false));
            assertTrue(service.retainSamples(frame).dropped());

            for (int round = 0; round < 2; round++) {
                // The pass the controller runs after each recolor samples only the rejected pixels.
                assertArrayEquals(directFire, refinePublished(service, frame, fire), "Ice -> Fire, round " + round);
                assertEquals((long) (candidates - cached) * 16, service.lastProfile().samples());
                assertArrayEquals(directIce, refinePublished(service, frame, ice), "Fire -> Ice, round " + round);
                assertEquals(cached, tight.size(), "Cached samples stay; rejected ones are not stored");
            }
            overflowed = service.retainSamples(frame);
        }
        // Where the rejected samples fit, the first full pass stores them and clears the mark.
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            service.restoreSamples(frame, overflowed);
            assertTrue(service.missesCandidates(frame, fire, false));
            assertArrayEquals(directFire, refinePublished(service, frame, fire));
            assertTrue(service.lastProfile().samples() > 0);
            assertFalse(service.missesCandidates(frame, fire, false), "Every candidate is stored now");
            assertFalse(service.missesCandidates(frame, ice, false));
            assertFalse(service.retainSamples(frame).dropped());
            refineWholeFrame(service, frame, ice);
            assertEquals(0, service.lastProfile().samples(), "No further pass samples anything");
            assertArrayEquals(directIce, service.recolorCached(frame, ice));
        }
    }

    /** Runs a whole-frame pass and assembles its published tiles. */
    private static int[] refinePublished(InteractiveAntialiasService service, RenderFrame frame,
                                         SmoothPaletteColoring coloring) throws Exception {
        int width = frame.samplePlane().width();
        int[] output = new int[frame.samplePlane().size()];
        var done = new java.util.concurrent.CompletableFuture<int[]>();
        service.refine(frame, coloring, RefinedPixelSnapshot.empty(width, frame.samplePlane().height()),
                Runnable::run, (region, colors) -> {
                    for (int row = 0; row < region.height(); row++) {
                        System.arraycopy(colors, row * region.width(), output,
                                (region.y() + row) * width + region.x(), region.width());
                    }
                }, () -> done.complete(output), done::completeExceptionally);
        return done.get(15, TimeUnit.SECONDS);
    }

    private static void assertPaletteSwitchesAreRepeatable(RenderFrame frame, boolean deepZoom)
            throws Exception {
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(),
                SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.7);
        int[] directIce;
        int[] directFire;
        long fireCandidates;
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            refineWholeFrame(service, frame, fire, deepZoom);
            fireCandidates = service.lastProfile().candidates();
            directFire = service.recolorCached(frame, fire);
        }
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            refineWholeFrame(service, frame, ice, deepZoom);
            assertTrue(service.lastProfile().candidates() > 0, "The fixture must contain AA candidates");
            assertEquals(fireCandidates, service.lastProfile().candidates(),
                    "Ice and Fire must select the same AA pixels");
            directIce = service.recolorCached(frame, ice);
            assertFalse(service.missesCandidates(frame, fire, deepZoom), "Fire needs no further pass");

            for (int round = 0; round < 2; round++) {
                assertArrayEquals(directFire, service.recolorCached(frame, fire), "Ice -> Fire, round " + round);
                assertArrayEquals(directIce, service.recolorCached(frame, ice), "Fire -> Ice, round " + round);
            }
            // A later pass under another palette, as on a revisit, finds no new candidates.
            refineWholeFrame(service, frame, fire, deepZoom);
            assertEquals(0, service.lastProfile().samples(), "No pixel may be sampled again");
            assertEquals(0, service.lastProfile().candidates(), "No candidate may be added");
            assertArrayEquals(directFire, service.recolorCached(frame, fire));
            assertArrayEquals(directIce, service.recolorCached(frame, ice));
        }
    }

    private static void assertSteepPaletteSwitchesAreRepeatable(RenderFrame frame, boolean deepZoom)
            throws Exception {
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var steep = new SmoothPaletteColoring(sharpStop(0.02), SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.3);
        int[] directSteep;
        long steepSamples;
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            refineWholeFrame(service, frame, steep, deepZoom);
            steepSamples = service.lastProfile().samples();
            directSteep = service.recolorCached(frame, steep);
        }
        try (var service = new InteractiveAntialiasService(new AntialiasSampleCache(), true)) {
            refineWholeFrame(service, frame, ice, deepZoom);
            long iceSamples = service.lastProfile().samples();
            int[] directIce = service.recolorCached(frame, ice);
            assertTrue(steepSamples > iceSamples, "The steep palette must select more candidates");
            assertTrue(service.missesCandidates(frame, steep, deepZoom), "Ice's pass cannot cover the steep palette");

            // The pass the controller runs after the recolor samples only the missing candidates.
            refineWholeFrame(service, frame, steep, deepZoom);
            assertEquals(steepSamples - iceSamples, service.lastProfile().samples());
            assertFalse(service.missesCandidates(frame, steep, deepZoom));
            assertFalse(service.missesCandidates(frame, ice, deepZoom));

            for (int round = 0; round < 2; round++) {
                assertArrayEquals(directIce, service.recolorCached(frame, ice), "Steep -> Ice, round " + round);
                assertArrayEquals(directSteep, service.recolorCached(frame, steep), "Ice -> steep, round " + round);
            }
            refineWholeFrame(service, frame, ice, deepZoom);
            assertEquals(0, service.lastProfile().samples(), "Returning to Ice samples nothing");
            assertArrayEquals(directIce, service.recolorCached(frame, ice));
        }
    }

    private static void refineWith(InteractiveAntialiasService service, RenderFrame frame,
                                   SmoothPaletteColoring coloring, RefinedPixelSnapshot reused)
            throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        service.refine(frame, coloring, reused, Runnable::run, (region, colors) -> {},
                completed::countDown, failure::set);
        assertTrue(completed.await(15, TimeUnit.SECONDS));
        assertNull(failure.get());
    }

    private static RenderFrame mandelbrotFrame() throws InterruptedException {
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                FractalPreset.MANDELBROT.defaultViewport().zoom(4), 120, 90, 300));
        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2)) {
            calculator.calculate(frame, () -> false, ignored -> {});
        }
        return frame;
    }

    /** Dark blue up to 0.5, then white within {@code gap}: a sharp stop of an edited palette. */
    private static Palette sharpStop(double gap) {
        return new GradientPalette(List.of(
                new ColorStop(0.0, 0xFF001020),
                new ColorStop(0.5, 0xFF000000),
                new ColorStop(0.5 + gap, 0xFFFFFFFF),
                new ColorStop(1.0, 0xFFFFFFFF)));
    }

    private static RenderFrame checkerFrame(FractalCalculator calculator) {
        RenderFrame frame = RenderFrame.create(new RenderRequest(calculator,
                FractalPreset.BURNING_SHIP.defaultViewport(), 4, 4, 20));
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
            frame.fractalData().set(y * 4 + x, new FractalSample(1, (x + y) % 2 == 0, 3, 0));
        }
        frame.validity().markReady(new RenderRegion(0, 0, 4, 4));
        return frame;
    }

    private static int[] refineAndWait(InteractiveAntialiasService service, RenderFrame frame,
                                      SmoothPaletteColoring coloring) throws Exception {
        var done = new java.util.concurrent.CompletableFuture<int[]>();
        int[] output = new int[16];
        service.refine(frame, coloring, RefinedPixelSnapshot.empty(4, 4), Runnable::run,
                (region, colors) -> System.arraycopy(colors, 0, output, 0, colors.length),
                () -> done.complete(output), done::completeExceptionally);
        return done.get(5, TimeUnit.SECONDS);
    }
}
