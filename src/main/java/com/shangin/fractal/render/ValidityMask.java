package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class ValidityMask {

    private final int width;
    private final int height;
    private final int totalPixels;

    private final BitSet ready;

    public ValidityMask(
            int width,
            int height
    ) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }

        this.width = width;
        this.height = height;
        this.totalPixels = Math.multiplyExact(width, height);

        ready = new BitSet(totalPixels);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public synchronized void markReady(RenderRegion region) {
        validateRegion(region);

        for (int y = region.y(); y < region.y() + region.height(); y++) {

            int from = y * width + region.x();
            int to = from + region.width();

            ready.set(from, to);
        }
    }

    public synchronized boolean isReady(
            int x,
            int y
    ) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException();
        }

        return ready.get(y * width + x);
    }

    public synchronized boolean isRegionReady(RenderRegion region) {
        validateRegion(region);

        for (int y = region.y(); y < region.y() + region.height(); y++) {

            int from = y * width + region.x();
            int to = from + region.width();

            int firstMissing = ready.nextClearBit(from);

            if (firstMissing < to) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the not-yet-ready parts of a region as horizontal one-row spans.
     * The snapshot is created while holding the mask lock, so callers can use it
     * without performing a synchronized readiness check for every pixel.
     */
    public synchronized List<RenderRegion> missingRowSpans(RenderRegion region) {
        validateRegion(region);

        List<RenderRegion> missing = new ArrayList<>();

        for (int y = region.y(); y < region.y() + region.height(); y++) {
            int rowOffset = y * width;
            int rowFrom = rowOffset + region.x();
            int rowTo = rowFrom + region.width();
            int missingFrom = ready.nextClearBit(rowFrom);

            while (missingFrom < rowTo) {
                int nextReady = ready.nextSetBit(missingFrom);
                int missingTo = nextReady < 0
                        ? rowTo
                        : Math.min(nextReady, rowTo);

                missing.add(new RenderRegion(
                        missingFrom - rowOffset,
                        y,
                        missingTo - missingFrom,
                        1
                ));

                if (nextReady < 0 || nextReady >= rowTo) {
                    break;
                }

                missingFrom = ready.nextClearBit(nextReady);
            }
        }

        return List.copyOf(missing);
    }

    public synchronized boolean isComplete() {
        return ready.nextClearBit(0) >= totalPixels;
    }

    public synchronized int readyPixelCount() {
        return ready.cardinality();
    }

    private void validateRegion(RenderRegion region) {
        if (region.x() < 0
                || region.y() < 0
                || region.width() <= 0
                || region.height() <= 0
                || region.x() + region.width() > width
                || region.y() + region.height() > height) {
            throw new IllegalArgumentException("Region is outside validity mask");
        }
    }
}
