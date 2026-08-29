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

    @Test
    void exteriorPointShouldProvideDistanceEstimate() {
        DistanceSample distance = formula.calculateDistance(1.0, 1.0, 1000);

        assertTrue(distance.sample().escaped());
        assertTrue(distance.hasDistance());
        assertTrue(distance.distance() > 0.0);
    }

    @Test
    void shouldRecognizePointsInsideMainCardioid() {
        assertTrue(
                MandelbrotFormula.isInMainCardioidOrPeriodTwoBulb(
                        0.0,
                        0.0
                )
        );
        assertTrue(
                MandelbrotFormula.isInMainCardioidOrPeriodTwoBulb(
                        0.2,
                        0.0
                )
        );
    }

    @Test
    void shouldRecognizePointsInsidePeriodTwoBulb() {
        assertTrue(
                MandelbrotFormula.isInMainCardioidOrPeriodTwoBulb(
                        -1.0,
                        0.0
                )
        );
    }

    @Test
    void shouldNotRejectPointsOutsideAnalyticInteriorRegions() {
        assertFalse(
                MandelbrotFormula.isInMainCardioidOrPeriodTwoBulb(
                        0.5,
                        0.5
                )
        );
        assertFalse(
                MandelbrotFormula.isInMainCardioidOrPeriodTwoBulb(
                        -0.75,
                        0.1
                )
        );
    }

    @Test
    void optimizedFormulaShouldMatchReferenceRenderingGrid() {
        int maxIterations = 300;
        int width = 401;
        int height = 301;

        for (int y = 0; y < height; y++) {
            double imaginary = 1.2 - 2.4 * y / (height - 1.0);

            for (int x = 0; x < width; x++) {
                double real = -2.5 + 3.5 * x / (width - 1.0);
                FractalSample expected = calculateReference(
                        real,
                        imaginary,
                        maxIterations
                );
                FractalSample actual = formula.calculate(
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
            int maxIterations
    ) {
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;

        while (zr * zr + zi * zi <= 4.0
                && iteration < maxIterations) {

            double zrNew = zr * zr - zi * zi + real;
            double ziNew = 2.0 * zr * zi + imaginary;

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
