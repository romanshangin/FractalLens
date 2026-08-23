package com.shangin.fractal.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JuliaFormulaTest {

    private final JuliaFormula formula = new JuliaFormula(0.0, 0.0);

    @Test
    void originShouldNotEscape() {
        int maxIterations = 1000;

        FractalSample sample = formula.calculate(0.0, 0.0, maxIterations);

        assertEquals(maxIterations, sample.iterations());
    }

    @Test
    void pointInsideUnitCircleShouldNotEscape() {
        int maxIterations = 1000;

        FractalSample sample = formula.calculate(0.5, 0.0, maxIterations);

        assertEquals(maxIterations, sample.iterations());
    }

    @Test
    void pointOutsideUnitCircleShouldEscape() {
        int maxIterations = 1000;

        FractalSample sample = formula.calculate(2.0, 0.0, maxIterations);

        assertTrue(sample.iterations() < maxIterations);
    }
}