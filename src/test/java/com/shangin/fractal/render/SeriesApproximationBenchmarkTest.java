package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeriesApproximationBenchmarkTest {

    @Test
    void cubicSeriesMatchesFullPerturbationAtAConservativeDeepZoomSkip() {
        int maxIterations = 964;
        SeriesApproximationBenchmark.ReferenceOrbit reference =
                SeriesApproximationBenchmark.ReferenceOrbit.calculate(maxIterations);
        SeriesApproximationBenchmark.SeriesCoefficients coefficients =
                SeriesApproximationBenchmark.SeriesCoefficients.calculate(reference, 16);
        double scale = 2.4e-12;

        SeriesApproximationBenchmark.Result expected =
                SeriesApproximationBenchmark.calculatePerturbation(
                        80, 45, scale, reference, maxIterations);
        SeriesApproximationBenchmark.Result actual =
                SeriesApproximationBenchmark.calculateSeries(
                        80, 45, scale, reference, coefficients, maxIterations);
        SeriesApproximationBenchmark.Accuracy accuracy =
                SeriesApproximationBenchmark.compare(expected, actual, 1e-6);

        assertEquals(0, accuracy.iterationMismatches());
        assertEquals(0, accuracy.smoothMismatches());
    }
}
