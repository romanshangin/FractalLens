package com.shangin.fractal.formula;

/** Escape-time Multibrot formula z = z^power + c for an integer power. */
public final class MultibrotFormula implements FractalFormula {

    private static final double ESCAPE_RADIUS_SQUARED = 4.0;
    private final int power;

    public MultibrotFormula(int power) {
        if (power < 2) {
            throw new IllegalArgumentException("Multibrot power must be at least 2");
        }
        this.power = power;
    }

    public int power() {
        return power;
    }

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
            double powerReal = zr;
            double powerImaginary = zi;

            for (int exponent = 1; exponent < power; exponent++) {
                double nextReal = powerReal * zr - powerImaginary * zi;
                double nextImaginary = powerReal * zi + powerImaginary * zr;
                powerReal = nextReal;
                powerImaginary = nextImaginary;
            }

            zr = powerReal + real;
            zi = powerImaginary + imaginary;
            iteration++;
        }

        return new FractalSample(iteration, iteration < maxIterations, zr, zi, power);
    }
}
