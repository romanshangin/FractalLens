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
}
