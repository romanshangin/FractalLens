package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.HistogramPaletteColoring;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
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

    @Test
    void progressiveStrategyCarriesTheCompletedHistogramOnlyForHistogramColoring() {
        com.shangin.fractal.render.FractalData data = new com.shangin.fractal.render.FractalData(2, 1, 100);
        data.setValues(0, 10, 10.0, true, Double.NaN);
        data.setValues(1, 20, 20.0, true, Double.NaN);
        ColoringSettings histogram = new ColoringSettings(PalettePreset.FIRE, 0.01, 0.0, true);
        HistogramPaletteColoring reference = (HistogramPaletteColoring) histogram.createStrategy(data);

        assertInstanceOf(HistogramPaletteColoring.class, histogram.createProgressiveStrategy(reference));
        assertEquals(histogram.createStrategy(data).color(15, 15.0, true, 100),
                histogram.createProgressiveStrategy(reference).color(15, 15.0, true, 100));
        assertInstanceOf(SmoothPaletteColoring.class, histogram.createProgressiveStrategy(null),
                "Without a completed frame, progressive pixels use the smooth palette");
        assertInstanceOf(SmoothPaletteColoring.class,
                new ColoringSettings(PalettePreset.FIRE, 0.01, 0.0).createProgressiveStrategy(reference));
        assertInstanceOf(OrbitTrapColoring.class, new ColoringSettings(PalettePreset.FIRE,
                PalettePreset.FIRE.stops(), 0.01, 0.0, true, OrbitTrap.CROSS).createProgressiveStrategy(reference));
    }
}
