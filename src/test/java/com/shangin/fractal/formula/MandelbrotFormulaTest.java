package com.shangin.fractal.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MandelbrotFormulaTest {
    private final MandelbrotFormula formula = new MandelbrotFormula();

    @Test
    void originShouldNotEscape() {
        int maxIterations = 1000;

        FractalSample sample = formula.calculate(0.0, 0.0, maxIterations);

        assertEquals(maxIterations, sample.iterations());
        assertFalse(sample.escaped());
    }

    @Test
    void pointOutsideSetShouldEscape() {
        int maxIterations = 1000;

        FractalSample sample = formula.calculate(2.0, 2.0, maxIterations);

        assertTrue(sample.escaped());
        assertTrue(sample.iterations() < maxIterations);
    }

    @Test
    void minusOneShouldNotEscape() {
        int maxIterations = 1000;

        FractalSample sample = formula.calculate(-1.0, 0.0, maxIterations);

        assertEquals(maxIterations, sample.iterations());
        assertFalse(sample.escaped());
    }

    @Test
    void threeShouldEscapeQuickly() {
        int maxIterations = 1000;

        FractalSample sample = formula.calculate(3.0, 0.0, maxIterations);

        assertTrue(sample.escaped());
        assertTrue(sample.iterations() < maxIterations);
    }
}
