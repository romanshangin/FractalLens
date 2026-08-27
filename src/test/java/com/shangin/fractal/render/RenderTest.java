package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class RenderTest {

    private static final FractalPreset PRESET = FractalPreset.MANDELBROT;

    private FractalCalculator calculator;
    private Viewport viewport;
    private ParallelFractalCalculator parallelCalculator;
    private static final int WIDTH = 2000;
    private static final int HEIGHT = 1408;
    private static final int MAX_ITERATIONS = 2000;

    @BeforeEach
    void setUp() {
        calculator = new FractalCalculator(PRESET.createFormula());

        viewport = new Viewport(-0.75, 0.0, 2.4);

        parallelCalculator = new ParallelFractalCalculator();
    }

    @AfterEach
    void tearDown() {
        parallelCalculator.close();
    }

    @Test
    void shouldReportAllCompletedRegions() throws InterruptedException {
        RenderRequest renderRequest = new RenderRequest(
                calculator,
                viewport,
                100,
                70,
                300
        );

        List<RenderRegion> regions = new CopyOnWriteArrayList<>();

        RenderFrame renderFrame = RenderFrame.create(renderRequest);

        RenderFrame resultFrame =
                parallelCalculator.calculate(
                        renderFrame,
                        () -> false,
                        regions::add);

        assertNotNull(resultFrame.fractalData());
        assertEquals(12, regions.size());

        int coveredPixels =
                regions.stream()
                        .mapToInt(
                                region ->
                                        region.width()
                                                * region.height()
                        )
                        .sum();

        assertEquals(
                100 * 70,
                coveredPixels
        );
    }

    @Test
    void completedRegionsShouldStayInsideFrame() throws InterruptedException {
        RenderRequest request = new RenderRequest(
                calculator,
                viewport,
                100,
                70,
                300
        );

        RenderFrame renderFrame = RenderFrame.create(request);

        List<RenderRegion> regions = new CopyOnWriteArrayList<>();

        RenderFrame resultFrame = parallelCalculator.calculate(
                        renderFrame,
                        () -> false,
                        regions::add);

        assertSame(
                renderFrame,
                resultFrame
        );

        for (RenderRegion region : regions) {
            assertTrue(region.x() >= 0);
            assertTrue(region.y() >= 0);

            assertTrue(
                    region.x() + region.width()
                            <= request.width()
            );

            assertTrue(
                    region.y() + region.height()
                            <= request.height()
            );
        }
    }

    @Test
    void constructorWithoutPriorityShouldUseCenter() {
        RenderRequest request = new RenderRequest(
                calculator,
                viewport,
                WIDTH,
                HEIGHT,
                MAX_ITERATIONS);

        assertEquals(
                RenderPriority.center(),
                request.priority()
        );
    }
}
