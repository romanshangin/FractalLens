package com.shangin.fractal.ui;

import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Compact, re-enterable text for a view: the center rounded to within 0.0005 of the
 * finer render-grid pixel step and the zoom to twelve significant digits. Go to
 * Coordinates shows it, compares drafts against it and Copy Coordinates and Zoom copies it;
 * scenes keep the exact values.
 */
record CoordinateText(String real, String imaginary, String zoom) {
    CoordinateText {
        Objects.requireNonNull(real);
        Objects.requireNonNull(imaginary);
        Objects.requireNonNull(zoom);
    }

    /** Text for a viewport on a render grid of the given size; zoom is relative to the home view. */
    static CoordinateText of(Viewport viewport, int renderWidth, int renderHeight, BigDecimal zoom) {
        BigDecimal step = viewport.realUnitsPerPixelExact(renderWidth, renderHeight)
                .min(viewport.imaginaryUnitsPerPixelExact(renderHeight));
        return new CoordinateText(RenderValueFormat.coordinate(viewport.center().real(), step),
                RenderValueFormat.coordinate(viewport.center().imaginary(), step), RenderValueFormat.zoom(zoom));
    }

    /** The Copy Coordinates and Zoom text; every value is a complete decimal, without ellipsis or ≈. */
    String clipboard() {
        return "Real: " + real + "\nImaginary: " + imaginary + "\nZoom: " + zoom;
    }
}
