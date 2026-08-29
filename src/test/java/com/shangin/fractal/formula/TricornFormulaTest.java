package com.shangin.fractal.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TricornFormulaTest {

    private final TricornFormula formula = new TricornFormula();

    @Test
    void originShouldNotEscape() {
        FractalSample sample = formula.calculate(0.0, 0.0, 500);

        assertFalse(sample.escaped());
        assertEquals(500, sample.iterations());
    }

    @Test
    void distantPointShouldEscape() {
        FractalSample sample = formula.calculate(2.0, 2.0, 500);

        assertTrue(sample.escaped());
        assertTrue(sample.iterations() < 500);
    }

    @Test
    void conjugateCoordinatesShouldHaveEqualEscapeValues() {
        FractalSample upper = formula.calculate(-0.2, 0.7, 500);
        FractalSample lower = formula.calculate(-0.2, -0.7, 500);

        assertTrue(formula.hasConjugateSymmetry());
        assertEquals(upper.iterations(), lower.iterations());
        assertEquals(upper.escaped(), lower.escaped());
        assertEquals(upper.smoothIterations(), lower.smoothIterations());
    }
}
