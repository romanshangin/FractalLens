package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.ColoringSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Navigates through frames created over spare storage, the way the controller plans
 * them, and compares every frame's samples and ARGB pixels with a frame calculated from
 * scratch on the same grid.
 */
class SpareFrameStorageReuseTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final FormulaDefinition FORMULA =
            FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE);
    private static final ColoringSettings COLORING = new ColoringSettings(PalettePreset.ICE);

    private final Queue<Runnable> preparations = new ArrayDeque<>();
    private final SpareFrameStorage storage = new SpareFrameStorage(preparations::add);
    private final FrameReusePlanner planner = new FrameReusePlanner(storage::create);
    private final ParallelFractalCalculator workers = new ParallelFractalCalculator(2);
    private final List<RenderFrame> frames = new ArrayList<>();
    private RenderFrame active;
    private RenderFrame retained;

    @AfterEach
    void tearDown() {
        workers.close();
        storage.close();
    }

    @Test
    void panZoomResizeAndIterationChangesMatchFreshFrames() throws Exception {
        Viewport origin = new Viewport("-0.75", "0.1", "1.2");
        navigate(job(origin, WIDTH, HEIGHT, 200), Expect.FRESH);
        navigate(job(origin, WIDTH, HEIGHT, 200), Expect.REUSED);

        int[][] directions = {{9, 0}, {-9, 0}, {0, 7}, {0, -7}, {5, 6}, {-5, 6}, {5, -6}, {-5, -6}};
        for (int[] direction : directions) {
            Viewport panned = active.request().viewport()
                    .shiftedByPixels(direction[0], direction[1], WIDTH, HEIGHT);
            RenderFrame frame = navigate(job(panned, WIDTH, HEIGHT, 200), Expect.REUSED);
            assertEquals(new PixelShift(direction[0], direction[1]), lastShift);
            assertTrue(frame.isComplete());
        }

        Viewport beforeZoom = active.request().viewport();
        navigate(job(beforeZoom.zoom(new BigDecimal("1.5")), WIDTH, HEIGHT, 200), Expect.FRESH);
        // Zooming back reuses the retained frame into new storage.
        navigate(job(beforeZoom, WIDTH, HEIGHT, 200), Expect.REUSED);
        navigate(job(beforeZoom.zoom(new BigDecimal("0.5")), WIDTH, HEIGHT, 200), Expect.FRESH);
        navigate(job(beforeZoom, WIDTH, HEIGHT, 200), Expect.REUSED);

        navigate(job(beforeZoom, WIDTH, HEIGHT, 350), Expect.FRESH);
        assertEquals(350, active.fractalData().maxIterations());

        // Grow the window by 6x4 pixels around the same center and pixel spacing.
        Viewport grown = new Viewport(beforeZoom.center(), beforeZoom.imaginaryUnitsPerPixelExact(HEIGHT)
                .multiply(BigDecimal.valueOf(HEIGHT + 4 - 1), beforeZoom.mathContext()));
        navigate(job(grown, WIDTH + 6, HEIGHT + 4, 350), Expect.RESIZED);
        // The completed resized frame prepares a spare of the new size for the next pan.
        navigate(job(grown.shiftedByPixels(4, -3, WIDTH + 6, HEIGHT + 4), WIDTH + 6, HEIGHT + 4, 350),
                Expect.REUSED);
        navigate(job(grown.shiftedByPixels(-8, 5, WIDTH + 6, HEIGHT + 4), WIDTH + 6, HEIGHT + 4, 350),
                Expect.REUSED);
        // Shrinking back crops the reused samples.
        navigate(job(beforeZoom, WIDTH, HEIGHT, 350), Expect.RESIZED);

        assertAntialiasingMatchesFreshFrame(active);
        assertNoSharedStorage();
    }

    @Test
    void cancelledPartialFrameNeverReachesTheNextFrame() throws Exception {
        Viewport origin = new Viewport("-0.75", "0.1", "1.2");
        navigate(job(origin, WIDTH, HEIGHT, 200), Expect.FRESH);

        // A zoom-out whose render is cancelled after its first tile.
        RenderJob zoomedOut = job(origin.zoom(new BigDecimal("1.5")), WIDTH, HEIGHT, 200);
        RenderFrame partial = plan(zoomedOut, true);
        AtomicBoolean cancelled = new AtomicBoolean();
        workers.calculate(partial, cancelled::get, ignored -> cancelled.set(true));
        assertTrue(partial.validity().readyPixelCount() > 0, "The first tile must be ready");
        assertTrue(partial.validity().readyPixelCount() < WIDTH * HEIGHT, "Cancellation must leave missing pixels");
        assertReadySamplesEqual(freshFrame(partial), partial, false);

        // The next pan reuses only the cancelled frame's ready pixels, over new storage.
        RenderJob nextJob = job(zoomedOut.viewport().shiftedByPixels(-6, 3, WIDTH, HEIGHT), WIDTH, HEIGHT, 200);
        RenderFrame next = plan(nextJob, true);
        assertSame(partial, lastSource);
        assertFalse(next.fractalData().sharesStorageWith(partial.fractalData()));
        assertNotSame(next.validity(), partial.validity());
        assertReadySamplesEqual(freshFrame(next), next, true);

        // Late writes into the cancelled frame, as a worker still running after
        // cancellation could make, must not reach the next frame.
        FractalData stale = partial.fractalData();
        for (int index = 0; index < stale.size(); index++) stale.setValues(index, 1, 1.0, true, Double.NaN);
        partial.validity().markReady(new RenderRegion(0, 0, WIDTH, HEIGHT));

        workers.calculate(next, () -> false, ignored -> {});
        assertMatchesFreshFrame(next);
        assertNoSharedStorage();
    }

    @Test
    void sourceWithoutReusablePixelsLeavesTheSpareForTheFreshFrame() throws Exception {
        List<RenderFrame> created = new ArrayList<>();
        FrameReusePlanner counting = new FrameReusePlanner((job, grid) -> {
            RenderFrame frame = storage.create(job, grid);
            created.add(frame);
            return frame;
        });
        Viewport origin = new Viewport("-0.75", "0.1", "1.2");
        RenderFrame complete = counting.plan(null, null, job(origin, WIDTH, HEIGHT, 200)).result().frame();
        workers.calculate(complete, () -> false, ignored -> {});
        RenderFrame empty = counting.plan(null, null, job(origin.zoom(new BigDecimal("1.5")), WIDTH, HEIGHT, 200))
                .result().frame();

        // A pan past the frame edge has no overlap; an unrendered frame has no ready pixel.
        RenderJob beyondEdge = job(origin.shiftedByPixels(WIDTH + 5, 0, WIDTH, HEIGHT), WIDTH, HEIGHT, 200);
        RenderJob overEmpty = job(empty.request().viewport().shiftedByPixels(4, -3, WIDTH, HEIGHT),
                WIDTH, HEIGHT, 200);
        record Step(RenderFrame source, RenderJob job) {}
        for (Step step : List.of(new Step(complete, beyondEdge), new Step(empty, overEmpty))) {
            RenderJob job = step.job();
            runPreparations();
            assertTrue(storage.hasSpareFor(job));
            int framesBefore = created.size();
            int sparesBefore = storage.sparesTaken();

            FrameReuseSelection selection = counting.plan(step.source(), null, job);

            assertNull(selection.sourceFrame());
            assertEquals(framesBefore + 1, created.size(), "Only the visible frame may be created");
            assertSame(created.getLast(), selection.result().frame());
            assertEquals(sparesBefore + 1, storage.sparesTaken(), "The visible frame must take the spare");
            assertEquals(0, selection.result().frame().validity().readyPixelCount());
            workers.calculate(selection.result().frame(), () -> false, ignored -> {});
            assertMatchesFreshFrame(selection.result().frame());
        }
    }

    private enum Expect { FRESH, REUSED, RESIZED }

    private PixelShift lastShift;

    /**
     * Plans the job like the controller, completes the frame and compares it. Same-size
     * steps must take the spare prepared before them; a resize allocates directly.
     */
    private RenderFrame navigate(RenderJob job, Expect expect) throws Exception {
        RenderFrame frame = plan(job, expect != Expect.RESIZED && active != null && sameShape(job));
        assertEquals(expect == Expect.FRESH, lastSource == null, "Unexpected reuse for " + expect);
        workers.calculate(frame, () -> false, ignored -> {});
        assertMatchesFreshFrame(frame);
        // The controller prepares a spare of the completed frame's size.
        storage.prepareFor(job);
        return frame;
    }

    private RenderFrame lastSource;

    private RenderFrame plan(RenderJob job, boolean expectSpare) {
        runPreparations();
        assertEquals(expectSpare, storage.hasSpareFor(job), "Spare readiness before " + job.viewport());
        RenderFrame previous = active;
        FrameReuseSelection selection = planner.plan(previous, retained, job);
        if (expectSpare) assertFalse(storage.hasSpareFor(job), "The frame must take the prepared spare");
        lastSource = selection.sourceFrame();
        lastShift = selection.result().shift().orElse(null);
        active = selection.result().frame();
        if (lastSource != previous) retained = previous;
        frames.add(active);
        return active;
    }

    private boolean sameShape(RenderJob job) {
        return active.request().width() == job.width() && active.request().height() == job.height();
    }

    private void runPreparations() {
        Runnable task;
        while ((task = preparations.poll()) != null) task.run();
    }

    private void assertNoSharedStorage() {
        for (int later = 1; later < frames.size(); later++) {
            for (int earlier = 0; earlier < later; earlier++) {
                assertFalse(frames.get(later).fractalData().sharesStorageWith(frames.get(earlier).fractalData()),
                        "Frames " + earlier + " and " + later + " share sample storage");
                assertNotSame(frames.get(later).validity(), frames.get(earlier).validity());
            }
        }
    }

    private RenderFrame freshFrame(RenderFrame frame) throws Exception {
        RenderFrame control = RenderFrame.create(frame.request(), frame.renderGrid());
        workers.calculate(control, () -> false, ignored -> {});
        assertTrue(control.isComplete());
        return control;
    }

    private void assertMatchesFreshFrame(RenderFrame frame) throws Exception {
        assertTrue(frame.isComplete());
        RenderFrame control = freshFrame(frame);
        assertReadySamplesEqual(control, frame, true);
        assertArrayEquals(argb(control), argb(frame), "ARGB pixels differ from a fresh frame");
    }

    /**
     * Compares ready samples. A planned frame must also hold zero samples at missing
     * pixels; a cancelled tile may leave written but unmarked rows, which are never read.
     */
    private static void assertReadySamplesEqual(RenderFrame control, RenderFrame frame, boolean missingEmpty) {
        FractalData expected = control.fractalData();
        FractalData actual = frame.fractalData();
        int width = frame.request().width();
        for (int y = 0; y < frame.request().height(); y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!frame.validity().isReady(x, y)) {
                    if (missingEmpty) {
                        assertEquals(0, actual.iterations(index), "Missing pixel " + x + "," + y + " holds a sample");
                        assertEquals(0.0, actual.smoothIterations(index));
                        assertFalse(actual.escaped(index));
                    }
                    continue;
                }
                assertEquals(expected.iterations(index), actual.iterations(index), "iterations at " + x + "," + y);
                assertEquals(expected.smoothIterations(index), actual.smoothIterations(index), "smooth at " + x + "," + y);
                assertEquals(expected.escaped(index), actual.escaped(index), "escaped at " + x + "," + y);
            }
        }
    }

    private static int[] argb(RenderFrame frame) {
        int[] pixels = new int[frame.samplePlane().size()];
        new FractalColorizer().color(frame.samplePlane(), IntBuffer.wrap(pixels),
                COLORING.createStrategy(frame.samplePlane()));
        return pixels;
    }

    private void assertAntialiasingMatchesFreshFrame(RenderFrame frame) throws Exception {
        assertArrayEquals(antialiased(freshFrame(frame)), antialiased(frame),
                "Antialiased pixels differ from a fresh frame");
    }

    private static int[] antialiased(RenderFrame frame) throws Exception {
        int width = frame.request().width();
        int height = frame.request().height();
        int[] pixels = argb(frame);
        ColoringStrategy coloring = COLORING.createStrategy(frame.samplePlane());
        try (var service = new InteractiveAntialiasService()) {
            var done = new CompletableFuture<Void>();
            service.refine(frame, coloring,
                    new RefinedPixelSnapshot(width, height, new int[width * height], new ValidityMask(width, height)),
                    Runnable::run,
                    (region, colors) -> {
                        for (int row = 0; row < region.height(); row++) {
                            System.arraycopy(colors, row * region.width(), pixels,
                                    (region.y() + row) * width + region.x(), region.width());
                        }
                    },
                    () -> done.complete(null), done::completeExceptionally);
            done.get(60, TimeUnit.SECONDS);
        }
        return pixels;
    }

    private static RenderJob job(Viewport viewport, int width, int height, int maxIterations) {
        return new RenderJob(FORMULA, viewport, width, height, maxIterations);
    }
}
