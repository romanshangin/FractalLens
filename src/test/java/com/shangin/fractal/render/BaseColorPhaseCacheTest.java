package com.shangin.fractal.render;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseColorPhaseCacheTest {

    @Test
    void cachedBasePhasesMatchDirectColoringAcrossAnimatedOffset() {
        FractalData data = new FractalData(4, 1, 20_001_000);
        data.set(0, new FractalSample(20_000_001, true, 4.0, 0.0));
        data.set(1, new FractalSample(812, true, 8.0, 1.0));
        data.set(2, new FractalSample(300, false, 0.0, 0.0));
        data.set(3, new FractalSample(47, true, 3.0, 2.0));
        var phaseSource = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.0075, 0.0);
        var animated = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.0075, 1.371);
        BaseColorPhaseCache cache = BaseColorPhaseCache.create(data, phaseSource);
        int[] actual = new int[data.size()];

        cache.recolorInto(actual, new SmoothColorLookup(animated));

        for (int index = 0; index < data.size(); index++) {
            assertEquals(
                    animated.color(
                            data.iterations(index), data.smoothIterations(index),
                            data.escaped(index), data.maxIterations()),
                    actual[index]
            );
        }
        assertTrue(cache.matches(animated));
    }
}
