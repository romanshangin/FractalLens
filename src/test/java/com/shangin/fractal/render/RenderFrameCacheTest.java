package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFrameCacheTest {

    private FractalCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FractalCalculator(FractalPreset.MANDELBROT.createFormula());
    }

    @Test
    void exactViewportShouldBeReusableWithDifferentSchedulingHints() {
        RenderFrameCache cache = new RenderFrameCache(10_000);
        RenderFrame frame = completedFrame(request(new Viewport(0.0, 0.0, 2.0)));
        cache.put(frame);

        RenderRequest requested = new RenderRequest(
                calculator,
                frame.request().viewport(),
                10,
                10,
                100,
                new RenderPriority(0.0, 1.0),
                java.util.Optional.of(new RenderRegion(2, 2, 6, 6))
        );

        assertSame(frame, cache.findExact(requested).orElseThrow());
    }

    @Test
    void partialFramesShouldNotEnterTheCache() {
        RenderFrameCache cache = new RenderFrameCache(10_000);

        cache.put(RenderFrame.create(request(new Viewport(0.0, 0.0, 2.0))));

        assertEquals(0, cache.size());
    }

    @Test
    void memoryBudgetShouldEvictTheLeastRecentlyUsedFrame() {
        RenderFrame first = completedFrame(request(new Viewport(-1.0, 0.0, 2.0)));
        RenderFrame second = completedFrame(request(new Viewport(0.0, 0.0, 2.0)));
        RenderFrame third = completedFrame(request(new Viewport(1.0, 0.0, 2.0)));
        long twoFrames = RenderFrameCache.estimatedBytes(first) * 2;
        RenderFrameCache cache = new RenderFrameCache(twoFrames);

        cache.put(first);
        cache.put(second);
        assertSame(first, cache.findExact(first.request()).orElseThrow());
        cache.put(third);

        assertTrue(cache.findExact(second.request()).isEmpty());
        assertSame(first, cache.findExact(first.request()).orElseThrow());
        assertSame(third, cache.findExact(third.request()).orElseThrow());
    }

    @Test
    void iterationLimitAndCalculatorMustMatch() {
        RenderFrameCache cache = new RenderFrameCache(10_000);
        RenderFrame frame = completedFrame(request(new Viewport(0.0, 0.0, 2.0)));
        cache.put(frame);

        RenderRequest otherIterations = new RenderRequest(
                calculator, frame.request().viewport(), 10, 10, 101);
        RenderRequest otherCalculator = new RenderRequest(
                new FractalCalculator(FractalPreset.MANDELBROT.createFormula()),
                frame.request().viewport(), 10, 10, 100);

        assertTrue(cache.findExact(otherIterations).isEmpty());
        assertTrue(cache.findExact(otherCalculator).isEmpty());
    }

    @Test
    void framesOfDifferentFormulasShareTheBudgetWithoutMatchingEachOther() {
        Viewport view = new Viewport(0.0, 0.0, 2.0);
        RenderFrame mandelbrot = completedFrame(request(view));
        RenderFrame julia = completedFrame(formulaRequest(juliaScene(-0.8, 0.156), view));
        RenderFrameCache cache = new RenderFrameCache(RenderFrameCache.estimatedBytes(mandelbrot) * 2);

        cache.put(mandelbrot);
        cache.put(julia);

        assertEquals(2, cache.size(), "A fractal switch must not displace the other fractal's frame");
        assertEquals(RenderFrameCache.estimatedBytes(mandelbrot) * 2, cache.cachedBytes());
        assertSame(mandelbrot, cache.findExact(request(view)).orElseThrow());
        assertSame(julia, cache.findExact(formulaRequest(juliaScene(-0.8, 0.156), view)).orElseThrow());
        assertTrue(cache.findExact(formulaRequest(juliaScene(-0.8, 0.157), view)).isEmpty(),
                "Another Julia constant calculates different samples");
        assertTrue(cache.findExact(formulaRequest(
                com.shangin.fractal.scene.FractalScene.create(FractalPreset.BURNING_SHIP,
                        com.shangin.fractal.coloring.PalettePreset.ICE), view)).isEmpty());

        // The third fractal evicts the least recently used one from the shared budget.
        cache.put(completedFrame(formulaRequest(juliaScene(0.285, 0.01), view)));
        assertEquals(2, cache.size());
        assertTrue(cache.findExact(request(view)).isEmpty());
    }

    @Test
    void retainedAntialiasingSharesTheBudgetAndIsDroppedBeforeItsFrame() {
        RenderFrame older = completedFrame(request(new Viewport(-1.0, 0.0, 2.0)));
        RenderFrame newer = completedFrame(request(new Viewport(1.0, 0.0, 2.0)));
        long frameBytes = RenderFrameCache.estimatedBytes(older);
        RetainedRefinement refinement = refinement(newer);
        long refinementBytes = refinement.estimatedBytes();
        RenderFrameCache cache = new RenderFrameCache(2 * frameBytes + refinementBytes - 1);
        cache.put(older);
        cache.put(newer);
        assertTrue(cache.refinementFor(newer).isEmpty());

        // Retaining AA evicts the least recently used frame to stay within the one budget.
        cache.retainRefinement(newer, refinement);
        assertSame(refinement, cache.refinementFor(newer).orElseThrow());
        assertEquals(frameBytes + refinementBytes, cache.cachedBytes());
        assertTrue(cache.findExact(older.request()).isEmpty());

        // Replacing AA releases the earlier AA bytes; a frame that is not cached keeps nothing.
        RetainedRefinement replacement = refinement(newer);
        cache.retainRefinement(newer, replacement);
        assertSame(replacement, cache.refinementFor(newer).orElseThrow());
        assertEquals(frameBytes + refinementBytes, cache.cachedBytes());
        cache.retainRefinement(older, refinement(older));
        assertTrue(cache.refinementFor(older).isEmpty());
        assertEquals(frameBytes + refinementBytes, cache.cachedBytes());

        // AA that cannot fit beside its frame is dropped while the frame stays cached.
        RenderFrameCache tight = new RenderFrameCache(frameBytes + refinementBytes - 1);
        tight.put(newer);
        tight.retainRefinement(newer, refinement);
        assertTrue(tight.refinementFor(newer).isEmpty());
        assertSame(newer, tight.findExact(newer.request()).orElseThrow());
        assertEquals(frameBytes, tight.cachedBytes());

        // A recalculated frame for the same request replaces the old frame and its AA.
        RenderFrame recalculated = completedFrame(request(new Viewport(1.0, 0.0, 2.0)));
        cache.put(recalculated);
        assertTrue(cache.refinementFor(newer).isEmpty());
        assertTrue(cache.refinementFor(recalculated).isEmpty());
        assertEquals(frameBytes, cache.cachedBytes());
    }

    @Test
    void samplesAreDroppedBeforeColorsAndColorsBeforeFrames() {
        RenderFrame older = completedFrame(request(new Viewport(-1.0, 0.0, 2.0)));
        RenderFrame newer = completedFrame(request(new Viewport(1.0, 0.0, 2.0)));
        long frameBytes = RenderFrameCache.estimatedBytes(older);
        RetainedRefinement colors = refinement(newer);
        RetainedRefinement withSamples = withSamples(colors, 8);
        long sampleBytes = withSamples.estimatedBytes() - colors.estimatedBytes();
        assertTrue(sampleBytes > colors.estimatedBytes(), "The fixture samples must outweigh the colors");

        // Samples that cannot fit beside their frame leave the colors in place.
        RenderFrameCache tight = new RenderFrameCache(frameBytes + colors.estimatedBytes() + sampleBytes / 2);
        tight.put(newer);
        tight.retainRefinement(newer, withSamples);
        RetainedRefinement kept = tight.refinementFor(newer).orElseThrow();
        assertSame(colors.pixels(), kept.pixels());
        assertNull(kept.samples());
        assertEquals(frameBytes + colors.estimatedBytes(), tight.cachedBytes());

        // A new frame takes room from older samples before older frames.
        long frames = 2 * frameBytes;
        RenderFrameCache putting = new RenderFrameCache(frames + colors.estimatedBytes() + sampleBytes - 1);
        putting.put(older);
        putting.retainRefinement(older, withSamples(refinement(older), 8));
        assertNotNull(putting.refinementFor(older).orElseThrow().samples());
        putting.put(newer);
        assertNull(putting.refinementFor(older).orElseThrow().samples());
        assertSame(older, putting.findExact(older.request()).orElseThrow());

        // Newer AA takes room from older samples, keeping its own samples and both frames.
        RenderFrameCache cache = new RenderFrameCache(frames + 2 * colors.estimatedBytes() + sampleBytes);
        cache.put(older);
        cache.retainRefinement(older, withSamples(refinement(older), 8));
        cache.put(newer);
        assertNotNull(cache.refinementFor(older).orElseThrow().samples());
        cache.retainRefinement(newer, withSamples);
        assertNull(cache.refinementFor(older).orElseThrow().samples());
        assertSame(withSamples, cache.refinementFor(newer).orElseThrow());
        assertSame(older, cache.findExact(older.request()).orElseThrow());
        assertEquals(frames + 2 * colors.estimatedBytes() + sampleBytes, cache.cachedBytes());

        // Samples never displace a frame: without room, only the colors are kept.
        RenderFrame third = completedFrame(request(new Viewport(0.0, 1.0, 2.0)));
        RenderFrameCache full = new RenderFrameCache(2 * frameBytes + 2 * colors.estimatedBytes() + sampleBytes / 2);
        full.put(older);
        full.put(third);
        full.retainRefinement(third, withSamples(refinement(third), 8));
        assertNull(full.refinementFor(third).orElseThrow().samples());
        assertSame(older, full.findExact(older.request()).orElseThrow());
    }

    @Test
    void samplesWithoutColorsAreNotKeptWhenTheyWouldDisplaceAFrame() {
        RenderFrame older = completedFrame(request(new Viewport(-1.0, 0.0, 2.0)));
        RenderFrame newer = completedFrame(request(new Viewport(1.0, 0.0, 2.0)));
        long frameBytes = RenderFrameCache.estimatedBytes(older);
        RetainedRefinement samplesOnly = new RetainedRefinement(null, null, withSamples(refinement(newer), 8).samples());
        RenderFrameCache cache = new RenderFrameCache(2 * frameBytes + samplesOnly.estimatedBytes() - 1);
        cache.put(older);
        cache.put(newer);

        cache.retainRefinement(newer, samplesOnly);
        assertTrue(cache.refinementFor(newer).isEmpty());
        assertSame(older, cache.findExact(older.request()).orElseThrow(), "Samples must not displace a frame");
        assertEquals(2 * frameBytes, cache.cachedBytes());

        RenderFrameCache roomy = new RenderFrameCache(2 * frameBytes + samplesOnly.estimatedBytes());
        roomy.put(older);
        roomy.put(newer);
        roomy.retainRefinement(newer, samplesOnly);
        assertSame(samplesOnly, roomy.refinementFor(newer).orElseThrow());
    }

    private static RetainedRefinement withSamples(RetainedRefinement colors, int pixels) {
        var coloring = new com.shangin.fractal.coloring.SmoothPaletteColoring(
                com.shangin.fractal.coloring.PalettePreset.ICE.palette());
        var samples = new com.shangin.fractal.formula.FractalSample[16];
        java.util.Arrays.fill(samples, new com.shangin.fractal.formula.FractalSample(1, true, 3.5, 0));
        var cache = new AntialiasSampleCache();
        for (int pixel = 0; pixel < pixels; pixel++) cache.put(pixel, samples, coloring);
        return new RetainedRefinement(colors.pixels(), colors.coloring(), cache.retain());
    }

    private static RetainedRefinement refinement(RenderFrame frame) {
        int width = frame.request().width(), height = frame.request().height();
        ValidityMask validity = new ValidityMask(width, height);
        validity.markReady(new RenderRegion(0, 0, width, height));
        return new RetainedRefinement(new RefinedPixelSnapshot(width, height, new int[width * height], validity),
                new com.shangin.fractal.scene.ColoringSettings(com.shangin.fractal.coloring.PalettePreset.ICE), null);
    }

    private static com.shangin.fractal.scene.FractalScene juliaScene(double real, double imaginary) {
        return com.shangin.fractal.scene.FractalScene.create(FractalPreset.JULIA,
                        com.shangin.fractal.coloring.PalettePreset.ICE)
                .withJuliaParameters(new com.shangin.fractal.scene.JuliaParameters(real, imaginary));
    }

    private static RenderRequest formulaRequest(com.shangin.fractal.scene.FractalScene scene, Viewport viewport) {
        return new RenderRequest(FormulaDefinition.forScene(scene).createDirectCalculator(), viewport, 10, 10, 100);
    }

    private RenderRequest request(Viewport viewport) {
        return new RenderRequest(calculator, viewport, 10, 10, 100);
    }

    private static RenderFrame completedFrame(RenderRequest request) {
        RenderFrame frame = RenderFrame.create(request);
        frame.validity().markReady(new RenderRegion(0, 0, request.width(), request.height()));
        return frame;
    }
}
