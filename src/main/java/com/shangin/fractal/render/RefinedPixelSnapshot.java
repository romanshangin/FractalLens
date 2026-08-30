package com.shangin.fractal.render;

import java.util.Objects;

/** Immutable copy of already refined display pixels available for AA reuse. */
public record RefinedPixelSnapshot(
        int width,
        int height,
        int[] colors,
        ValidityMask validity
) {
    public RefinedPixelSnapshot {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        colors = Objects.requireNonNull(colors).clone();
        validity = Objects.requireNonNull(validity).copy();
        if (colors.length != Math.multiplyExact(width, height)
                || validity.width() != width
                || validity.height() != height) {
            throw new IllegalArgumentException("Refined pixel dimensions do not match");
        }
    }

    @Override
    public int[] colors() {
        return colors.clone();
    }

    @Override
    public ValidityMask validity() {
        return validity.copy();
    }

    public int color(int x, int y) {
        return colors[y * width + x];
    }

    public boolean isRefined(int x, int y) {
        return validity.isReady(x, y);
    }

    public static RefinedPixelSnapshot empty(int width, int height) {
        return new RefinedPixelSnapshot(
                width,
                height,
                new int[Math.multiplyExact(width, height)],
                new ValidityMask(width, height)
        );
    }
}
