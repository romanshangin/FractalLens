package com.shangin.fractal.coloring;

import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.FractalData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramPaletteColoringTest {

    @Test
    void mapsEscapedSamplesThroughTheirCumulativeDistribution() {
        FractalData data = new FractalData(4, 1, 100);
        FractalSample low = new FractalSample(2, true, 8.0, 0.0);
        FractalSample middle = new FractalSample(8, true, 8.0, 0.0);
        FractalSample high = new FractalSample(20, true, 8.0, 0.0);
        data.set(0, low);
        data.set(1, middle);
        data.set(2, high);
        data.set(3, new FractalSample(100, false, 0.0, 0.0));

        RecordingPalette palette = new RecordingPalette();
        HistogramPaletteColoring coloring =
                new HistogramPaletteColoring(palette, 0.0, data, 32);

        coloring.color(low.iterations(), low.smoothIterations(), true, 100);
        double lowPosition = palette.position;
        coloring.color(middle.iterations(), middle.smoothIterations(), true, 100);
        double middlePosition = palette.position;
        coloring.color(high.iterations(), high.smoothIterations(), true, 100);
        double highPosition = palette.position;

        assertEquals(0.0, lowPosition, 1.0e-12);
        assertTrue(middlePosition > lowPosition && middlePosition < highPosition);
        assertEquals(1.0, highPosition, 1.0e-12);
    }

    @Test
    void interpolatesPalettePositionsWithinOneHistogramBin() {
        FractalData data = new FractalData(3, 1, 100);
        FractalSample low = new FractalSample(2, true, 8.0, 0.0);
        FractalSample middle = new FractalSample(2, true, 4.0, 0.0);
        FractalSample high = new FractalSample(20, true, 8.0, 0.0);
        data.set(0, low);
        data.set(1, middle);
        data.set(2, high);
        RecordingPalette palette = new RecordingPalette();
        HistogramPaletteColoring coloring =
                new HistogramPaletteColoring(palette, 0.0, data, 2);

        coloring.color(low.iterations(), low.smoothIterations(), true, 100);
        double first = palette.position;
        coloring.color(middle.iterations(), middle.smoothIterations(), true, 100);
        double second = palette.position;

        assertTrue(second > first);
    }

    @Test
    void keepsInteriorBlackAndOutOfRangeAaSamplesClamped() {
        FractalData data = new FractalData(1, 1, 50);
        FractalSample sample = new FractalSample(3, true, 4.0, 0.0);
        data.set(0, sample);
        RecordingPalette palette = new RecordingPalette();
        HistogramPaletteColoring coloring =
                new HistogramPaletteColoring(palette, 0.0, data, 16);

        assertEquals(0xFF000000, coloring.color(50, 50.0, false, 50));
        coloring.color(1, -100.0, true, 50);
        assertEquals(1.0, palette.position, 1.0e-12);
        coloring.color(1, 1_000.0, true, 50);
        assertEquals(1.0, palette.position, 1.0e-12);
    }

    @Test
    void progressivePixelsKeepTheCompletedMappingWithinItsRange() {
        FractalData data = spreadData();
        RecordingPalette completedPalette = new RecordingPalette();
        HistogramPaletteColoring completed = new HistogramPaletteColoring(completedPalette, 0.25, data, 8);
        RecordingPalette progressivePalette = new RecordingPalette();
        HistogramPaletteColoring progressive = completed.forProgressivePixels(progressivePalette, 0.25, 0.01);

        for (int index = 0; index < data.size(); index++) {
            completed.color(data.iterations(index), data.smoothIterations(index), true, 100);
            progressive.color(data.iterations(index), data.smoothIterations(index), true, 100);
            assertEquals(completedPalette.position, progressivePalette.position, 0.0,
                    "Sample " + index + " must keep its completed palette position");
        }
        assertEquals(0xFF000000, progressive.color(100, 100.0, false, 100));
    }

    @Test
    void progressivePixelsContinueBeyondTheCompletedRangeInsteadOfClamping() {
        FractalData data = spreadData();
        RecordingPalette palette = new RecordingPalette();
        HistogramPaletteColoring progressive =
                new HistogramPaletteColoring(new RecordingPalette(), 0.0, data, 8)
                        .forProgressivePixels(palette, 0.0, 0.01);

        // The range is [10, 20]: one palette sweep per width of 10 beyond either end.
        progressive.color(1, 22.0, true, 100);
        assertEquals(0.8, palette.position, 1.0e-12, "1.2 wraps back to 0.8");
        progressive.color(1, 25.0, true, 100);
        assertEquals(0.5, palette.position, 1.0e-12);
        progressive.color(1, 30.0, true, 100);
        assertEquals(0.0, palette.position, 1.0e-12);
        progressive.color(1, 7.0, true, 100);
        assertEquals(0.3, palette.position, 1.0e-12, "-0.3 wraps to 0.3");
        progressive.color(1, 20.0, true, 100);
        assertEquals(1.0, palette.position, 1.0e-12, "The continuation starts at the range end");
    }

    @Test
    void progressivePixelsUseTheirOwnPaletteAndOffset() {
        FractalData data = spreadData();
        HistogramPaletteColoring completed = new HistogramPaletteColoring(new RecordingPalette(), 0.0, data, 8);
        RecordingPalette palette = new RecordingPalette();
        HistogramPaletteColoring shifted = completed.forProgressivePixels(palette, 0.5, 0.01);

        assertEquals(0xFFFFFFFF, shifted.color(1, 10.0, true, 100));
        assertEquals(0.5, palette.position, 1.0e-12);
    }

    @Test
    void aSingleEscapedValueKeepsItsColorAndContinuesAtTheSmoothScale() {
        FractalData single = new FractalData(1, 1, 50);
        single.setValues(0, 3, 4.0, true, Double.NaN);
        RecordingPalette completedPalette = new RecordingPalette();
        HistogramPaletteColoring completed = new HistogramPaletteColoring(completedPalette, 0.0, single, 8);
        RecordingPalette palette = new RecordingPalette();
        HistogramPaletteColoring progressive = completed.forProgressivePixels(palette, 0.0, 0.0075);

        completed.color(3, 4.0, true, 50);
        progressive.color(3, 4.0, true, 50);
        assertEquals(completedPalette.position, palette.position, 0.0, "The known value keeps its color");
        assertEquals(1.0, palette.position, 0.0);
        progressive.color(5, 24.0, true, 50);
        assertEquals(0.85, palette.position, 1.0e-12, "1 + 20 * 0.0075 wraps back to 0.85");
        progressive.color(2, 0.0, true, 50);
        assertEquals(0.97, palette.position, 1.0e-12, "1 - 4 * 0.0075");
    }

    @Test
    void framesWithoutEscapedPixelsHaveNoProgressiveMapping() {
        FractalData inside = new FractalData(1, 1, 50);
        inside.setValues(0, 50, 0.0, false, Double.NaN);

        assertNull(new HistogramPaletteColoring(new RecordingPalette(), 0.0, inside, 8)
                .forProgressivePixels(new RecordingPalette(), 0.0, 0.01));
    }

    /** Escaped smooth values 10, 12.5, 15, 17.5 and 20. */
    private static FractalData spreadData() {
        FractalData data = new FractalData(5, 1, 100);
        for (int index = 0; index < 5; index++) {
            data.setValues(index, 10, 10.0 + 2.5 * index, true, Double.NaN);
        }
        return data;
    }

    private static final class RecordingPalette implements Palette {
        private double position;

        @Override
        public int color(double position) {
            this.position = position;
            return 0xFFFFFFFF;
        }
    }
}
