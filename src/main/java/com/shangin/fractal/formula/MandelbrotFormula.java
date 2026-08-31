package com.shangin.fractal.formula;

import com.shangin.fractal.coloring.OrbitTrap;

public class MandelbrotFormula implements DistanceEstimatingFormula {

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
            double nextImaginary = 2.0 * zr * zi + imaginary;
            zr = nextReal;
            zi = nextImaginary;
            trapDistance = Math.min(trapDistance, orbitTrap.distance(zr, zi));
            iteration++;
        }
        return trappedSample(iteration, iteration < maxIterations, zr, zi, 2.0, trapDistance);
    }

    static FractalSample trappedSample(
            int iterations, boolean escaped, double zr, double zi,
            double smoothingPower, double trapDistance
    ) {
        return new FractalSample(
                iterations, escaped, zr, zi, smoothingPower,
                Double.isFinite(trapDistance) ? trapDistance : Double.NaN
        );
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

    @Override
    public DistanceSample calculateDistance(
            double real,
            double imaginary,
            int maxIterations
    ) {
        if (isInMainCardioidOrPeriodTwoBulb(real, imaginary)) {
            return DistanceSample.unavailable(
                    new FractalSample(maxIterations, false, 0.0, 0.0)
            );
        }

        double zr = 0.0;
        double zi = 0.0;
        double derivativeReal = 0.0;
        double derivativeImaginary = 0.0;
        int iteration = 0;

        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED
                && iteration < maxIterations) {
            double nextDerivativeReal = 2.0
                    * (zr * derivativeReal - zi * derivativeImaginary) + 1.0;
            double nextDerivativeImaginary = 2.0
                    * (zr * derivativeImaginary + zi * derivativeReal);
            double nextReal = zr * zr - zi * zi + real;
            double nextImaginary = 2.0 * zr * zi + imaginary;

            derivativeReal = nextDerivativeReal;
            derivativeImaginary = nextDerivativeImaginary;
            zr = nextReal;
            zi = nextImaginary;
            iteration++;
        }

        FractalSample sample = new FractalSample(
                iteration,
                iteration < maxIterations,
                zr,
                zi
        );
        return distanceSample(sample, derivativeReal, derivativeImaginary);
    }

    static DistanceSample distanceSample(
            FractalSample sample,
            double derivativeReal,
            double derivativeImaginary
    ) {
        if (!sample.escaped()) {
            return DistanceSample.unavailable(sample);
        }

        double modulus = Math.hypot(sample.zr(), sample.zi());
        double derivativeModulus = Math.hypot(derivativeReal, derivativeImaginary);
        double distance = modulus * Math.log(modulus) / derivativeModulus;

        if (!Double.isFinite(distance) || distance < 0.0) {
            return DistanceSample.unavailable(sample);
        }
        return new DistanceSample(sample, distance);
    }
}
