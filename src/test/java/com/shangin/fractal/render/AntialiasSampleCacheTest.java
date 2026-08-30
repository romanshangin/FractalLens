package com.shangin.fractal.render;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntialiasSampleCacheTest {

    @Test
    void evictsLeastRecentlyUsedPixelsWithinMemoryLimit() {
        AntialiasSampleCache cache = new AntialiasSampleCache(16);
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        cache.put(1, samples(1.0, 2), coloring);
        cache.put(2, samples(2.0, 2), coloring);
        assertTrue(cache.contains(1)); // make pixel 1 newest
        cache.put(3, samples(3.0, 2), coloring);

        assertTrue(cache.usedBytes() <= cache.maxBytes());
        assertTrue(cache.contains(1));
        assertFalse(cache.contains(2));
        assertTrue(cache.contains(3));
    }

    @Test
    void recolorsStoredSamplesAndShiftsThemForFrameReuse() {
        AntialiasSampleCache cache = new AntialiasSampleCache(1024);
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.02, 0.5);
        cache.put(1, samples(20.0, 4), ice);

        assertNotEquals(cache.color(1, ice, 100), cache.color(1, fire, 100));
        cache.shift(4, 3, new PixelShift(1, 1));
        assertFalse(cache.contains(1));
        assertTrue(cache.contains(6));
    }

    @Test
    void preservesSmoothPhaseAtVeryHighIterationCounts() {
        AntialiasSampleCache cache = new AntialiasSampleCache(1024);
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.0075, 0.37);
        FractalSample sample = new FractalSample(20_000_001, true, 4.0, 0.0);

        cache.put(0, new FractalSample[]{sample}, coloring);

        assertEquals(
                coloring.color(
                        sample.iterations(), sample.smoothIterations(), true, 20_000_100),
                cache.color(0, coloring, 20_000_100)
        );
    }

    private static FractalSample[] samples(double smooth, int count) {
        FractalSample[] samples = new FractalSample[count];
        for (int index = 0; index < count; index++) {
            samples[index] = new FractalSample(10, true, smooth + index, 0.0);
        }
        return samples;
    }
}
