package com.shangin.fractal.formula;

import com.shangin.fractal.coloring.OrbitTrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbitTrapFormulaTest {

    @Test
    void mandelbrotRetainsClosestPointTrapApproach() {
        FractalSample sample = new MandelbrotFormula().calculate(1.0, 0.0, 20, OrbitTrap.POINT);

        assertEquals(1.0, sample.orbitTrapDistance(), 1.0e-12);
    }

    @Test
    void everyPresetFormulaProducesFiniteTrapDistance() {
        for (FractalPreset preset : FractalPreset.values()) {
            FractalSample sample = preset.createFormula().calculate(
                    0.31, 0.17, 40, OrbitTrap.CROSS);
            assertTrue(Double.isFinite(sample.orbitTrapDistance()), preset.toString());
        }
    }
}
