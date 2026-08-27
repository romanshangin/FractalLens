package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RenderFrameTest {

    private static final int WIDTH = 100;
    private static final int HEIGHT = 70;
    private static final int MAX_ITERATIONS = 300;

    private RenderRequest request;
    private ParallelFractalCalculator parallelCalculator;

    @BeforeEach
    void setUp() {
        FractalPreset preset = FractalPreset.MANDELBROT;
        FractalCalculator calculator = new FractalCalculator(preset.createFormula());
        request = new RenderRequest(calculator, preset.defaultViewport(), WIDTH, HEIGHT, MAX_ITERATIONS);
        parallelCalculator = new ParallelFractalCalculator();
    }

    @Test
    void shouldCreateFrameFromRequest() {
        RenderFrame frame = RenderFrame.create(request);

        assertSame(request, frame.request());

        assertEquals(WIDTH, frame.fractalData().width());

        assertEquals(HEIGHT, frame.fractalData().height());

        assertEquals(WIDTH, frame.validity().width());

        assertEquals(HEIGHT, frame.validity().height());
    }

    @Test
    void newFrameShouldBeIncomplete() {
        RenderFrame frame = RenderFrame.create(request);

        assertFalse(frame.isComplete());

        assertEquals(0, frame.validity().readyPixelCount());
    }

    @Test
    void frameShouldBecomeCompleteWhenAllPixelsAreReady() {
        RenderFrame frame = RenderFrame.create(request);

        frame.validity().markReady(new RenderRegion(0, 0, WIDTH, HEIGHT));

        assertTrue(frame.isComplete());

        assertEquals(WIDTH * HEIGHT, frame.validity().readyPixelCount());
    }

    @Test
    void createShouldRejectNullRequest() {
        assertThrows(NullPointerException.class, () -> RenderFrame.create(null));
    }

    @Test
    void cancelledRenderShouldPreserveCompletedRegions() throws InterruptedException {
        RenderFrame frame = RenderFrame.create(request);
        AtomicInteger completedRegions = new AtomicInteger();
        RenderFrame result = parallelCalculator.calculate(frame, () -> completedRegions.get() >= 5, region -> completedRegions.incrementAndGet());
        assertSame(frame, result);
        assertTrue(result.validity().readyPixelCount() > 0);
        assertFalse(result.isComplete());
    }

    @Test
    void completeFrameShouldNotBeCalculatedAgain() throws InterruptedException {

        RenderFrame frame = RenderFrame.create(request);

        parallelCalculator.calculate(frame, () -> false, region -> {
        });

        assertTrue(frame.isComplete());

        List<RenderRegion> secondRenderRegions = new CopyOnWriteArrayList<>();

        RenderFrame result = parallelCalculator.calculate(frame, () -> false, secondRenderRegions::add);

        assertSame(frame, result);
        assertTrue(result.isComplete());

        assertTrue(secondRenderRegions.isEmpty(), "Already completed regions should not be rendered again");
    }

    @Test
    void shouldResumeIncompleteFrame() throws InterruptedException {

        AtomicInteger completedRegions = new AtomicInteger();

        RenderFrame frame = RenderFrame.create(request);

        parallelCalculator.calculate(frame, () -> completedRegions.get() >= 5, region -> completedRegions.incrementAndGet());

        assertFalse(frame.isComplete());
        assertTrue(frame.validity().readyPixelCount() > 0);

        int readyBeforeResume = frame.validity().readyPixelCount();

        List<RenderRegion> resumedRegions = new CopyOnWriteArrayList<>();

        parallelCalculator.calculate(frame, () -> false, resumedRegions::add);

        assertTrue(frame.isComplete());

        assertEquals(request.width() * request.height(), frame.validity().readyPixelCount());

        assertTrue(readyBeforeResume < frame.validity().readyPixelCount());
    }
}