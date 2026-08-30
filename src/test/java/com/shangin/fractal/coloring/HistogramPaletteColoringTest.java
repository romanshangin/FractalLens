package com.shangin.fractal.coloring;

import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.FractalData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final class RecordingPalette implements Palette {
        private double position;

        @Override
        public int color(double position) {
            this.position = position;
            return 0xFFFFFFFF;
        }
    }
}
