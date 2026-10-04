package com.shangin.fractal.coloring;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class PalettePresetTest {
    @org.junit.jupiter.api.Test
    void blueGoldCyclesFromBlueThroughLightAndWarmColorsBackToBlue() {
        Palette palette = PalettePreset.BLUE_GOLD.palette();
        assertEquals(palette.color(0.0), palette.color(1.0));
        int blue = palette.color(0.16);
        int cream = palette.color(0.52);
        int orange = palette.color(0.76);
        assertTrue((blue & 0xFF) > ((blue >>> 16) & 0xFF));
        assertTrue(((cream >>> 16) & 0xFF) > 240 && ((cream >>> 8) & 0xFF) > 240);
        assertTrue(((orange >>> 16) & 0xFF) > 240 && (orange & 0xFF) < 20);
    }

    @ParameterizedTest
    @EnumSource(PalettePreset.class)
    void presetsProvideDistinctOpaqueStopsAcrossTheWholeGradient(PalettePreset preset) {
        var stops = preset.stops();
        assertTrue(stops.size() >= 5, preset + " needs at least five stops");
        assertTrue(stops.stream().map(ColorStop::color).distinct().count() >= 5,
                preset + " needs at least five distinct colors");
        assertEquals(0.0, stops.getFirst().position());
        assertEquals(1.0, stops.getLast().position());
        for (int i = 0; i < stops.size(); i++) {
            ColorStop stop = stops.get(i);
            assertEquals(255, stop.color() >>> 24);
            assertEquals(stop.color(), preset.palette().color(stop.position()));
            if (i > 0) {
                assertTrue(stop.position() > stops.get(i - 1).position());
            }
        }
    }
}
