package com.shangin.fractal.formula;

public class JuliaFormula implements FractalFormula {

    private static final double ESCAPE_RADIUS_SQUARED = 4.0;

    private final double cReal;
    private final double cImaginary;

    public JuliaFormula(
            double cReal,
            double cImaginary
    ) {
        this.cReal = cReal;
        this.cImaginary = cImaginary;
    }

    @Override
    public FractalSample calculate(
            double real,
            double imaginary,
            int maxIterations
    ) {
        double zr = real;
        double zi = imaginary;

        int iteration = 0;

        while (zr * zr + zi * zi <= ESCAPE_RADIUS_SQUARED && iteration < maxIterations) {

            double zrNew = zr * zr - zi * zi + cReal;
            double ziNew = 2.0 * zr * zi + cImaginary;

            zr = zrNew;
            zi = ziNew;

            iteration++;
        }

        boolean escaped = iteration < maxIterations;

        return new FractalSample(iteration, escaped, zr, zi);
    }
}
