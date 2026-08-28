package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FrameReusePlannerFastPathTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 6;
    private static final int MAX_ITERATIONS = 100;

    private FrameReusePlanner planner;
    private FractalCalculator calculator;
    private Viewport sourceViewport;
    private RenderFrame sourceFrame;

    @BeforeEach
    void setUp() {
        FractalPreset preset = FractalPreset.MANDELBROT;

        planner = new FrameReusePlanner();
        calculator = new FractalCalculator(preset.createFormula());
        sourceViewport = preset.defaultViewport();

        sourceFrame = RenderFrame.create(
                new RenderRequest(
                        calculator,
                        sourceViewport,
                        WIDTH,
                        HEIGHT,
                        MAX_ITERATIONS
                )
        );

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int value = y * WIDTH + x + 1;

                sourceFrame.fractalData().set(
                        x,
                        y,
                        new FractalSample(value, false, 0.0, 0.0)
                );
            }
        }

        sourceFrame.validity().markReady(
                new RenderRegion(0, 0, WIDTH, HEIGHT)
        );
    }

    static Stream<Arguments> directionalShifts() {
        return Stream.of(
                Arguments.of("right", 3, 0),
                Arguments.of("left", -3, 0),
                Arguments.of("down", 0, 2),
                Arguments.of("up", 0, -2)
        );
    }

    @ParameterizedTest(name = "completed frame shifted {0}")
    @MethodSource("directionalShifts")
    void shouldReuseExactOverlapInEveryDirection(
            String direction,
            int dx,
            int dy
    ) {
        FrameReuseResult result = planShift(dx, dy);

        assertEquals(new PixelShift(dx, dy), result.shift().orElseThrow());
        assertEquals(
                (WIDTH - Math.abs(dx)) * (HEIGHT - Math.abs(dy)),
                result.reusedPixels()
        );
        assertTrue(result.reused());
        assertReusedPixelsMatchSource(result.frame(), dx, dy);
    }

    @Test
    void zeroShiftShouldReuseTheWholeCompletedFrame() {
        FrameReuseResult result = planShift(0, 0);

        assertEquals(new PixelShift(0, 0), result.shift().orElseThrow());
        assertEquals(WIDTH * HEIGHT, result.reusedPixels());
        assertTrue(result.reused());
        assertTrue(result.frame().isComplete());
        assertReusedPixelsMatchSource(result.frame(), 0, 0);
    }

    static Stream<Arguments> nonOverlappingShifts() {
        return Stream.of(
                Arguments.of("right", WIDTH, 0),
                Arguments.of("left", -WIDTH, 0),
                Arguments.of("down", 0, HEIGHT),
                Arguments.of("up", 0, -HEIGHT)
        );
    }

    @ParameterizedTest(name = "no overlap after shift {0}")
    @MethodSource("nonOverlappingShifts")
    void shouldReturnEmptyFrameWhenCompletedFramesDoNotOverlap(
            String direction,
            int dx,
            int dy
    ) {
        FrameReuseResult result = planShift(dx, dy);

        assertEquals(new PixelShift(dx, dy), result.shift().orElseThrow());
        assertEquals(0, result.reusedPixels());
        assertFalse(result.reused());
        assertEquals(0, result.frame().validity().readyPixelCount());
        assertFalse(result.frame().isComplete());
    }

    private FrameReuseResult planShift(
            int dx,
            int dy
    ) {
        Viewport targetViewport = sourceViewport.shiftedByPixels(
                dx,
                dy,
                WIDTH,
                HEIGHT
        );

        return planner.plan(
                sourceFrame,
                new RenderRequest(
                        calculator,
                        targetViewport,
                        WIDTH,
                        HEIGHT,
                        MAX_ITERATIONS
                )
        );
    }

    private void assertReusedPixelsMatchSource(
            RenderFrame targetFrame,
            int dx,
            int dy
    ) {
        int expectedReadyPixels = 0;

        for (int targetY = 0; targetY < HEIGHT; targetY++) {
            for (int targetX = 0; targetX < WIDTH; targetX++) {
                int sourceX = targetX - dx;
                int sourceY = targetY - dy;

                boolean reusable = sourceX >= 0
                        && sourceX < WIDTH
                        && sourceY >= 0
                        && sourceY < HEIGHT;

                assertEquals(
                        reusable,
                        targetFrame.validity().isReady(targetX, targetY)
                );

                int targetIndex = targetY * WIDTH + targetX;

                if (reusable) {
                    expectedReadyPixels++;

                    int sourceIndex = sourceY * WIDTH + sourceX;

                    assertEquals(
                            sourceFrame.fractalData().iterations(sourceIndex),
                            targetFrame.fractalData().iterations(targetIndex)
                    );
                    assertEquals(
                            sourceFrame.fractalData().escaped(sourceIndex),
                            targetFrame.fractalData().escaped(targetIndex)
                    );
                    assertEquals(
                            Double.doubleToLongBits(
                                    sourceFrame.fractalData().smoothIterations(sourceIndex)
                            ),
                            Double.doubleToLongBits(
                                    targetFrame.fractalData().smoothIterations(targetIndex)
                            )
                    );
                } else {
                    assertEquals(0, targetFrame.fractalData().iterations(targetIndex));
                }
            }
        }

        assertEquals(
                expectedReadyPixels,
                targetFrame.validity().readyPixelCount()
        );
    }
}
