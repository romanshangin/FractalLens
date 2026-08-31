package com.shangin.fractal.render;

import com.shangin.fractal.formula.DistanceEstimatingFormula;
import com.shangin.fractal.formula.DistanceSample;
import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.coloring.OrbitTrap;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Evaluates a fractal formula over pixel regions mapped by a render grid. */
public class FractalCalculator {

    private final FractalFormula formula;
    private final OrbitTrap orbitTrap;
    private static final int CANCELLATION_CHECK_INTERVAL = 8;

    public FractalCalculator(FractalFormula formula) {
        this(formula, OrbitTrap.NONE);
    }

    public FractalCalculator(FractalFormula formula, OrbitTrap orbitTrap) {
        this.formula = formula;
        this.orbitTrap = java.util.Objects.requireNonNull(orbitTrap);
    }

    /** Returns whether rows mirrored around the real axis share their samples. */
    public boolean hasConjugateSymmetry() {
        return formula.hasConjugateSymmetry();
    }

    /** Evaluates one coordinate for off-screen and adaptive sampling pipelines. */
    public FractalSample calculateSample(
            double real,
            double imaginary,
            int maxIterations
    ) {
        return formula.calculate(real, imaginary, maxIterations, orbitTrap);
    }

    public boolean supportsDistanceEstimation() {
        return formula instanceof DistanceEstimatingFormula;
    }

    public DistanceSample calculateDistanceSample(
            double real,
            double imaginary,
            int maxIterations
    ) {
        if (!(formula instanceof DistanceEstimatingFormula distanceFormula)) {
            throw new UnsupportedOperationException("Formula does not support distance estimation");
        }
        return distanceFormula.calculateDistance(real, imaginary, maxIterations);
    }

    public FractalData calculate(
            int width,
            int height,
            RenderGrid renderGrid,
            int maxIterations
    ) {
        FractalData fractalData = new FractalData(
                width,
                height,
                maxIterations);

        calculateTile(
                fractalData,
                renderGrid,
                0,
                width,
                0,
                height,
                maxIterations,
                () -> false);

        return fractalData;
    }

    boolean calculateTile(
            FractalData fractalData,
            RenderGrid renderGrid,
            int xFrom,
            int xTo,
            int yFrom,
            int yTo,
            int maxIterations,
            BooleanSupplier cancelled
    ) {
        return calculateTile(
                fractalData,
                renderGrid,
                xFrom,
                xTo,
                yFrom,
                yTo,
                maxIterations,
                cancelled,
                (x, y) -> false,
                ignored -> {}
        );
    }

    boolean calculateTile(
            FractalData fractalData,
            RenderGrid renderGrid,
            int xFrom,
            int xTo,
            int yFrom,
            int yTo,
            int maxIterations,
            BooleanSupplier cancelled,
            PixelReady ready,
            Consumer<RenderRegion> rowCompleted
    ) {
        for (int y = yFrom; y < yTo; y++) {

            if (cancelled.getAsBoolean()) {
                return false;
            }

            double imaginary = renderGrid.imaginaryAt(y);

            for (int x = xFrom; x < xTo; x++) {

                if (ready.test(x, y)) {
                    continue;
                }

                if (((x - xFrom) & (CANCELLATION_CHECK_INTERVAL - 1)) == 0  && cancelled.getAsBoolean()) {
                    return false;
                }

                double real = renderGrid.realAt(x);

                FractalSample sample = calculateSample(real, imaginary, maxIterations);

                fractalData.set(x, y, sample);
            }

            rowCompleted.accept(
                    new RenderRegion(
                            xFrom,
                            y,
                            xTo - xFrom,
                            1
                    )
            );
        }

        return true;
    }
}
