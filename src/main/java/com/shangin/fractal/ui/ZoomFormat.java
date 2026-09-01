package com.shangin.fractal.ui;

import java.math.BigDecimal;
import java.util.Locale;

/** Formatting policy for the copyable zoom readout, independent of JavaFX. */
final class ZoomFormat {

    private ZoomFormat() {
    }

    static String format(BigDecimal zoomFactor) {
        if (zoomFactor.compareTo(BigDecimal.TEN) < 0) {
            return String.format(Locale.ROOT, "%.2f", zoomFactor);
        }
        if (zoomFactor.compareTo(BigDecimal.valueOf(1_000)) < 0) {
            return String.format(Locale.ROOT, "%.1f", zoomFactor);
        }
        if (zoomFactor.compareTo(BigDecimal.valueOf(1_000_000)) < 0) {
            return String.format(Locale.ROOT, "%.0f", zoomFactor);
        }
        return String.format(Locale.ROOT, "%.12e", zoomFactor);
    }
}
