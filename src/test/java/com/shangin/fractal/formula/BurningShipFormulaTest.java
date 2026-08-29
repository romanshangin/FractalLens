package com.shangin.fractal.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BurningShipFormulaTest {

    private final BurningShipFormula formula = new BurningShipFormula();

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
    void conjugateCoordinatesShouldNotBeAssumedEquivalent() {
        FractalSample upper = formula.calculate(-1.7, 0.04, 500);
        FractalSample lower = formula.calculate(-1.7, -0.04, 500);

        assertFalse(formula.hasConjugateSymmetry());
        assertNotEquals(upper.iterations(), lower.iterations());
    }

    @Test
    void displayCoordinatesShouldReflectCanonicalImaginaryCoordinate() {
        FractalSample displayedUpper = formula.calculate(-1.7, 0.04, 500);
        FractalSample canonicalLower = calculateCanonical(-1.7, -0.04, 500);

        assertEquals(canonicalLower.iterations(), displayedUpper.iterations());
        assertEquals(canonicalLower.escaped(), displayedUpper.escaped());
        assertEquals(canonicalLower.smoothIterations(), displayedUpper.smoothIterations());
    }

    private static FractalSample calculateCanonical(
            double real,
            double imaginary,
            int maxIterations
    ) {
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;

        while (zr * zr + zi * zi <= 4.0 && iteration < maxIterations) {
            double absoluteReal = Math.abs(zr);
            double absoluteImaginary = Math.abs(zi);
            zr = absoluteReal * absoluteReal
                    - absoluteImaginary * absoluteImaginary
                    + real;
            zi = 2.0 * absoluteReal * absoluteImaginary + imaginary;
            iteration++;
        }

        return new FractalSample(iteration, iteration < maxIterations, zr, zi);
    }
}
