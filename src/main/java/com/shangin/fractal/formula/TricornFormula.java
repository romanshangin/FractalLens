package com.shangin.fractal.formula;

/** Escape-time Tricorn (Mandelbar) formula z = conjugate(z)^2 + c. */
public final class TricornFormula implements FractalFormula {

    private static final double ESCAPE_RADIUS_SQUARED = 4.0;

    @Override
    public boolean hasConjugateSymmetry() {
        return true;
    }

    @Override
    public FractalSample calculate(double real, double imaginary, int maxIterations) {
        double zr = 0.0;
        double zi = 0.0;
        int iteration = 0;

        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED
                && iteration < maxIterations) {
            double nextReal = zr * zr - zi * zi + real;
            double nextImaginary = -2.0 * zr * zi + imaginary;
            zr = nextReal;
            zi = nextImaginary;
            iteration++;
        }

        return new FractalSample(iteration, iteration < maxIterations, zr, zi);
    }
}
