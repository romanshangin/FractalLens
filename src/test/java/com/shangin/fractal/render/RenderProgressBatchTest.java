package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderProgressBatchTest {

    private FractalCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FractalCalculator(FractalPreset.MANDELBROT.createFormula());
    }

    @Test
    void zoomOutShouldNotPublishExactProgressOverApproximateCenter() {
        RenderRegion coverage = new RenderRegion(25, 25, 50, 50);
        RenderFrame frame = frame(Optional.of(coverage));
        RenderProgressBatch progress = new RenderProgressBatch(
                frame,
                List.of(new RenderRegion(0, 0, 100, 100))
        );

        RenderProgressBatch visible = progress.outsideApproximateCoverage();

        assertEquals(List.of(
                new RenderRegion(0, 0, 100, 25),
                new RenderRegion(0, 75, 100, 25),
                new RenderRegion(0, 25, 25, 50),
                new RenderRegion(75, 25, 25, 50)
        ), visible.regions());
    }

    @Test
    void fullyCoveredCenterTileShouldStayOnTheReprojectedImage() {
        RenderRegion coverage = new RenderRegion(20, 20, 60, 60);
        RenderProgressBatch progress = new RenderProgressBatch(
                frame(Optional.of(coverage)),
                List.of(new RenderRegion(32, 32, 32, 32))
        );

        assertTrue(progress.outsideApproximateCoverage().regions().isEmpty());
    }

    @Test
    void newlyExposedTileShouldRemainVisible() {
        RenderRegion exposed = new RenderRegion(0, 0, 20, 20);
        RenderProgressBatch progress = new RenderProgressBatch(
                frame(Optional.of(new RenderRegion(25, 25, 50, 50))),
                List.of(exposed)
        );

        assertEquals(
                List.of(exposed),
                progress.outsideApproximateCoverage().regions()
        );
    }

    @Test
    void normalRenderShouldKeepTheOriginalBatch() {
        RenderProgressBatch progress = new RenderProgressBatch(
                frame(Optional.empty()),
                List.of(new RenderRegion(0, 0, 32, 32))
        );

        assertSame(progress, progress.outsideApproximateCoverage());
    }

    private RenderFrame frame(Optional<RenderRegion> coverage) {
        FractalPreset preset = FractalPreset.MANDELBROT;
        return RenderFrame.create(new RenderRequest(
                calculator,
                preset.defaultViewport(),
                100,
                100,
                100,
                RenderPriority.center(),
                coverage
        ));
    }
}
