package com.shangin.fractal.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultibrotFormulaTest {

    @Test
    void powerShouldBeAtLeastTwo() {
        assertThrows(IllegalArgumentException.class, () -> new MultibrotFormula(1));
    }

    @Test
    void originShouldRemainInsideCubicSet() {
        FractalSample sample = new MultibrotFormula(3).calculate(0.0, 0.0, 500);

        assertFalse(sample.escaped());
        assertEquals(500, sample.iterations());
        assertEquals(3.0, sample.smoothingPower());
    }

    @Test
    void distantPointShouldEscapeCubicSet() {
        FractalSample sample = new MultibrotFormula(3).calculate(2.0, 2.0, 500);

        assertTrue(sample.escaped());
        assertTrue(sample.iterations() < 500);
    }

    @Test
    void quadraticMultibrotShouldMatchMandelbrotOutsideAnalyticInterior() {
        FractalSample expected = new MandelbrotFormula().calculate(-0.75, 0.1, 500);
        FractalSample actual = new MultibrotFormula(2).calculate(-0.75, 0.1, 500);

        assertEquals(expected.iterations(), actual.iterations());
        assertEquals(expected.escaped(), actual.escaped());
        assertEquals(expected.smoothIterations(), actual.smoothIterations());
    }
}
