package com.shangin.fractal.coloring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SmoothPaletteColoringTest {

    @Test
    void insidePointShouldAlwaysBeBlack() {
        Palette palette = position -> 0xFFFFFFFF;

        SmoothPaletteColoring coloring = new SmoothPaletteColoring(palette);

        int color = coloring.color(0, 0.0, false, 300);

        assertEquals(0xFF000000, color);
    }

    @Test
    void palettePositionMovesForwardAndBackwardWithoutASeam() {
        RecordingPalette palette = new RecordingPalette();
        SmoothPaletteColoring coloring = new SmoothPaletteColoring(palette, 0.1, 0.0);

        coloring.color(0, 0.0, true, 300);
        assertEquals(0.0, palette.position);

        coloring.color(0, 5.0, true, 300);
        assertEquals(0.5, palette.position);

        coloring.color(0, 10.0, true, 300);
        assertEquals(1.0, palette.position);

        coloring.color(0, 15.0, true, 300);
        assertEquals(0.5, palette.position);

        coloring.color(0, 20.0, true, 300);
        assertEquals(0.0, palette.position);
    }

    private static final class RecordingPalette implements Palette {
        private double position;

        @Override
        public int color(double position) {
            this.position = position;
            return 0xFF000000;
        }
    }
}
