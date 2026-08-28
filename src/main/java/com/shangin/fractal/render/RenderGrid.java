package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Objects;

public record RenderGrid(
        double originReal,
        double originImaginary,
        double realStep,
        double imaginaryStep,
        long offsetX,
        long offsetY,
        int conjugateHeight
) {
    public static RenderGrid from(
            Viewport viewport,
            int width,
            int height
    ) {
        Objects.requireNonNull(viewport);

        return new RenderGrid(
                viewport.minReal(
                        width,
                        height),
                viewport.maxImaginary(),
                viewport.realUnitsPerPixel(
                        width,
                        height),
                viewport.imaginaryUnitsPerPixel(height),
                0,
                0,
                viewport.centerImaginary() == 0.0 ? height : 0);
    }

    public double realAt(int x) {
        long gridX = (long) x + offsetX;

        return originReal + gridX * realStep;
    }

    public double imaginaryAt(int y) {
        long gridY = (long) y + offsetY;

        if (conjugateHeight > 0
                && gridY >= (conjugateHeight + 1L) / 2L
                && gridY < conjugateHeight) {
            return -rawImaginaryAt(conjugateHeight - 1L - gridY);
        }

        return rawImaginaryAt(gridY);
    }

    private double rawImaginaryAt(long gridY) {
        return originImaginary - gridY * imaginaryStep;
    }

    /** Returns whether every render row has an exact conjugate mirror row. */
    public boolean isConjugateSymmetric(int height) {
        if (height < 1) {
            throw new IllegalArgumentException("Height must be positive");
        }

        double firstRow = imaginaryAt(0);
        double lastRow = imaginaryAt(height - 1);
        double gridMagnitude = Math.max(
                Math.max(Math.abs(firstRow), Math.abs(lastRow)),
                Math.abs(imaginaryStep * Math.max(1, height - 1))
        );
        double tolerance = 8.0 * Math.ulp(gridMagnitude == 0.0 ? 1.0 : gridMagnitude);

        for (int y = 0; y < (height + 1) / 2; y++) {
            double first = imaginaryAt(y);
            double mirror = imaginaryAt(height - 1 - y);

            if (Math.abs(first + mirror) > tolerance) {
                return false;
            }
        }

        return true;
    }

    public RenderGrid shifted(PixelShift shift) {
        Objects.requireNonNull(shift);

        return new RenderGrid(
                originReal,
                originImaginary,
                realStep,
                imaginaryStep,
                offsetX - shift.dx(),
                offsetY - shift.dy(),
                conjugateHeight);
    }
}
