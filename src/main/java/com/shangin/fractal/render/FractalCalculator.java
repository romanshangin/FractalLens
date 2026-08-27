package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalSample;

import java.util.function.BooleanSupplier;

public class FractalCalculator {

    private final FractalFormula formula;
    private static final int CANCELLATION_CHECK_INTERVAL = 8;

    public FractalCalculator(FractalFormula formula) {
        this.formula = formula;
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
        for (int y = yFrom; y < yTo; y++) {

            if (cancelled.getAsBoolean()) {
                return false;
            }

            double imaginary = renderGrid.imaginaryAt(y);

            for (int x = xFrom; x < xTo; x++) {

                if (((x - xFrom) & (CANCELLATION_CHECK_INTERVAL - 1)) == 0  && cancelled.getAsBoolean()) {
                    return false;
                }

                double real = renderGrid.realAt(x);

                FractalSample sample = formula.calculate(real, imaginary, maxIterations);

                fractalData.set(x, y, sample);
            }
        }

        return true;
    }
}
