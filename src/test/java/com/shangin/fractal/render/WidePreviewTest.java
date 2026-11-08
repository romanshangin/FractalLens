package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.WidePreviewSettings;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class WidePreviewTest {
    private static RenderJob job(Viewport view, int width, int height) {
        return new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                view, width, height, 300);
    }

    @Test void completedDeepReserveRecolorsOnceWithoutConstructingAnotherBackend() throws Exception {
        var factories = new AtomicInteger();
        var calculations = new AtomicInteger();
        var publications = new AtomicInteger();
        var snapshots = new LinkedBlockingQueue<WidePreviewSnapshot>();
        var visible = job(new Viewport("-0.743643887037151", "0.13182590420533", "1e-80"), 80, 60);
        try (var service = new WidePreviewService(Runnable::run, () -> {
            factories.incrementAndGet();
            return new RenderBackend() {
                public RenderFrame render(RenderFrame frame, BooleanSupplier stop,
                                          Consumer<RenderRegion> region, Consumer<TileTimingStats> timing) {
                    calculations.incrementAndGet();
                    for (int pixel = 0; pixel < frame.samplePlane().size(); pixel++)
                        frame.fractalData().set(pixel, new FractalSample(10, true, 3, 0));
                    frame.validity().markReady(new RenderRegion(0, 0, frame.job().width(), frame.job().height()));
                    return frame;
                }
                public void close() {}
            };
        })) {
            Consumer<WidePreviewSnapshot> publish = snapshot -> { publications.incrementAndGet(); snapshots.add(snapshot); };
            var errors = new LinkedBlockingQueue<Throwable>();
            service.request(visible, new ColoringSettings(PalettePreset.ICE), WidePreviewSettings.CANDIDATE, publish, errors::add);
            var first = snapshots.poll(5, TimeUnit.SECONDS); assertNotNull(first);
            service.request(visible, new ColoringSettings(PalettePreset.ICE, ColoringSettings.DEFAULT_COLOR_SCALE, 0.25),
                    WidePreviewSettings.CANDIDATE, publish, errors::add);
            var second = snapshots.poll(5, TimeUnit.SECONDS); assertNotNull(second);
            var field = WidePreviewService.class.getDeclaredField("coordinator"); field.setAccessible(true);
            ((ExecutorService) field.get(service)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(errors.isEmpty());
            assertEquals(1, factories.get(), "A complete reserve must not allocate another backend/cache");
            assertEquals(1, calculations.get(), "A palette update must not rebuild a deep reference orbit");
            assertEquals(2, publications.get(), "Each complete recolor publishes exactly once");
            assertFalse(java.util.Arrays.equals(first.copyColors(), second.copyColors()));
        }
    }

    @Test void validatesSettingsBeforeStartingWork() {
        assertSame(WidePreviewSettings.CANDIDATE, WidePreviewSettings.DEFAULT);
        assertFalse(WidePreviewSettings.DISABLED.enabled());
        assertTrue(WidePreviewSettings.CANDIDATE.enabled());
        assertSame(WidePreviewSettings.CANDIDATE, WidePreviewSettings.of(true));
        assertSame(WidePreviewSettings.DISABLED, WidePreviewSettings.of(false));
        assertEquals(WidePreviewSettings.CANDIDATE,
                new WidePreviewSettings(true, WidePreviewSettings.DISABLED.extent(), WidePreviewSettings.DISABLED.pixelStep(),
                        WidePreviewSettings.DISABLED.sampleLimit(), WidePreviewSettings.DISABLED.workMillis()),
                "The switch changes only enablement");
        for (double extent : new double[]{0, 0.9, 4.1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> new WidePreviewSettings(extent, 8, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new WidePreviewSettings(2, 0, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new WidePreviewSettings(2, 8, 262_145, 100));
        assertThrows(IllegalArgumentException.class, () -> new WidePreviewSettings(2, 8, 100, 2001));
        assertThrows(IllegalArgumentException.class, () -> new WidePreviewSettings(2, 8, 100, 0));
    }

    @Test void coversBothAxesWithSquarePhysicalCellsAndBoundedStorage() {
        for (int[] size : new int[][]{{1512, 982}, {3024, 1964}, {17, 8}, {2, 2}, {100000, 100000}, {Integer.MAX_VALUE, 2}}) {
            RenderJob visible = job(new Viewport(-0.75, 0, 2.4), size[0], size[1]);
            var plan = WidePreviewPlan.create(visible, WidePreviewSettings.CANDIDATE, null);
            var coarse = plan.job();
            assertTrue((long) coarse.width() * coarse.height() <= plan.settings().sampleLimit());
            double step = coarse.viewport().imaginaryUnitsPerPixelExact(coarse.height())
                    .divide(visible.viewport().imaginaryUnitsPerPixelExact(visible.height()), visible.viewport().mathContext()).doubleValue();
            assertEquals(plan.physicalStep(), step, 1e-10);
            assertTrue(coarse.viewport().scale() >= visible.viewport().scale() * 2);
            assertTrue(coarse.viewport().visibleWidth(coarse.width(), coarse.height())
                    >= visible.viewport().visibleWidth(visible.width(), visible.height()) * 2);
            assertEquals(visible.viewport().center(), coarse.viewport().center());
        }
        var huge = WidePreviewPlan.create(job(new Viewport(-0.75, 0, 2.4), Integer.MAX_VALUE, Integer.MAX_VALUE),
                new WidePreviewSettings(4, 2, 16, 10), null);
        assertTrue((long) huge.job().width() * huge.job().height() <= 16);
    }

    @Test void configuredSizeAndQualityControlTheGridWithinTheExistingBudget() {
        var visible = job(new Viewport(-0.75, 0, 2.4), 640, 480);
        for (double extent : new double[] {1.5, 2, 3, 4}) {
            for (int step : new int[] {16, 8, 4, 2}) {
                var settings = new WidePreviewSettings(true, extent, step, 262_144, 2_000);
                var plan = WidePreviewPlan.create(visible, settings, null);
                assertTrue(plan.physicalStep() >= step);
                assertTrue((long) plan.job().width() * plan.job().height() <= settings.sampleLimit());
                assertTrue(plan.job().viewport().scale() >= visible.viewport().scale() * extent);
                if (extent == 1.5 && step >= 4) assertEquals(step, plan.physicalStep());
                assertEquals(visible.viewport().center(), plan.job().viewport().center());
            }
        }
    }

    @Test void preservesDeepDecimalCoordinatesAndSelectsCorrectFormulaBackend() {
        var view = new Viewport("-0.743643887037151000000000000000000000001", "0.131825904205330000000000000000000000002", "1e-80");
        var coarse = WidePreviewPlan.create(job(view, 3024, 1964), WidePreviewSettings.CANDIDATE, null).job();
        assertEquals(view.center(), coarse.viewport().center());
        assertNotEquals(coarse.preciseGrid().realAt(0), coarse.preciseGrid().realAt(1));
        try (var direct = new DirectDoubleRenderBackend();
             var mandelbrot = new MandelbrotPerturbationRenderBackend(1);
             var julia = new JuliaDeepZoomRenderBackend(1)) {
            assertFalse(direct.supports(coarse));
            assertTrue(mandelbrot.supports(coarse));
            var juliaJob = new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE),
                    coarse.viewport(), coarse.width(), coarse.height(), 300);
            assertFalse(mandelbrot.supports(juliaJob));
            assertTrue(julia.supports(juliaJob));
        }
    }

    @Test void retainsCoarseSamplesDuringPansInAllDirectionsAndDropsIncompatibleSamples() {
        var visible = job(new Viewport(-0.75, 0, 0.5), 800, 600);
        var sourcePlan = WidePreviewPlan.create(visible, WidePreviewSettings.CANDIDATE, null);
        var source = RenderFrame.create(sourcePlan.job());
        var area = new RenderRegion(0, 0, sourcePlan.job().width(), sourcePlan.job().height());
        source.validity().markReady(area);
        source.fractalData().set(0, new FractalSample(9, false, 0, 0));
        for (int[] delta : new int[][]{{0,0}, {16,0}, {-16,0}, {0,16}, {0,-16}, {16,-16}, {3000,0}}) {
            var moved = job(visible.viewport().shiftedByPixels(delta[0], delta[1], 800, 600), 800, 600);
            var next = WidePreviewPlan.create(moved, WidePreviewSettings.CANDIDATE, source);
            var reused = new FrameReusePlanner().plan(source, next.job()).frame();
            assertEquals(delta[0] == 3000 ? 0 : (sourcePlan.job().width() - Math.abs(delta[0] / 8))
                    * (sourcePlan.job().height() - Math.abs(delta[1] / 8)), reused.validity().readyPixelCount());
        }
        var next = WidePreviewPlan.create(job(visible.viewport().zoom(2), 800, 600), WidePreviewSettings.CANDIDATE, source);
        assertEquals(0, new FrameReusePlanner().plan(source, next.job()).frame().validity().readyPixelCount());
        assertEquals(0, new FrameReusePlanner().plan(source, new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE), sourcePlan.job().viewport(),
                sourcePlan.job().width(), sourcePlan.job().height(), 300)).frame().validity().readyPixelCount());
    }

    @Test void calculatesRealSamplesWithoutTouchingVisibleValidity() throws Exception {
        var visible = job(new Viewport(-0.75, 0, 2.4), 80, 60);
        var exact = RenderFrame.create(visible);
        var ready = new CompletableFuture<WidePreviewSnapshot>();
        var plan = WidePreviewPlan.create(visible, WidePreviewSettings.CANDIDATE, null);
        try (var service = new WidePreviewService(Runnable::run)) {
            service.request(visible, new ColoringSettings(PalettePreset.ICE), WidePreviewSettings.CANDIDATE,
                    snapshot -> { if (snapshot.readySamples() == plan.job().width() * plan.job().height()) ready.complete(snapshot); },
                    ready::completeExceptionally);
            var snapshot = ready.get(10, TimeUnit.SECONDS);
            var control = RenderFrame.create(plan.job());
            try (var direct = new DirectDoubleRenderBackend(new ParallelFractalCalculator(1))) {
                direct.render(control, () -> false, ignored -> {}, null);
            }
            int[] expected = new int[plan.job().width() * plan.job().height()];
            new FractalColorizer().color(control.samplePlane(), java.nio.IntBuffer.wrap(expected), new ColoringSettings(PalettePreset.ICE).createStrategy());
            assertArrayEquals(expected, snapshot.copyColors());
            int[] upload = new int[expected.length]; snapshot.copyColorsInto(upload);
            assertArrayEquals(expected, upload);
            assertThrows(IllegalArgumentException.class, () -> snapshot.copyColorsInto(new int[1]));
            assertEquals(0, exact.validity().readyPixelCount());
            var copy = snapshot.copyColors(); copy[0] = 123;
            assertArrayEquals(expected, snapshot.copyColors());
        }
    }

    @Test void deepReserveUsesTheVisibleIterationLimitInsteadOfPaintingSlowEscapesAsInterior() throws Exception {
        // Near this point every sample escapes only after more than 4096 iterations.
        var view = new Viewport("-0.743643887037158704752191506114774", "0.131825904205311970493132056385139", "1e-16");
        var visible = new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE), view, 80, 60, 20_000);
        var plan = WidePreviewPlan.create(visible, WidePreviewSettings.CANDIDATE, null);
        assertEquals(visible.maxIterations(), plan.job().maxIterations());
        var control = RenderFrame.create(plan.job());
        try (var deep = new MandelbrotPerturbationRenderBackend(1)) {
            assertFalse(new DirectDoubleRenderBackend().supports(plan.job()), "The fixture must use the deep path");
            deep.render(control, () -> false, ignored -> {}, null);
        }
        int slow = 0;
        for (int pixel = 0; pixel < control.samplePlane().size(); pixel++)
            if (control.fractalData().escaped(pixel) && control.fractalData().iterations(pixel) > 4096) slow++;
        assertTrue(slow > control.samplePlane().size() / 2, "The fixture must escape after the former 4096 cap");
        int[] expected = new int[control.samplePlane().size()];
        var coloring = new ColoringSettings(PalettePreset.ICE);
        new FractalColorizer().color(control.samplePlane(), java.nio.IntBuffer.wrap(expected), coloring.createStrategy());
        var ready = new CompletableFuture<WidePreviewSnapshot>();
        try (var service = new WidePreviewService(Runnable::run)) {
            service.request(visible, coloring, WidePreviewSettings.CANDIDATE,
                    snapshot -> { if (snapshot.readySamples() == expected.length) ready.complete(snapshot); },
                    ready::completeExceptionally);
            assertArrayEquals(expected, ready.get(20, TimeUnit.SECONDS).copyColors(),
                    "Reserve colors must equal the deep reference at the visible limit");
        }
    }

    @Test void workBudgetStopsPartialCalculationAndBoundsQueuedPublications() throws Exception {
        var callbacks = new LinkedBlockingQueue<Runnable>();
        var drained = new CountDownLatch(1);
        var limited = new WidePreviewSettings(2, 8, 1000, 10);
        var visible = job(new Viewport(-0.75, 0, 2.4), 80, 60);
        try (var service = new WidePreviewService(callbacks::add, () -> new RenderBackend() {
            public RenderFrame render(RenderFrame frame, BooleanSupplier stop, Consumer<RenderRegion> region, Consumer<TileTimingStats> timings) {
                assertEquals(300, frame.job().maxIterations(), "The reserve uses the visible iteration limit");
                var row = new RenderRegion(0, 0, frame.job().width(), 1);
                frame.validity().markReady(row); region.accept(row);
                while (!stop.getAsBoolean()) Thread.onSpinWait();
                return frame;
            }
            public void close() { drained.countDown(); }
        })) {
            service.request(visible, new ColoringSettings(PalettePreset.ICE), limited, ignored -> {}, error -> fail(error));
            assertTrue(drained.await(3, TimeUnit.SECONDS), "The wall budget must stop calculation even without navigation");
            assertEquals(1, callbacks.size(), "Partial and final rasters share one queued callback");
            service.close();
            callbacks.take().run();
        }
    }

    @Test void replacesDensityAndJuliaParametersWithoutReusingOldSamples() throws Exception {
        var results = new LinkedBlockingQueue<Integer>();
        var source = job(new Viewport(-0.75, 0, 0.5), 80, 60);
        try (var service = new WidePreviewService(Runnable::run, () -> new RenderBackend() {
            public RenderFrame render(RenderFrame frame, BooleanSupplier stop, Consumer<RenderRegion> region, Consumer<TileTimingStats> timings) {
                results.add(frame.validity().readyPixelCount());
                frame.validity().markReady(new RenderRegion(0, 0, frame.job().width(), frame.job().height()));
                return frame;
            }
            public void close() {}
        })) {
            var colors = new ColoringSettings(PalettePreset.ICE);
            var ready = new LinkedBlockingQueue<WidePreviewSnapshot>();
            service.request(source, colors, WidePreviewSettings.CANDIDATE, ready::add, error -> fail(error));
            assertNotNull(ready.poll(5, TimeUnit.SECONDS));
            var changed = new WidePreviewSettings(3, 16, 1000, 100);
            service.request(source, colors, changed, ready::add, error -> fail(error));
            assertNotNull(ready.poll(5, TimeUnit.SECONDS));
            var scene = com.shangin.fractal.scene.FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE);
            for (double real : new double[]{-0.8, -0.7}) {
                var julia = new RenderJob(FormulaDefinition.forScene(scene.withJuliaParameters(
                        new com.shangin.fractal.scene.JuliaParameters(real, 0.156))), source.viewport(), 80, 60, 300);
                service.request(julia, colors, changed, ready::add, error -> fail(error));
                assertNotNull(ready.poll(5, TimeUnit.SECONDS));
            }
            assertEquals(java.util.List.of(0, 0, 0, 0), java.util.List.copyOf(results));
        }
    }

    @Test void cancelsQueuedPublicationAndDrainsWorkersBeforeSuccessorReusesPartialSamples() throws Exception {
        var callbackQueue = new LinkedBlockingQueue<Runnable>();
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var successor = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        var visible = job(new Viewport(-0.75, 0, 0.5), 80, 60);
        try (var service = new WidePreviewService(callbackQueue::add, () -> new RenderBackend() {
            public RenderFrame render(RenderFrame frame, BooleanSupplier stop, Consumer<RenderRegion> region, Consumer<TileTimingStats> timings) throws InterruptedException {
                if (runs.incrementAndGet() == 1) {
                    var row = new RenderRegion(0, 0, frame.job().width(), 1);
                    frame.validity().markReady(row); region.accept(row); started.countDown();
                    while (!stop.getAsBoolean()) Thread.onSpinWait();
                    cancelled.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } else {
                    assertEquals(frame.job().width(), frame.validity().readyPixelCount());
                    successor.countDown();
                }
                return frame;
            }
            public void close() {}
        })) {
            service.request(visible, new ColoringSettings(PalettePreset.ICE), WidePreviewSettings.CANDIDATE, x -> publications.incrementAndGet(), error -> fail(error));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            service.cancel();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            service.request(visible, new ColoringSettings(PalettePreset.ICE), WidePreviewSettings.CANDIDATE, x -> publications.incrementAndGet(), error -> fail(error));
            assertEquals(1, runs.get(), "A successor cannot enter while the old worker is still registered");
            callbackQueue.take().run();
            assertEquals(0, publications.get(), "Cancelled pixels must never publish");
            release.countDown();
            assertTrue(successor.await(5, TimeUnit.SECONDS));
            service.close(); service.close();
            for (Runnable callback : callbackQueue) callback.run();
            assertEquals(0, publications.get());
        } finally { release.countDown(); }
    }
}
