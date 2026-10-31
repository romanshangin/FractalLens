package com.shangin.fractal.ui;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Locale-independent values for the render badge, its tooltip, Copy Coordinates and Zoom
 * and Go to Coordinates. Coordinates and zoom are compact but always re-enterable decimals;
 * iteration limits use a compact power-of-ten mode.
 */
final class RenderValueFormat {
    private static final BigDecimal SCIENTIFIC_THRESHOLD = BigDecimal.valueOf(1_000_000);
    private static final MathContext SCIENTIFIC_PRECISION = new MathContext(3, RoundingMode.HALF_UP);
    private static final BigDecimal SCIENTIFIC_ZOOM = BigDecimal.TEN.pow(12);
    private static final int ZOOM_DIGITS = 12;
    /** Rounded coordinates stay within this fraction of a render pixel of the exact center. */
    private static final BigDecimal COORDINATE_PIXEL_FRACTION = new BigDecimal("0.001");

    private RenderValueFormat() {}

    /**
     * Twelve significant digits, so the relative error is at most 5e-12. Plain notation below
     * 1e12 after rounding, otherwise {@link BigDecimal#toString()} scientific notation.
     * A zoom below 1 is never shown as 1.
     */
    static String zoom(BigDecimal value) {
        BigDecimal zoom = Objects.requireNonNull(value);
        if (zoom.signum() <= 0) throw new IllegalArgumentException("Zoom must be positive");
        BigDecimal rounded = zoom.round(new MathContext(ZOOM_DIGITS, RoundingMode.HALF_EVEN));
        if (zoom.compareTo(BigDecimal.ONE) < 0 && rounded.compareTo(BigDecimal.ONE) >= 0)
            rounded = zoom.round(new MathContext(ZOOM_DIGITS, RoundingMode.DOWN));
        rounded = rounded.stripTrailingZeros();
        return rounded.compareTo(SCIENTIFIC_ZOOM) < 0 ? rounded.toPlainString() : rounded.toString();
    }

    /**
     * A plain decimal rounded half-even to a power of ten no larger than 0.001 of
     * {@code pixelStep}, so the re-entered value lies within 0.0005 of that step.
     */
    static String coordinate(BigDecimal value, BigDecimal pixelStep) {
        Objects.requireNonNull(value);
        if (pixelStep.signum() <= 0) throw new IllegalArgumentException("Pixel step must be positive");
        BigDecimal limit = pixelStep.multiply(COORDINATE_PIXEL_FRACTION);
        // Exponent of the leading digit, i.e. floor(log10(limit)), without doubles.
        int exponent = limit.precision() - limit.scale() - 1;
        BigDecimal rounded = value.setScale(-exponent, RoundingMode.HALF_EVEN);
        return rounded.signum() == 0 ? "0" : rounded.stripTrailingZeros().toPlainString();
    }

    static String iterations(int value) {
        BigDecimal iterations = BigDecimal.valueOf(value);
        if (iterations.compareTo(SCIENTIFIC_THRESHOLD) < 0) return iterations.toPlainString();
        BigDecimal rounded = iterations.round(SCIENTIFIC_PRECISION);
        int exponent = rounded.precision() - rounded.scale() - 1;
        String mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString();
        return (rounded.compareTo(iterations) == 0 ? "" : "≈") + mantissa + " × 10^" + exponent;
    }

    /**
     * The text whose width the badge reserves: every digit becomes {@code 0} and every
     * percentage takes three digits. Progress then never resizes the badge; only a change
     * of state or wording does.
     */
    static String widestText(String text) {
        return text.replaceAll("\\d+%", "000%").replaceAll("\\d", "0");
    }
}
