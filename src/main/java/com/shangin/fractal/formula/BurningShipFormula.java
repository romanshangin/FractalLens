package com.shangin.fractal.formula;

import com.shangin.fractal.coloring.OrbitTrap;

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

    @Override
    public FractalSample calculate(
            double real, double imaginary, int maxIterations, OrbitTrap orbitTrap
    ) {
        if (orbitTrap == OrbitTrap.NONE) {
            return calculate(real, imaginary, maxIterations);
        }
        double reflectedImaginary = -imaginary;
        double zr = 0.0;
        double zi = 0.0;
        double trapDistance = Double.POSITIVE_INFINITY;
        int iteration = 0;
        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED && iteration < maxIterations) {
            double absoluteReal = Math.abs(zr);
            double absoluteImaginary = Math.abs(zi);
            double nextReal = absoluteReal * absoluteReal
                    - absoluteImaginary * absoluteImaginary + real;
            double nextImaginary = 2.0 * absoluteReal * absoluteImaginary
                    + reflectedImaginary;
            zr = nextReal;
            zi = nextImaginary;
            trapDistance = Math.min(trapDistance, orbitTrap.distance(zr, zi));
            iteration++;
        }
        return MandelbrotFormula.trappedSample(
                iteration, iteration < maxIterations, zr, zi, 2.0, trapDistance);
    }
}
