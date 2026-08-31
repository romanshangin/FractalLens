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
}
