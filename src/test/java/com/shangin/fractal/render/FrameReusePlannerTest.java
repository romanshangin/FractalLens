package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FrameReusePlannerTest {

    private static final int WIDTH = 100;
    private static final int HEIGHT = 70;
    private static final int MAX_ITERATIONS = 300;

    private FractalCalculator calculator;
    private ParallelFractalCalculator parallelCalculator;
    private FrameReusePlanner planner;

    private Viewport sourceViewport;
    private RenderRequest sourceRequest;

    private static void assertFractalDataEquals(FractalData expected, FractalData actual) {
        assertEquals(expected.width(), actual.width());

        assertEquals(expected.height(), actual.height());

        assertEquals(expected.maxIterations(), actual.maxIterations());

        assertEquals(expected.size(), actual.size());

        for (int i = 0; i < expected.size(); i++) {

            assertEquals(expected.iterations(i), actual.iterations(i), "Iterations differ at index " + i);

            assertEquals(expected.escaped(i), actual.escaped(i), "Escaped differs at index " + i);

            assertEquals(Double.doubleToLongBits(expected.smoothIterations(i)), Double.doubleToLongBits(actual.smoothIterations(i)), "Smooth iterations differ at index " + i);
        }
    }

    @BeforeEach
    void setUp() {
        FractalPreset preset = FractalPreset.MANDELBROT;

        calculator = new FractalCalculator(preset.createFormula());

        parallelCalculator = new ParallelFractalCalculator(4);

        planner = new FrameReusePlanner();

        sourceViewport = preset.defaultViewport();

        sourceRequest = new RenderRequest(calculator, sourceViewport, WIDTH, HEIGHT, MAX_ITERATIONS);
    }

    @AfterEach
    void tearDown() {
        parallelCalculator.close();
    }

    @Test
    void shouldCreateFreshFrameWhenSourceIsNull() {
        RenderFrame result = planner.createFrame(null, sourceRequest);

        assertNotNull(result);

        assertSame(sourceRequest, result.request());

        assertEquals(0, result.validity().readyPixelCount());

        assertFalse(result.isComplete());
    }

    @Test
    void shouldReuseCompletedPixelsAfterIntegerPan() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        assertTrue(sourceFrame.isComplete());

        PixelShift shift = new PixelShift(10, 7);

        Viewport targetViewport = sourceViewport.shiftedByPixels(shift.dx(), shift.dy(), WIDTH, HEIGHT);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        RenderFrame targetFrame = planner.createFrame(sourceFrame, targetRequest);

        assertNotSame(sourceFrame, targetFrame);

        int expectedReusablePixels = (WIDTH - Math.abs(shift.dx())) * (HEIGHT - Math.abs(shift.dy()));

        assertEquals(expectedReusablePixels, targetFrame.validity().readyPixelCount());

        assertFalse(targetFrame.isComplete());
    }

    @Test
    void shouldShiftValidityMaskToTargetCoordinates() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        PixelShift shift = new PixelShift(10, 7);

        Viewport targetViewport = sourceViewport.shiftedByPixels(shift.dx(), shift.dy(), WIDTH, HEIGHT);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        RenderFrame targetFrame = planner.createFrame(sourceFrame, targetRequest);

        /*
         * source (0, 0)
         *      ↓ shift (10, 7)
         * target (10, 7)
         */
        assertTrue(targetFrame.validity().isReady(10, 7));

        /* The newly exposed area on the left has no source-frame data. */
        assertFalse(targetFrame.validity().isReady(0, 7));

        /* The newly exposed area at the top is also invalid. */
        assertFalse(targetFrame.validity().isReady(10, 0));
    }

    @Test
    void shouldPreserveExactRenderGridForReusedPixels() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        PixelShift shift = new PixelShift(10, 7);

        Viewport targetViewport = sourceViewport.shiftedByPixels(shift.dx(), shift.dy(), WIDTH, HEIGHT);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        RenderFrame targetFrame = planner.createFrame(sourceFrame, targetRequest);

        int sourceX = 20;
        int sourceY = 15;

        int targetX = sourceX + shift.dx();

        int targetY = sourceY + shift.dy();

        assertEquals(Double.doubleToLongBits(sourceFrame.renderGrid().realAt(sourceX)), Double.doubleToLongBits(targetFrame.renderGrid().realAt(targetX)));

        assertEquals(Double.doubleToLongBits(sourceFrame.renderGrid().imaginaryAt(sourceY)), Double.doubleToLongBits(targetFrame.renderGrid().imaginaryAt(targetY)));
    }

    @Test
    void shouldCreateFreshFrameWhenScaleChanges() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        Viewport targetViewport = new Viewport(sourceViewport.centerReal(), sourceViewport.centerImaginary(), sourceViewport.scale() * 0.8);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        RenderFrame targetFrame = planner.createFrame(sourceFrame, targetRequest);

        assertEquals(0, targetFrame.validity().readyPixelCount());

        assertFalse(targetFrame.isComplete());
    }

    @Test
    void shouldCreateFreshFrameWhenCalculatorChanges() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        FractalCalculator anotherCalculator = new FractalCalculator(FractalPreset.JULIA.createFormula());

        RenderRequest targetRequest = new RenderRequest(anotherCalculator, sourceViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        RenderFrame targetFrame = planner.createFrame(sourceFrame, targetRequest);

        assertEquals(0, targetFrame.validity().readyPixelCount());
    }

    @Test
    void shouldPreserveExactRenderGridForReusedPixelsWithNegativeShift() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        PixelShift shift = new PixelShift(-10, -7);

        Viewport targetViewport = sourceViewport.shiftedByPixels(shift.dx(), shift.dy(), WIDTH, HEIGHT);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        RenderFrame targetFrame = planner.createFrame(sourceFrame, targetRequest);

        int sourceX = 20;
        int sourceY = 15;

        int targetX = sourceX + shift.dx();

        int targetY = sourceY + shift.dy();

        assertEquals(Double.doubleToLongBits(sourceFrame.renderGrid().realAt(sourceX)), Double.doubleToLongBits(targetFrame.renderGrid().realAt(targetX)));

        assertEquals(Double.doubleToLongBits(sourceFrame.renderGrid().imaginaryAt(sourceY)), Double.doubleToLongBits(targetFrame.renderGrid().imaginaryAt(targetY)));
    }

    @Test
    void reusedFrameAfterCompletionShouldMatchFullRender() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        assertTrue(sourceFrame.isComplete());

        PixelShift shift = new PixelShift(10, 7);

        Viewport targetViewport = sourceViewport.shiftedByPixels(shift.dx(), shift.dy(), WIDTH, HEIGHT);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        // Create a partially reused frame.
        RenderFrame reusedFrame = planner.createFrame(sourceFrame, targetRequest);

        assertFalse(reusedFrame.isComplete());

        assertTrue(reusedFrame.validity().readyPixelCount() > 0);

        // Calculate only the missing regions.
        parallelCalculator.calculate(reusedFrame, () -> false, region -> {
        });

        assertTrue(reusedFrame.isComplete());

        /* Calculate the reference from scratch on the same render grid. */
        RenderFrame referenceFrame = RenderFrame.create(targetRequest, reusedFrame.renderGrid());

        parallelCalculator.calculate(referenceFrame, () -> false, region -> {
        });

        assertTrue(referenceFrame.isComplete());

        assertFractalDataEquals(referenceFrame.fractalData(), reusedFrame.fractalData());
    }

    @Test
    void reusedFrameWithNegativeShiftShouldMatchFullRender() throws InterruptedException {

        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(sourceFrame, () -> false, region -> {
        });

        PixelShift shift = new PixelShift(-10, -7);

        Viewport targetViewport = sourceViewport.shiftedByPixels(shift.dx(), shift.dy(), WIDTH, HEIGHT);

        RenderRequest targetRequest = new RenderRequest(calculator, targetViewport, WIDTH, HEIGHT, MAX_ITERATIONS);

        RenderFrame reusedFrame = planner.createFrame(sourceFrame, targetRequest);

        parallelCalculator.calculate(reusedFrame, () -> false, region -> {
        });

        RenderFrame referenceFrame = RenderFrame.create(targetRequest, reusedFrame.renderGrid());

        parallelCalculator.calculate(referenceFrame, () -> false, region -> {
        });

        assertTrue(reusedFrame.isComplete());
        assertTrue(referenceFrame.isComplete());

        assertFractalDataEquals(referenceFrame.fractalData(), reusedFrame.fractalData());
    }

    @Test
    void cancelledPanRenderShouldPreserveProgressAndResumeToExactResult() throws InterruptedException {
        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);

        parallelCalculator.calculate(
                sourceFrame,
                () -> false,
                ignored -> {}
        );

        PixelShift shift = new PixelShift(40, 0);
        Viewport targetViewport = sourceViewport.shiftedByPixels(
                shift.dx(),
                shift.dy(),
                WIDTH,
                HEIGHT
        );

        RenderRequest targetRequest = new RenderRequest(
                calculator,
                targetViewport,
                WIDTH,
                HEIGHT,
                MAX_ITERATIONS
        );

        RenderFrame reusedFrame = planner.createFrame(
                sourceFrame,
                targetRequest
        );

        int reusedPixelCount = reusedFrame.validity().readyPixelCount();
        AtomicInteger completedRegions = new AtomicInteger();
        AtomicInteger timingCallbacks = new AtomicInteger();

        try (ParallelFractalCalculator cancellableCalculator =
                     new ParallelFractalCalculator(1)) {

            cancellableCalculator.calculate(
                    reusedFrame,
                    () -> completedRegions.get() >= 1,
                    ignored -> completedRegions.incrementAndGet(),
                    ignored -> timingCallbacks.incrementAndGet()
            );
        }

        assertEquals(1, completedRegions.get());
        assertEquals(0, timingCallbacks.get());
        assertTrue(reusedFrame.validity().readyPixelCount() > reusedPixelCount);
        assertFalse(reusedFrame.isComplete());

        parallelCalculator.calculate(
                reusedFrame,
                () -> false,
                ignored -> {}
        );

        RenderFrame referenceFrame = RenderFrame.create(
                targetRequest,
                reusedFrame.renderGrid()
        );

        parallelCalculator.calculate(
                referenceFrame,
                () -> false,
                ignored -> {}
        );

        assertTrue(reusedFrame.isComplete());
        assertFractalDataEquals(
                referenceFrame.fractalData(),
                reusedFrame.fractalData()
        );
    }
}
