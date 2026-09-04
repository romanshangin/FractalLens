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
    /** Maps sample centers between equally sized render grids. */
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

    /**
     * Maps the outer bounds of a source raster onto a target viewport.
     *
     * <p>An {@code ImageView} occupies the full display rectangle, while the
     * fractal samples inside it are indexed from {@code 0} to {@code size - 1}.
     * Using the sample-center transform for the view itself leaves a fractional
     * pixel offset after scaling. This transform deliberately maps raster
     * bounds instead.</p>
     */
    public static ViewportProjection betweenImageBounds(
            Viewport source,
            Viewport target,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            double displayWidth,
            double displayHeight
    ) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        if (sourceWidth < 2 || sourceHeight < 2
                || targetWidth < 2 || targetHeight < 2) {
            throw new IllegalArgumentException("Dimensions must be at least 2");
        }
        if (!Double.isFinite(displayWidth) || displayWidth <= 0.0
                || !Double.isFinite(displayHeight) || displayHeight <= 0.0) {
            throw new IllegalArgumentException("Display dimensions must be positive and finite");
        }

        int precision = Math.max(
                source.mathContext().getPrecision(),
                target.mathContext().getPrecision()) + 8;
        MathContext context = new MathContext(precision, RoundingMode.HALF_EVEN);

        BigDecimal sourceVisibleWidth = source.visibleWidthExact(
                sourceWidth, sourceHeight);
        if (sourceWidth != targetWidth || sourceHeight != targetHeight) {
            BigDecimal stepRatio = source.imaginaryUnitsPerPixelExact(sourceHeight)
                    .divide(target.imaginaryUnitsPerPixelExact(targetHeight), context);
            BigDecimal halfPixel = BigDecimal.ONE.subtract(stepRatio, context)
                    .divide(BigDecimal.valueOf(2), context);
            double x = target.xAtExact(source.minRealExact(sourceWidth, sourceHeight),
                    targetWidth, targetHeight).add(halfPixel, context).doubleValue();
            double y = target.yAtExact(source.maxImaginaryExact(), targetHeight)
                    .add(halfPixel, context).doubleValue();
            return new ViewportProjection(
                    stepRatio.doubleValue() * sourceWidth / targetWidth,
                    stepRatio.doubleValue() * sourceHeight / targetHeight,
                    x * displayWidth / targetWidth,
                    y * displayHeight / targetHeight);
        }
        BigDecimal targetVisibleWidth = target.visibleWidthExact(
                targetWidth, targetHeight);
        BigDecimal scaleX = sourceVisibleWidth.divide(targetVisibleWidth, context);
        BigDecimal scaleY = source.visibleHeightExact()
                .divide(target.visibleHeightExact(), context);

        BigDecimal translateX = source.minRealExact(sourceWidth, sourceHeight)
                .subtract(target.minRealExact(targetWidth, targetHeight), context)
                .divide(targetVisibleWidth, context)
                .multiply(BigDecimal.valueOf(displayWidth), context);
        BigDecimal translateY = target.maxImaginaryExact()
                .subtract(source.maxImaginaryExact(), context)
                .divide(target.visibleHeightExact(), context)
                .multiply(BigDecimal.valueOf(displayHeight), context);

        return new ViewportProjection(
                scaleX.doubleValue(),
                scaleY.doubleValue(),
                translateX.doubleValue(),
                translateY.doubleValue());
    }
}
