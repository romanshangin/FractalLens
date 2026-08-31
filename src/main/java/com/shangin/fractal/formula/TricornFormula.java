package com.shangin.fractal.formula;

import com.shangin.fractal.coloring.OrbitTrap;

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

    @Override
    public FractalSample calculate(
            double real, double imaginary, int maxIterations, OrbitTrap orbitTrap
    ) {
        if (orbitTrap == OrbitTrap.NONE) {
            return calculate(real, imaginary, maxIterations);
        }
        double zr = 0.0;
        double zi = 0.0;
        double trapDistance = Double.POSITIVE_INFINITY;
        int iteration = 0;
        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED && iteration < maxIterations) {
            double nextReal = zr * zr - zi * zi + real;
            double nextImaginary = -2.0 * zr * zi + imaginary;
            zr = nextReal;
            zi = nextImaginary;
            trapDistance = Math.min(trapDistance, orbitTrap.distance(zr, zi));
            iteration++;
        }
        return MandelbrotFormula.trappedSample(
                iteration, iteration < maxIterations, zr, zi, 2.0, trapDistance);
    }
}
