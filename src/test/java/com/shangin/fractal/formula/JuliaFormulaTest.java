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

    @Test
    void exteriorPointShouldProvideDistanceEstimate() {
        DistanceSample distance = formula.calculateDistance(2.0, 0.0, 1000);

        assertTrue(distance.sample().escaped());
        assertTrue(distance.hasDistance());
        assertTrue(distance.distance() > 0.0);
    }

    @Test
    void periodicityCheckShouldMatchReferenceRenderingGrid() {
        double cReal = -0.8;
        double cImaginary = 0.156;
        JuliaFormula optimized = new JuliaFormula(cReal, cImaginary);
        int maxIterations = 300;
        int width = 301;
        int height = 241;

        for (int y = 0; y < height; y++) {
            double imaginary = 1.2 - 2.4 * y / (height - 1.0);

            for (int x = 0; x < width; x++) {
                double real = -1.5 + 3.0 * x / (width - 1.0);
                FractalSample expected = calculateReference(
                        real,
                        imaginary,
                        cReal,
                        cImaginary,
                        maxIterations
                );
                FractalSample actual = optimized.calculate(
                        real,
                        imaginary,
                        maxIterations
                );

                assertEquals(expected.iterations(), actual.iterations());
                assertEquals(expected.escaped(), actual.escaped());
                assertEquals(
                        Double.doubleToLongBits(expected.smoothIterations()),
                        Double.doubleToLongBits(actual.smoothIterations())
                );
            }
        }
    }

    private static FractalSample calculateReference(
            double real,
            double imaginary,
            double cReal,
            double cImaginary,
            int maxIterations
    ) {
        double zr = real;
        double zi = imaginary;
        int iteration = 0;

        while (zr * zr + zi * zi <= 4.0
                && iteration < maxIterations) {

            double zrNew = zr * zr - zi * zi + cReal;
            double ziNew = 2.0 * zr * zi + cImaginary;

            zr = zrNew;
            zi = ziNew;
            iteration++;
        }

        return new FractalSample(
                iteration,
                iteration < maxIterations,
                zr,
                zi
        );
    }
}
