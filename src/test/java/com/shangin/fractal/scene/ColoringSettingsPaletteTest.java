package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.OrbitTrapColoring;
import com.shangin.fractal.coloring.PalettePreset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ColoringSettingsPaletteTest {

    @Test
    void customStopsAreDefensivelyCopiedIntoSceneSettings() {
        List<ColorStop> source = new java.util.ArrayList<>(PalettePreset.ICE.stops());
        ColoringSettings settings = new ColoringSettings(
                PalettePreset.ICE, source, 0.01, 0.0, false, OrbitTrap.NONE);
        source.clear();

        assertEquals(PalettePreset.ICE.stops(), settings.paletteStops());
    }

    @Test
    void activeTrapSelectsTrapColoringEvenWhenHistogramIsEnabled() {
        ColoringSettings settings = new ColoringSettings(
                PalettePreset.FIRE, PalettePreset.FIRE.stops(), 0.01, 0.0,
                true, OrbitTrap.UNIT_CIRCLE);

        assertInstanceOf(OrbitTrapColoring.class, settings.createStrategy(new com.shangin.fractal.render.FractalData(1, 1, 10)));
    }
}
