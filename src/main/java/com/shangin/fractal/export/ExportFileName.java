package com.shangin.fractal.export;

import java.util.Locale;
import java.util.Objects;

/** Builds portable descriptive file names for exported fractal images. */
public final class ExportFileName {

    private ExportFileName() {}

    public static String create(
            String fractalName,
            String paletteName,
            double centerReal,
            double centerImaginary
    ) {
        // Retained for source compatibility; the palette is intentionally omitted from the filename.
        Objects.requireNonNull(paletteName);
        return String.format(
                Locale.ROOT,
                "%s_%.12g_%+.12gi.png",
                normalize(fractalName),
                centerReal,
                centerImaginary
        );
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value)
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        return normalized.isEmpty()
                ? "fractal"
                : normalized;
    }
}
