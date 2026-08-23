package com.shangin.fractal.formula;

public record FractalSample(
        int iterations,
        boolean escaped,
        double zr,
        double zi)
{
    private static final double LOG_2 = Math.log(2.0);

    public double smoothIterations() {
        if (!escaped) {
            return iterations;
        }
        double modulus = Math.sqrt(zr * zr + zi * zi);

        return iterations + 1.0 - Math.log(Math.log(modulus)) / LOG_2;
    }
}
