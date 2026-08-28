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
        return String.format(
                Locale.ROOT,
                "%s_%s_%.12g_%+.12gi.png",
                normalize(fractalName),
                normalize(paletteName),
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
