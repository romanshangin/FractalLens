package com.shangin.fractal.formula;

/** Escape-time Multibrot formula z = z^power + c for an integer power. */
public final class MultibrotFormula implements DistanceEstimatingFormula {

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

    @Override
    public DistanceSample calculateDistance(
            double real,
            double imaginary,
            int maxIterations
    ) {
        double zr = 0.0;
        double zi = 0.0;
        double derivativeReal = 0.0;
        double derivativeImaginary = 0.0;
        int iteration = 0;

        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED
                && iteration < maxIterations) {
            ComplexPower powerMinusOne = complexPower(zr, zi, power - 1);
            double factorReal = power * powerMinusOne.real();
            double factorImaginary = power * powerMinusOne.imaginary();
            double nextDerivativeReal = factorReal * derivativeReal
                    - factorImaginary * derivativeImaginary + 1.0;
            double nextDerivativeImaginary = factorReal * derivativeImaginary
                    + factorImaginary * derivativeReal;
            ComplexPower nextPower = multiply(
                    powerMinusOne.real(),
                    powerMinusOne.imaginary(),
                    zr,
                    zi
            );

            derivativeReal = nextDerivativeReal;
            derivativeImaginary = nextDerivativeImaginary;
            zr = nextPower.real() + real;
            zi = nextPower.imaginary() + imaginary;
            iteration++;
        }

        FractalSample sample = new FractalSample(
                iteration,
                iteration < maxIterations,
                zr,
                zi,
                power
        );
        return MandelbrotFormula.distanceSample(
                sample,
                derivativeReal,
                derivativeImaginary
        );
    }

    private static ComplexPower complexPower(double real, double imaginary, int exponent) {
        double resultReal = 1.0;
        double resultImaginary = 0.0;

        for (int current = 0; current < exponent; current++) {
            ComplexPower next = multiply(
                    resultReal,
                    resultImaginary,
                    real,
                    imaginary
            );
            resultReal = next.real();
            resultImaginary = next.imaginary();
        }
        return new ComplexPower(resultReal, resultImaginary);
    }

    private static ComplexPower multiply(
            double firstReal,
            double firstImaginary,
            double secondReal,
            double secondImaginary
    ) {
        return new ComplexPower(
                firstReal * secondReal - firstImaginary * secondImaginary,
                firstReal * secondImaginary + firstImaginary * secondReal
        );
    }

    private record ComplexPower(double real, double imaginary) {}
}
