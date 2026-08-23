package com.shangin.fractal.formula;

public class MandelbrotFormula implements FractalFormula {

    private static final double ESCAPE_RADIUS_SQUARED = 4.0;

    @Override
    public FractalSample calculate(
            double real,
            double imaginary,
            int maxIterations
    ) {
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
}