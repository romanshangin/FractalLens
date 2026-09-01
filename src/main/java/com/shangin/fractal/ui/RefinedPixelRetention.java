package com.shangin.fractal.ui;

import com.shangin.fractal.render.ValidityMask;

import java.util.Objects;

/** Removes provisional base colors before a refined staging frame is exposed. */
final class RefinedPixelRetention {

    private RefinedPixelRetention() {}

    static void clearUnconfirmed(
            int[] pixels,
            int width,
            int height,
            ValidityMask validity
    ) {
        Objects.requireNonNull(pixels);
        Objects.requireNonNull(validity);

        if (width < 1 || height < 1 || pixels.length != width * height
                || validity.width() != width || validity.height() != height) {
            throw new IllegalArgumentException("Pixels and validity dimensions must match");
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!validity.isReady(x, y)) {
                    pixels[y * width + x] = 0;
                }
            }
        }
    }
}
