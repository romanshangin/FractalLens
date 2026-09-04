package com.shangin.fractal.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable mapping between the complex plane and pixels. The authoritative
 * center and scale are decimal values; double-returning methods are adapters
 * for hardware-precision renderers and presentation code.
 */
public final class Viewport {

    private static final int GUARD_DIGITS = 20;
    private static final int MINIMUM_PRECISION = 34;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final PreciseComplex center;
    private final BigDecimal scale;
    private final MathContext mathContext;

    public Viewport(double centerReal, double centerImaginary, double scale) {
        this(PreciseComplex.of(centerReal, centerImaginary), finitePositive(scale));
    }

    public Viewport(String centerReal, String centerImaginary, String scale) {
        this(new PreciseComplex(centerReal, centerImaginary), new BigDecimal(scale));
    }

    public Viewport(PreciseComplex center, BigDecimal scale) {
        this.center = Objects.requireNonNull(center, "Center must not be null");
        Objects.requireNonNull(scale, "Scale must not be null");
        if (scale.signum() <= 0) {
            throw new IllegalArgumentException("Scale must be positive");
        }
        this.scale = scale.stripTrailingZeros();
        this.mathContext = precisionFor(center, this.scale);
    }

    private static BigDecimal finitePositive(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("Scale must be positive and finite");
        }
        return BigDecimal.valueOf(value);
    }

    private static MathContext precisionFor(PreciseComplex center, BigDecimal scale) {
        int integerDigits = Math.max(
                integerDigits(center.real()), integerDigits(center.imaginary()));
        int scaleDepth = Math.max(0, -scale.precision() + scale.scale() + 1);
        int retainedDigits = Math.max(
                Math.max(center.real().precision(), center.imaginary().precision()),
                scale.precision());
        int precision = Math.max(MINIMUM_PRECISION,
                Math.max(integerDigits + scaleDepth + GUARD_DIGITS,
                        retainedDigits + GUARD_DIGITS));
        return new MathContext(precision, RoundingMode.HALF_EVEN);
    }

    private static int integerDigits(BigDecimal value) {
        return Math.max(1, value.precision() - value.scale());
    }

    private static void validateDimensions(int width, int height) {
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Width and height must be at least 2");
        }
    }

    public PreciseComplex center() { return center; }
    public BigDecimal scaleExact() { return scale; }
    public MathContext mathContext() { return mathContext; }

    public double centerReal() { return center.real().doubleValue(); }
    public double centerImaginary() { return center.imaginary().doubleValue(); }
    public double scale() { return scale.doubleValue(); }

    public BigDecimal visibleHeightExact() { return scale; }

    public BigDecimal visibleWidthExact(int width, int height) {
        validateDimensions(width, height);
        // Scale spans sample centers (0 .. size - 1) on both axes.
        return imaginaryUnitsPerPixelExact(height)
                .multiply(BigDecimal.valueOf(width - 1L), mathContext);
    }

    public BigDecimal minRealExact(int width, int height) {
        return center.real().subtract(
                visibleWidthExact(width, height).divide(TWO, mathContext), mathContext);
    }

    public BigDecimal maxRealExact(int width, int height) {
        return center.real().add(
                visibleWidthExact(width, height).divide(TWO, mathContext), mathContext);
    }

    public BigDecimal minImaginaryExact() {
        return center.imaginary().subtract(scale.divide(TWO, mathContext), mathContext);
    }

    public BigDecimal maxImaginaryExact() {
        return center.imaginary().add(scale.divide(TWO, mathContext), mathContext);
    }

    public BigDecimal realUnitsPerPixelExact(int width, int height) {
        validateDimensions(width, height);
        return imaginaryUnitsPerPixelExact(height);
    }

    public BigDecimal imaginaryUnitsPerPixelExact(int height) {
        if (height < 2) throw new IllegalArgumentException("Height must be at least 2");
        return scale.divide(BigDecimal.valueOf(height - 1L), mathContext);
    }

    public BigDecimal realAtExact(BigDecimal x, int width, int height) {
        validateDimensions(width, height);
        BigDecimal clamped = x.max(BigDecimal.ZERO).min(BigDecimal.valueOf(width - 1L));
        return minRealExact(width, height).add(
                clamped.multiply(realUnitsPerPixelExact(width, height), mathContext),
                mathContext);
    }

    public BigDecimal imaginaryAtExact(BigDecimal y, int height) {
        if (height < 2) throw new IllegalArgumentException("Height must be at least 2");
        BigDecimal clamped = y.max(BigDecimal.ZERO).min(BigDecimal.valueOf(height - 1L));
        return maxImaginaryExact().subtract(
                clamped.multiply(imaginaryUnitsPerPixelExact(height), mathContext),
                mathContext);
    }

    public double visibleHeight() { return visibleHeightExact().doubleValue(); }
    public double visibleWidth(int width, int height) { return visibleWidthExact(width, height).doubleValue(); }
    public double minReal(int width, int height) { return minRealExact(width, height).doubleValue(); }
    public double maxReal(int width, int height) { return maxRealExact(width, height).doubleValue(); }
    public double minImaginary() { return minImaginaryExact().doubleValue(); }
    public double maxImaginary() { return maxImaginaryExact().doubleValue(); }
    public double realAt(int x, int width, int height) { return realAtExact(BigDecimal.valueOf(x), width, height).doubleValue(); }
    public double imaginaryAt(int y, int height) { return imaginaryAtExact(BigDecimal.valueOf(y), height).doubleValue(); }
    public double realAt(double x, int width, int height) { return realAtExact(BigDecimal.valueOf(x), width, height).doubleValue(); }
    public double imaginaryAt(double y, int height) { return imaginaryAtExact(BigDecimal.valueOf(y), height).doubleValue(); }
    public double realUnitsPerPixel(int width, int height) { return realUnitsPerPixelExact(width, height).doubleValue(); }
    public double imaginaryUnitsPerPixel(int height) { return imaginaryUnitsPerPixelExact(height).doubleValue(); }

    public double xAt(double real, int width, int height) {
        return xAtExact(BigDecimal.valueOf(real), width, height).doubleValue();
    }

    public BigDecimal xAtExact(BigDecimal real, int width, int height) {
        return real.subtract(minRealExact(width, height), mathContext)
                .divide(realUnitsPerPixelExact(width, height), mathContext);
    }

    public double yAt(double imaginary, int height) {
        return yAtExact(BigDecimal.valueOf(imaginary), height).doubleValue();
    }

    public BigDecimal yAtExact(BigDecimal imaginary, int height) {
        return maxImaginaryExact().subtract(imaginary, mathContext)
                .divide(imaginaryUnitsPerPixelExact(height), mathContext);
    }

    public Viewport zoom(double factor) { return zoom(finitePositive(factor)); }

    public Viewport zoom(BigDecimal factor) {
        Objects.requireNonNull(factor);
        if (factor.signum() <= 0) throw new IllegalArgumentException("Zoom factor must be positive");
        return new Viewport(center, scale.multiply(factor, mathContext));
    }

    public Viewport pan(double deltaReal, double deltaImaginary) {
        return pan(BigDecimal.valueOf(deltaReal), BigDecimal.valueOf(deltaImaginary));
    }

    public Viewport pan(BigDecimal deltaReal, BigDecimal deltaImaginary) {
        return new Viewport(new PreciseComplex(
                center.real().add(deltaReal, mathContext),
                center.imaginary().add(deltaImaginary, mathContext)), scale);
    }

    public Viewport fit(double contentWidth, double contentHeight, int pixelWidth, int pixelHeight) {
        validateDimensions(pixelWidth, pixelHeight);
        BigDecimal width = finitePositive(contentWidth);
        BigDecimal height = finitePositive(contentHeight);
        BigDecimal windowAspect = BigDecimal.valueOf(pixelWidth - 1L)
                .divide(BigDecimal.valueOf(pixelHeight - 1L), mathContext);
        BigDecimal contentAspect = width.divide(height, mathContext);
        BigDecimal fittedScale = windowAspect.compareTo(contentAspect) < 0
                ? width.divide(windowAspect, mathContext) : height;
        return new Viewport(center, fittedScale);
    }

    public Viewport zoomAt(double x, double y, int width, int height, double factor) {
        return zoomAt(BigDecimal.valueOf(x), BigDecimal.valueOf(y), width, height,
                finitePositive(factor));
    }

    public Viewport zoomAt(
            BigDecimal x, BigDecimal y, int width, int height, BigDecimal factor) {
        Objects.requireNonNull(factor);
        if (factor.signum() <= 0) throw new IllegalArgumentException("Zoom factor must be positive");
        BigDecimal targetReal = realAtExact(x, width, height);
        BigDecimal targetImaginary = imaginaryAtExact(y, height);
        BigDecimal newReal = targetReal.add(
                center.real().subtract(targetReal, mathContext).multiply(factor, mathContext),
                mathContext);
        BigDecimal newImaginary = targetImaginary.add(
                center.imaginary().subtract(targetImaginary, mathContext).multiply(factor, mathContext),
                mathContext);
        return new Viewport(new PreciseComplex(newReal, newImaginary),
                scale.multiply(factor, mathContext));
    }

    /** Checks whether the precise grid can be represented by distinct doubles. */
    public boolean hasSufficientPrecision(int width, int height, double minUlpsPerPixel) {
        validateDimensions(width, height);
        if (!Double.isFinite(minUlpsPerPixel) || minUlpsPerPixel <= 0.0) return false;
        double stepReal = realUnitsPerPixel(width, height);
        double stepImaginary = imaginaryUnitsPerPixel(height);
        if (!(stepReal > 0.0) || !(stepImaginary > 0.0)
                || !Double.isFinite(stepReal) || !Double.isFinite(stepImaginary)) return false;
        double realUlp = Math.max(Math.ulp(minReal(width, height)), Math.ulp(maxReal(width, height)));
        double imaginaryUlp = Math.max(Math.ulp(minImaginary()), Math.ulp(maxImaginary()));
        return stepReal / realUlp >= minUlpsPerPixel
                && stepImaginary / imaginaryUlp >= minUlpsPerPixel;
    }

    public Viewport shiftedByPixels(int shiftX, int shiftY, int width, int height) {
        validateDimensions(width, height);
        BigDecimal realShift = realUnitsPerPixelExact(width, height)
                .multiply(BigDecimal.valueOf(shiftX), mathContext);
        BigDecimal imaginaryShift = imaginaryUnitsPerPixelExact(height)
                .multiply(BigDecimal.valueOf(shiftY), mathContext);
        return new Viewport(new PreciseComplex(
                center.real().subtract(realShift, mathContext),
                center.imaginary().add(imaginaryShift, mathContext)), scale);
    }

    public Viewport snapToPixelGrid(Viewport reference, int width, int height) {
        Objects.requireNonNull(reference);
        if (reference.scale.compareTo(scale) != 0) return this;
        BigDecimal rawShiftX = reference.center.real().subtract(center.real(), mathContext)
                .divide(reference.realUnitsPerPixelExact(width, height), mathContext);
        BigDecimal rawShiftY = center.imaginary().subtract(reference.center.imaginary(), mathContext)
                .divide(reference.imaginaryUnitsPerPixelExact(height), mathContext);
        int shiftX = rawShiftX.setScale(0, RoundingMode.HALF_UP).intValueExact();
        int shiftY = rawShiftY.setScale(0, RoundingMode.HALF_UP).intValueExact();
        return reference.shiftedByPixels(shiftX, shiftY, width, height);
    }

    public double zoomFactorFrom(Viewport defaultViewport) {
        Objects.requireNonNull(defaultViewport);
        return defaultViewport.scale.divide(scale, mathContext).doubleValue();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Viewport viewport)) return false;
        return center.equals(viewport.center) && scale.compareTo(viewport.scale) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * center.hashCode() + scale.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return "Viewport[center=" + center + ", scale=" + scale + ']';
    }
}
