package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColorStop;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaletteStopEditorTest {

    @Test
    void addedStopWorksWithImmutableInputAndSplitsWidestGap() {
        List<ColorStop> stops = List.of(
                new ColorStop(1.0, 0xFFFFFFFF),
                new ColorStop(0.0, 0xFF000000),
                new ColorStop(0.25, 0xFF404040)
        );

        ColorStop added = PaletteStopOperations.createAddedStop(stops);

        assertEquals(0.625, added.position(), 1.0e-12);
        assertEquals(0xFF9F9F9F, added.color());
    }

    @Test
    void addedStopPositionIsTheDecimalMidpoint() {
        List<ColorStop> stops = List.of(
                new ColorStop(0.2, 0xFF000000),
                new ColorStop(0.35, 0xFF404040),
                new ColorStop(0.7, 0xFF808080),
                new ColorStop(0.8, 0xFFFFFFFF)
        );

        ColorStop added = PaletteStopOperations.createAddedStop(stops);

        assertEquals("0.525", Double.toString(added.position()));
    }
}
