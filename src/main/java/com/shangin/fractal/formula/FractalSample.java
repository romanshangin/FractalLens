package com.shangin.fractal.formula;

public record FractalSample(
        int iterations,
        boolean escaped,
        double zr,
        double zi,
        double smoothingPower)
{
    public FractalSample(int iterations, boolean escaped, double zr, double zi) {
        this(iterations, escaped, zr, zi, 2.0);
    }

    public FractalSample {
        if (!Double.isFinite(smoothingPower) || smoothingPower <= 1.0) {
            throw new IllegalArgumentException("Smoothing power must be finite and greater than 1");
        }
    }

    public double smoothIterations() {
        if (!escaped) {
            return iterations;
        }
        double modulus = Math.sqrt(zr * zr + zi * zi);

        return iterations + 1.0
                - Math.log(Math.log(modulus)) / Math.log(smoothingPower);
    }
}
