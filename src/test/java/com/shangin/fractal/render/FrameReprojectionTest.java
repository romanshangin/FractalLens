package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameReprojectionTest {

    @Test
    void centeredZoomOutShouldCoverTheScaledCenter() {
        Viewport source = new Viewport(0.0, 0.0, 2.0);

        Optional<RenderRegion> coverage = FrameReprojection.approximateCoverage(
                source,
                source.zoom(2.0),
                100,
                100
        );

        assertEquals(Optional.of(new RenderRegion(25, 25, 50, 50)), coverage);
    }

    @Test
    void cursorAnchoredZoomOutShouldKeepTheAnchorAtTheFrameEdge() {
        Viewport source = new Viewport(0.0, 0.0, 2.0);
        Viewport target = source.zoomAt(0.0, 0.0, 100, 100, 2.0);

        Optional<RenderRegion> coverage = FrameReprojection.approximateCoverage(
                source,
                target,
                100,
                100
        );

        assertEquals(Optional.of(new RenderRegion(0, 0, 50, 50)), coverage);
    }

    @Test
    void zoomInShouldNotAdvertiseApproximateCoverage() {
        Viewport source = new Viewport(0.0, 0.0, 2.0);

        assertTrue(FrameReprojection.approximateCoverage(
                source,
                source.zoom(0.5),
                100,
                100
        ).isEmpty());
    }

    @Test
    void deepCursorAnchoredZoomOutShouldKeepCoverageAroundItsExactAnchor() {
        Viewport source = new Viewport(
                "-0.8317528516858322713653476366999",
                "0.207813754242134522471317257011028",
                "1.6e-13");
        Viewport target = source.zoomAt(
                BigDecimal.valueOf(250), BigDecimal.valueOf(300),
                1000, 800, new BigDecimal("1.25"));

        assertEquals(Optional.of(new RenderRegion(50, 60, 800, 640)),
                FrameReprojection.approximateCoverage(source, target, 1000, 800));
    }
}
