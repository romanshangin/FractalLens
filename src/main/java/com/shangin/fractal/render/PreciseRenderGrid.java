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
        if (width < 2 || height < 2) throw new IllegalArgumentException("Dimensions must be at least 2");
        // The viewport retains every supplied digit. Orbit/grid arithmetic needs only
        // pixel-depth precision plus guards, not thousands of irrelevant input digits.
        int depth = Math.max(0, viewport.scaleExact().scale() - viewport.scaleExact().precision() + 1);
        int integerDigits = Math.max(1, Math.max(
                viewport.center().real().precision() - viewport.center().real().scale(),
                viewport.center().imaginary().precision() - viewport.center().imaginary().scale()));
        int dimensionDigits = Integer.toString(Math.max(width, height)).length();
        MathContext context = new MathContext(Math.max(34, depth + integerDigits + dimensionDigits + 24),
                java.math.RoundingMode.HALF_EVEN);
        if (viewport.mathContext().getPrecision() <= context.getPrecision() + 32) {
            return new PreciseRenderGrid(viewport.minRealExact(width, height), viewport.maxImaginaryExact(),
                    viewport.realUnitsPerPixelExact(width, height), viewport.imaginaryUnitsPerPixelExact(height),
                    0, 0, viewport.center().imaginary().signum() == 0 ? height : 0, viewport.mathContext());
        }
        BigDecimal step = viewport.scaleExact().divide(BigDecimal.valueOf(height - 1L), context);
        BigDecimal half = BigDecimal.valueOf(2);
        return new PreciseRenderGrid(
                viewport.center().real().subtract(step.multiply(BigDecimal.valueOf(width - 1L), context)
                        .divide(half, context), context),
                viewport.center().imaginary().add(viewport.scaleExact().divide(half, context), context),
                step, step, 0, 0,
                viewport.center().imaginary().signum() == 0 ? height : 0, context);
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
