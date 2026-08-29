package com.shangin.fractal.formula;

/** Optional capability for analytic formulas that can track a complex derivative. */
public interface DistanceEstimatingFormula extends FractalFormula {

    DistanceSample calculateDistance(
            double real,
            double imaginary,
            int maxIterations
    );
}
