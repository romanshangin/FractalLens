package com.shangin.fractal.ui;

import java.math.BigDecimal;

/** Formatting policy for the copyable zoom readout, independent of JavaFX. */
final class ZoomFormat {

    private ZoomFormat() {
    }

    static String format(BigDecimal zoomFactor) {
        return zoomFactor.stripTrailingZeros().toPlainString();
    }
}
