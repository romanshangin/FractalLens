package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.MandelbrotFormula;
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
        RenderRequest request = new RenderRequest(
                calculator,
                viewport,
                100,
                70,
                300
        );

        List<RenderRegion> regions =
                new CopyOnWriteArrayList<>();

        FractalData data =
                parallelCalculator.calculate(
                        request,
                        () -> false,
                        (fractalData, region) -> regions.add(region)
                );

        assertNotNull(data);
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

        List<RenderRegion> regions =
                new CopyOnWriteArrayList<>();

        parallelCalculator.calculate(
                request,
                () -> false,
                (data, region) -> regions.add(region)
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
}
