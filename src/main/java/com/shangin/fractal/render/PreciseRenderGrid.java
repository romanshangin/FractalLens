package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/** Backend-neutral render grid retaining the viewport's full coordinate precision. */
public record PreciseRenderGrid(
        BigDecimal originReal,
        BigDecimal originImaginary,
        BigDecimal realStep,
        BigDecimal imaginaryStep,
        long offsetX,
        long offsetY,
        int conjugateHeight,
        MathContext mathContext
) {
    public PreciseRenderGrid {
        Objects.requireNonNull(originReal);
        Objects.requireNonNull(originImaginary);
        Objects.requireNonNull(realStep);
        Objects.requireNonNull(imaginaryStep);
        Objects.requireNonNull(mathContext);
        if (realStep.signum() <= 0 || imaginaryStep.signum() <= 0) {
            throw new IllegalArgumentException("Grid steps must be positive");
        }
    }

    public static PreciseRenderGrid from(Viewport viewport, int width, int height) {
        Objects.requireNonNull(viewport);
        return new PreciseRenderGrid(
                viewport.minRealExact(width, height),
                viewport.maxImaginaryExact(),
                viewport.realUnitsPerPixelExact(width, height),
                viewport.imaginaryUnitsPerPixelExact(height),
                0,
                0,
                viewport.center().imaginary().signum() == 0 ? height : 0,
                viewport.mathContext());
    }

    public BigDecimal realAt(int x) {
        return originReal.add(
                realStep.multiply(BigDecimal.valueOf((long) x + offsetX), mathContext),
                mathContext);
    }

    public BigDecimal imaginaryAt(int y) {
        long gridY = (long) y + offsetY;
        if (conjugateHeight > 0
                && gridY >= (conjugateHeight + 1L) / 2L
                && gridY < conjugateHeight) {
            return rawImaginaryAt(conjugateHeight - 1L - gridY).negate();
        }
        return rawImaginaryAt(gridY);
    }

    private BigDecimal rawImaginaryAt(long gridY) {
        return originImaginary.subtract(
                imaginaryStep.multiply(BigDecimal.valueOf(gridY), mathContext),
                mathContext);
    }

    public PreciseRenderGrid shifted(PixelShift shift) {
        Objects.requireNonNull(shift);
        return new PreciseRenderGrid(
                originReal, originImaginary, realStep, imaginaryStep,
                offsetX - shift.dx(), offsetY - shift.dy(), conjugateHeight, mathContext);
    }
}
