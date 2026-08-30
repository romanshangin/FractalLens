package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRequest;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
