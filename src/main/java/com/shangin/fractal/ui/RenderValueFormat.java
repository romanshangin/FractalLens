package com.shangin.fractal.ui;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/** Compact, locale-independent values for the render badge and its tooltip. */
final class RenderValueFormat {
    private static final BigDecimal SCIENTIFIC_THRESHOLD = BigDecimal.valueOf(1_000_000);
    private static final MathContext SCIENTIFIC_PRECISION = new MathContext(3, RoundingMode.HALF_UP);

    private RenderValueFormat() {}

    static String zoom(BigDecimal value) {
        BigDecimal zoom = Objects.requireNonNull(value);
        return format(zoom.compareTo(SCIENTIFIC_THRESHOLD) < 0
                ? zoom.setScale(0, RoundingMode.HALF_UP) : zoom);
    }

    static String iterations(int value) {
        return format(BigDecimal.valueOf(value));
    }

    /**
     * The text whose width the badge reserves: every digit becomes {@code 0} and every
     * percentage takes three digits. Progress then never resizes the badge; only a change
     * of state or wording does.
     */
    static String widestText(String text) {
        return text.replaceAll("\\d+%", "000%").replaceAll("\\d", "0");
    }

    private static String format(BigDecimal value) {
        if (value.compareTo(SCIENTIFIC_THRESHOLD) < 0) return value.toPlainString();
        BigDecimal rounded = value.round(SCIENTIFIC_PRECISION);
        int exponent = rounded.precision() - rounded.scale() - 1;
        String mantissa = rounded.movePointLeft(exponent).stripTrailingZeros().toPlainString();
        return (rounded.compareTo(value) == 0 ? "" : "≈") + mantissa + " × 10^" + exponent;
    }
}
