package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Objects;

public record RenderGrid(
        double originReal,
        double originImaginary,
        double realStep,
        double imaginaryStep,
        long offsetX,
        long offsetY
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
                0);
    }

    public double realAt(int x) {
        long gridX = (long) x + offsetX;

        return originReal + gridX * realStep;
    }

    public double imaginaryAt(int y) {
        long gridY = (long) y + offsetY;

        return originImaginary - gridY * imaginaryStep;
    }

    public RenderGrid shifted(PixelShift shift) {
        Objects.requireNonNull(shift);

        return new RenderGrid(
                originReal,
                originImaginary,
                realStep,
                imaginaryStep,
                offsetX - shift.dx(),
                offsetY - shift.dy());
    }
}