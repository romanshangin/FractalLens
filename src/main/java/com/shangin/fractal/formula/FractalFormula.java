package com.shangin.fractal.formula;

public interface FractalFormula {

    FractalSample calculate(
            double real,
            double imaginary,
            int maxIterations);
}
