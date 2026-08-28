package com.shangin.fractal.formula;

public interface FractalFormula {

    /** Returns whether conjugate input coordinates always produce equal samples. */
    default boolean hasConjugateSymmetry() {
        return false;
    }

    FractalSample calculate(
            double real,
            double imaginary,
            int maxIterations);
}
