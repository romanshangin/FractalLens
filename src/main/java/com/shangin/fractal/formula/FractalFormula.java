package com.shangin.fractal.formula;

import com.shangin.fractal.coloring.OrbitTrap;

public interface FractalFormula {

    /** Returns whether conjugate input coordinates always produce equal samples. */
    default boolean hasConjugateSymmetry() {
        return false;
    }

    FractalSample calculate(
            double real,
            double imaginary,
            int maxIterations);

    /** Calculates the same orbit while retaining its closest approach to a trap. */
    default FractalSample calculate(
            double real,
            double imaginary,
            int maxIterations,
            OrbitTrap orbitTrap
    ) {
        return calculate(real, imaginary, maxIterations);
    }
}
