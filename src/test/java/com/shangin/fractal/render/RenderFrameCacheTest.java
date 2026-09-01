package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private RenderRequest request(Viewport viewport) {
        return new RenderRequest(calculator, viewport, 10, 10, 100);
    }

    private static RenderFrame completedFrame(RenderRequest request) {
        RenderFrame frame = RenderFrame.create(request);
        frame.validity().markReady(new RenderRegion(0, 0, request.width(), request.height()));
        return frame;
    }
}
