package com.shangin.fractal.render;

import java.util.BitSet;
import java.util.Objects;

/** Immutable copy of already refined display pixels available for AA reuse. */
public final class RefinedPixelSnapshot {

    private final int width;
    private final int height;
    private final int[] colors;
    private final ValidityMask validity;
    private final BitSet ready;

    public RefinedPixelSnapshot(
            int width,
            int height,
            int[] colors,
            ValidityMask validity
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        this.colors = Objects.requireNonNull(colors).clone();
        this.validity = Objects.requireNonNull(validity).copy();
        ready = this.validity.readyBitsCopy();
        if (colors.length != Math.multiplyExact(width, height)
                || validity.width() != width
                || validity.height() != height) {
            throw new IllegalArgumentException("Refined pixel dimensions do not match");
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int[] colors() {
        return colors.clone();
    }

    public ValidityMask validity() {
        return validity.copy();
    }

    public int color(int x, int y) {
        return colors[index(x, y)];
    }

    public boolean isRefined(int x, int y) {
        return ready.get(index(x, y));
    }

    public boolean isRegionRefined(RenderRegion region) {
        validateRegion(region);

        for (int y = region.y(); y < region.y() + region.height(); y++) {
            int from = y * width + region.x();
            if (ready.nextClearBit(from) < from + region.width()) {
                return false;
            }
        }
        return true;
    }

    private int index(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException();
        }
        return y * width + x;
    }

    private void validateRegion(RenderRegion region) {
        if (region.x() < 0
                || region.y() < 0
                || region.width() <= 0
                || region.height() <= 0
                || region.x() + region.width() > width
                || region.y() + region.height() > height) {
            throw new IllegalArgumentException("Region is outside refined snapshot");
        }
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
