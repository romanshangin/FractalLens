package com.shangin.fractal.ui;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.function.Predicate;

/** Formatting policy for the copyable zoom readout, independent of JavaFX. */
final class ZoomFormat {

    private ZoomFormat() {
    }

    static String format(BigDecimal zoomFactor) {
        return roundedInteger(zoomFactor).toPlainString();
    }

    /**
     * Keeps the complete integer while it fits, then uses the most precise
     * scientific representation accepted by the supplied width predicate.
     */
    static String format(BigDecimal zoomFactor, Predicate<String> fits) {
        Objects.requireNonNull(fits, "Fits predicate must not be null");

        BigDecimal integerZoom = roundedInteger(zoomFactor);
        String integerText = integerZoom.toPlainString();
        if (fits.test(integerText) || integerZoom.signum() == 0) {
            return integerText;
        }

        for (int significantDigits = integerZoom.precision();
             significantDigits >= 1;
             significantDigits--) {
            String candidate = scientific(integerZoom, significantDigits);
            if (fits.test(candidate)) {
                return candidate;
            }
        }

        // Even the shortest useful representation can be clipped only when
        // the control itself is extremely narrow. It is still preferable to
        // a bare exponent because it identifies the zoom magnitude correctly.
        return scientific(integerZoom, 1);
    }

    private static BigDecimal roundedInteger(BigDecimal zoomFactor) {
        return Objects.requireNonNull(zoomFactor, "Zoom factor must not be null")
                .setScale(0, RoundingMode.HALF_UP);
    }

    private static String scientific(BigDecimal integerZoom, int significantDigits) {
        int precision = Math.min(significantDigits, integerZoom.precision());
        BigDecimal rounded = integerZoom.round(new MathContext(precision, RoundingMode.HALF_UP));
        int exponent = rounded.precision() - rounded.scale() - 1;
        String mantissa = rounded.movePointLeft(exponent).toPlainString();
        return mantissa + "E" + (exponent >= 0 ? "+" : "") + exponent;
    }
}
