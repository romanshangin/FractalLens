package com.shangin.fractal.formula;

/**
 * Burning Ship formula using its conventional display orientation.
 * The imaginary input is reflected so the ship appears upright while the
 * rest of the renderer retains the usual mathematical screen mapping.
 */
public final class BurningShipFormula implements FractalFormula {

    private static final double ESCAPE_RADIUS_SQUARED = 4.0;

    @Override
    public FractalSample calculate(double real, double imaginary, int maxIterations) {
        double reflectedImaginary = -imaginary;
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;

        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED
                && iteration < maxIterations) {
            double absoluteReal = Math.abs(zr);
            double absoluteImaginary = Math.abs(zi);
            double nextReal = absoluteReal * absoluteReal
                    - absoluteImaginary * absoluteImaginary
                    + real;
            double nextImaginary = 2.0 * absoluteReal * absoluteImaginary
                    + reflectedImaginary;

            zr = nextReal;
            zi = nextImaginary;
            iteration++;
        }

        return new FractalSample(iteration, iteration < maxIterations, zr, zi);
    }
}
