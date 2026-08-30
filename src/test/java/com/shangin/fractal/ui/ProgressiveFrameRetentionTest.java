package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRequest;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressiveFrameRetentionTest {

    @Test
    void visiblePartialFrameShouldSurviveAnInterruptedZoom() {
        RenderFrame current = frame(1.0);
        RenderFrame zoomed = frame(0.5);

        assertTrue(ProgressiveFrameRetention.shouldRetain(
                current,
                zoomed,
                true
        ));
    }

    @Test
    void successiveInterruptedZoomShouldReplaceTheOlderPreview() {
        RenderFrame firstZoom = frame(0.5);
        RenderFrame secondZoom = frame(0.25);

        assertTrue(ProgressiveFrameRetention.shouldRetain(
                firstZoom,
                secondZoom,
                true
        ));
    }

    @Test
    void compatibleInPlaceContinuationShouldKeepTheSameStagingLayer() {
        RenderFrame current = frame(1.0);

        assertFalse(ProgressiveFrameRetention.shouldRetain(
                current,
                current,
                true
        ));
    }

    @Test
    void hiddenRawQualityFrameShouldNotReplaceVisibleRefinedPreview() {
        assertFalse(ProgressiveFrameRetention.shouldRetain(
                frame(0.5),
                frame(0.25),
                false
        ));
    }

    @Test
    void firstRenderShouldHaveNothingToRetain() {
        assertFalse(ProgressiveFrameRetention.shouldRetain(
                null,
                frame(1.0),
                true
        ));
    }

    @Test
    void panShouldReuseTheRetainedPartialFrameSelectedByPlanner() {
        RenderFrame displayed = frame(1.0);
        RenderFrame retained = frame(0.5);

        assertEquals(
                ProgressiveFrameRetention.SourceSlot.RETAINED,
                ProgressiveFrameRetention.reusableSource(
                        retained,
                        displayed,
                        retained
                )
        );
    }

    @Test
    void normalPanShouldStillPreferTheDisplayedCompletedFrame() {
        RenderFrame displayed = frame(1.0);

        assertEquals(
                ProgressiveFrameRetention.SourceSlot.DISPLAYED,
                ProgressiveFrameRetention.reusableSource(
                        displayed,
                        displayed,
                        frame(0.5)
                )
        );
    }

    @Test
    void unrelatedFrameShouldNotReuseVisiblePixelLayers() {
        assertEquals(
                ProgressiveFrameRetention.SourceSlot.NONE,
                ProgressiveFrameRetention.reusableSource(
                        frame(0.25),
                        frame(1.0),
                        frame(0.5)
                )
        );
    }

    @Test
    void reverseNavigationShouldNotOverwriteTheRetainedPlannerSource() {
        RenderFrame retained = frame(0.5);

        assertFalse(ProgressiveFrameRetention.shouldReplaceRetained(
                frame(0.25),
                retained,
                true,
                retained,
                retained
        ));
    }

    @Test
    void newZoomMayReplaceAHistoryFrameThatPlannerDoesNotUse() {
        assertTrue(ProgressiveFrameRetention.shouldReplaceRetained(
                frame(0.5),
                frame(0.25),
                true,
                null,
                frame(1.0)
        ));
    }

    private static RenderFrame frame(double scaleMultiplier) {
        FractalPreset preset = FractalPreset.MANDELBROT;
        Viewport source = preset.defaultViewport();
        return RenderFrame.create(new RenderRequest(
                new FractalCalculator(preset.createFormula()),
                new Viewport(
                        source.centerReal(),
                        source.centerImaginary(),
                        source.scale() * scaleMultiplier
                ),
                64,
                48,
                200
        ));
    }
}
