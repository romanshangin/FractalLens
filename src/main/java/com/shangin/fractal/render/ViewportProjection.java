package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/** Exact complex-plane mapping reduced to a final screen-space affine transform. */
public record ViewportProjection(
        double scaleX,
        double scaleY,
        double translateX,
        double translateY
) {
    public static ViewportProjection between(
            Viewport source,
            Viewport target,
            int width,
            int height
    ) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Dimensions must be at least 2");
        }

        int precision = Math.max(
                source.mathContext().getPrecision(),
                target.mathContext().getPrecision()) + 8;
        MathContext context = new MathContext(precision, RoundingMode.HALF_EVEN);

        BigDecimal sourceWidth = source.visibleWidthExact(width, height);
        BigDecimal targetWidth = target.visibleWidthExact(width, height);
        BigDecimal scaleX = sourceWidth.divide(targetWidth, context);
        BigDecimal scaleY = source.visibleHeightExact()
                .divide(target.visibleHeightExact(), context);

        BigDecimal translateX = source.minRealExact(width, height)
                .subtract(target.minRealExact(width, height), context)
                .divide(targetWidth, context)
                .multiply(BigDecimal.valueOf(width - 1L), context);
        BigDecimal translateY = target.maxImaginaryExact()
                .subtract(source.maxImaginaryExact(), context)
                .divide(target.visibleHeightExact(), context)
                .multiply(BigDecimal.valueOf(height - 1L), context);

        return new ViewportProjection(
                scaleX.doubleValue(),
                scaleY.doubleValue(),
                translateX.doubleValue(),
                translateY.doubleValue());
    }
}
