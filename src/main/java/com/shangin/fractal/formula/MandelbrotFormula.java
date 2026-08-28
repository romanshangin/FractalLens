package com.shangin.fractal.formula;

public class MandelbrotFormula implements FractalFormula {

    private static final double ESCAPE_RADIUS_SQUARED = 4.0;

    @Override
    public boolean hasConjugateSymmetry() {
        return true;
    }

    @Override
    public FractalSample calculate(
            double real,
            double imaginary,
            int maxIterations
    ) {
        if (isInMainCardioidOrPeriodTwoBulb(real, imaginary)) {
            return new FractalSample(
                    maxIterations,
                    false,
                    0.0,
                    0.0
            );
        }

        double zr = 0.0;
        double zi = 0.0;

        int iteration = 0;

        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED && iteration < maxIterations) {

            double zrNew = zr * zr - zi * zi + real;
            double ziNew = 2.0 * zr * zi + imaginary;

            zr = zrNew;
            zi = ziNew;

            iteration++;
        }

        boolean escaped = iteration < maxIterations;

        return new FractalSample(iteration, escaped, zr, zi);
    }

    /**
     * Returns whether a point lies in one of the two analytically known
     * interior regions that account for most of the visible Mandelbrot body.
     */
    static boolean isInMainCardioidOrPeriodTwoBulb(
            double real,
            double imaginary
    ) {
        double imaginarySquared = imaginary * imaginary;
        double cardioidX = real - 0.25;
        double q = cardioidX * cardioidX + imaginarySquared;

        if (q * (q + cardioidX) < 0.25 * imaginarySquared) {
            return true;
        }

        double bulbX = real + 1.0;

        return bulbX * bulbX + imaginarySquared < 0.0625;
    }
}
