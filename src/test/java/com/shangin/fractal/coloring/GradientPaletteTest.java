package com.shangin.fractal.coloring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradientPaletteTest {

    @Test
    void positionsBeforeFirstEditableStopUseFirstColor() {
        GradientPalette palette = new GradientPalette(List.of(
                new ColorStop(0.25, 0xFF112233),
                new ColorStop(0.75, 0xFFEEEEEE)
        ));

        assertEquals(0xFF112233, palette.color(0.0));
    }

    @Test
    void preservesExactEndpointColors() {
        GradientPalette palette = grayscalePalette();

        assertEquals(0xFF000000, palette.color(-1.0));
        assertEquals(0xFF000000, palette.color(0.0));
        assertEquals(0xFFFFFFFF, palette.color(1.0));
        assertEquals(0xFFFFFFFF, palette.color(2.0));
    }

    @Test
    void interpolatesPerceptualLightnessInOklab() {
        int midpoint = grayscalePalette().color(0.5);
        int red = (midpoint >>> 16) & 0xFF;
        int green = (midpoint >>> 8) & 0xFF;
        int blue = midpoint & 0xFF;

        assertEquals(red, green);
        assertEquals(green, blue);
        assertTrue(red > 90 && red < 115);
    }

    private static GradientPalette grayscalePalette() {
        return new GradientPalette(List.of(
                new ColorStop(0.0, 0xFF000000),
                new ColorStop(1.0, 0xFFFFFFFF)
        ));
    }
}
