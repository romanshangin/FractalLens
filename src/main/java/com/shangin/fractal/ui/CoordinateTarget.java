package com.shangin.fractal.ui;

import com.shangin.fractal.math.PreciseComplex;

import java.math.BigDecimal;

/**
 * The edited part of a Go to Coordinates request. A field whose trimmed draft equals the
 * text shown when the editor opened is untouched ({@code null}) and takes the live exact
 * value when Go applies, so compact display text never replaces exact state. A changed
 * zoom is relative to the fitted initial view and must be at least 1.
 */
record CoordinateTarget(BigDecimal real, BigDecimal imaginary, BigDecimal zoom) {
    CoordinateTarget {
        if (zoom != null && zoom.compareTo(BigDecimal.ONE) < 0)
            throw new IllegalArgumentException("Zoom must be at least 1");
    }

    /** Parses only the changed drafts; throws when a changed draft is invalid. */
    static CoordinateTarget parse(CoordinateText shown, String real, String imaginary, String zoom) {
        return new CoordinateTarget(
                changed(real, shown.real()) ? new BigDecimal(real.trim()) : null,
                changed(imaginary, shown.imaginary()) ? new BigDecimal(imaginary.trim()) : null,
                changed(zoom, shown.zoom()) ? new BigDecimal(zoom.trim()) : null);
    }

    /**
     * Zero when every changed draft parses, otherwise the one-based index of the first
     * invalid field (1 real, 2 imaginary, 3 zoom). Reachability is checked separately.
     */
    static int invalidField(CoordinateText shown, String real, String imaginary, String zoom) {
        if (changed(real, shown.real()) && !isNumber(real)) return 1;
        if (changed(imaginary, shown.imaginary()) && !isNumber(imaginary)) return 2;
        if (changed(zoom, shown.zoom()) && !isZoom(zoom)) return 3;
        return 0;
    }

    /** Whether a draft is a valid new zoom of at least 1. */
    static boolean isZoom(String text) {
        return isNumber(text) && new BigDecimal(text.trim()).compareTo(BigDecimal.ONE) >= 0;
    }

    boolean unchanged() {
        return real == null && imaginary == null && zoom == null;
    }

    /** The requested center, keeping untouched components of the live center exactly. */
    PreciseComplex center(PreciseComplex current) {
        return new PreciseComplex(real == null ? current.real() : real,
                imaginary == null ? current.imaginary() : imaginary);
    }

    // Text comparison, not numeric: 1.5 → 1.50 is an edit and replaces the live value.
    private static boolean changed(String draft, String shown) {
        return !draft.trim().equals(shown);
    }

    private static boolean isNumber(String text) {
        try {
            new BigDecimal(text.trim());
            return true;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
