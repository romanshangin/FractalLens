package com.shangin.fractal.render;

import com.shangin.fractal.formula.BurningShipFormula;
import com.shangin.fractal.formula.MandelbrotFormula;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DistanceEstimationCapabilityTest {

    @Test
    void calculatorShouldExposeOnlySupportedFormulaCapability() {
        FractalCalculator analytic = new FractalCalculator(new MandelbrotFormula());
        FractalCalculator nonAnalytic = new FractalCalculator(new BurningShipFormula());

        assertTrue(analytic.supportsDistanceEstimation());
        assertFalse(nonAnalytic.supportsDistanceEstimation());
        assertThrows(
                UnsupportedOperationException.class,
                () -> nonAnalytic.calculateDistanceSample(1.0, 1.0, 100)
        );
    }
}
