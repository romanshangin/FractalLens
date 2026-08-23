package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;

import java.util.function.BooleanSupplier;

public class FractalCalculator {

    private final FractalFormula formula;
    private static final int CANCELLATION_CHECK_INTERVAL = 8;

    public FractalCalculator(FractalFormula formula) {
        this.formula = formula;
    }

    public void calculate(
            int width,
            int height,
            Viewport viewport,
            int maxIterations
    ) {
        FractalData data = new FractalData(width, height, maxIterations);

        calculateTile(data, viewport, width, height, 0, width, 0, height, maxIterations, () -> false);

    }

    public void calculateTile(
            FractalData data,
            Viewport viewport,
            int width,
            int height,
            int xFrom,
            int xTo,
            int yFrom,
            int yTo,
            int maxIterations,
            BooleanSupplier cancelled
    ) {
        for (int y = yFrom; y < yTo; y++) {

            if (cancelled.getAsBoolean()) {
                return;
            }

            double imaginary = viewport.imaginaryAt(y, height);

            for (int x = xFrom; x < xTo; x++) {

                if (((x - xFrom) & (CANCELLATION_CHECK_INTERVAL - 1)) == 0  && cancelled.getAsBoolean()) {
                    return;
                }

                double real = viewport.realAt(x, width, height);

                FractalSample sample = formula.calculate(real, imaginary, maxIterations);

                data.set(y * width + x, sample);
            }
        }
    }
}
