package com.shangin.fractal.ui;

import com.shangin.fractal.math.PreciseComplex;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * An exact Go to Coordinates request. The zoom is relative to the fitted initial view;
 * {@code null} keeps the current scale exactly instead of reconstructing it from a decimal.
 */
record CoordinateTarget(PreciseComplex center, BigDecimal zoom) {
    CoordinateTarget {
        Objects.requireNonNull(center);
        if (zoom != null && zoom.compareTo(BigDecimal.ONE) < 0)
            throw new IllegalArgumentException("Zoom must be at least 1");
    }

    /** Parses dialog drafts; an unchanged zoom keeps the current scale. */
    static CoordinateTarget parse(String real, String imaginary, String zoom, BigDecimal currentZoom) {
        var center = new PreciseComplex(real.trim(), imaginary.trim());
        var requested = new BigDecimal(zoom.trim());
        return new CoordinateTarget(center, requested.compareTo(currentZoom) == 0 ? null : requested);
    }

    /** The exact editable text of the current zoom, matching Copy Coordinates and Zoom. */
    static String zoomText(BigDecimal zoom) {
        return zoom.stripTrailingZeros().toPlainString();
    }
}
