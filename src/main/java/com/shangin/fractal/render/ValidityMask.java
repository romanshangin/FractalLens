package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class ValidityMask {

    private final int width;
    private final int height;
    private final int totalPixels;

    // Flat words allow bounded scans without allocating a BitSet for each tile row.
    // Both words and the exact (overlap-safe) count are protected by this mask's lock.
    private final long[] ready;
    private int readyPixels;

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

        ready = new long[(int) ((totalPixels + 63L) / 64)];
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

            int firstWord = from >>> 6;
            int lastWord = (to - 1) >>> 6;
            for (int word = firstWord; word <= lastWord; word++) {
                long bits = -1L;
                if (word == firstWord) bits &= -1L << from;
                // Java masks long shift distances: a word-aligned end keeps all bits.
                if (word == lastWord) bits &= -1L >>> -to;
                readyPixels += Long.bitCount(bits & ~ready[word]);
                ready[word] |= bits;
            }
        }
    }

    public synchronized boolean isReady(
            int x,
            int y
    ) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException();
        }

        int pixel = y * width + x;
        return (ready[pixel >>> 6] & (1L << pixel)) != 0;
    }

    public synchronized boolean isRegionReady(RenderRegion region) {
        validateRegion(region);

        for (int y = region.y(); y < region.y() + region.height(); y++) {

            int from = y * width + region.x();
            int to = from + region.width();

            int firstMissing = nextBit(from, to, false);

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
        return missingRowSpansSnapshot(region, null);
    }

    /** Cancellation discards the partial plan; callers must not submit it. */
    public synchronized List<RenderRegion> missingRowSpans(
            RenderRegion region, BooleanSupplier cancelled
    ) {
        Objects.requireNonNull(cancelled);
        return missingRowSpansSnapshot(region, cancelled);
    }

    private List<RenderRegion> missingRowSpansSnapshot(RenderRegion region, BooleanSupplier cancelled) {
        validateRegion(region);
        if (scanCancelled(cancelled) || readyPixels == totalPixels) return List.of();
        List<RenderRegion> missing = new ArrayList<>();

        for (int y = region.y(); y < region.y() + region.height(); y++) {
            if (scanCancelled(cancelled)) return List.of();
            int rowOffset = y * width;
            int rowFrom = rowOffset + region.x();
            int rowTo = rowFrom + region.width();
            int missingFrom = nextBit(rowFrom, rowTo, false);

            while (missingFrom < rowTo) {
                int nextReady = nextBit(missingFrom, rowTo, true);
                int missingTo = nextReady;

                missing.add(new RenderRegion(
                        missingFrom - rowOffset,
                        y,
                        missingTo - missingFrom,
                        1
                ));

                if (nextReady >= rowTo) {
                    break;
                }

                missingFrom = nextBit(nextReady, rowTo, false);
            }
        }

        return List.copyOf(missing);
    }

    public synchronized boolean isComplete() {
        return readyPixels == totalPixels;
    }

    public synchronized int readyPixelCount() {
        return readyPixels;
    }

    public synchronized void clear() {
        Arrays.fill(ready, 0L);
        readyPixels = 0;
    }

    public synchronized ValidityMask copy() {
        ValidityMask copy = new ValidityMask(width, height);
        System.arraycopy(ready, 0, copy.ready, 0, ready.length);
        copy.readyPixels = readyPixels;
        return copy;
    }

    synchronized BitSet readyBitsCopy() {
        return BitSet.valueOf(ready);
    }

    /** Returns a compact row-major snapshot local to one region. */
    synchronized BitSet readyBitsCopy(RenderRegion region) {
        validateRegion(region);
        BitSet copy = new BitSet(region.width() * region.height());
        for (int y = region.y(); y < region.y() + region.height(); y++) {
            int rowFrom = y * width + region.x();
            int rowTo = rowFrom + region.width();
            for (int pixel = nextBit(rowFrom, rowTo, true); pixel < rowTo;
                 pixel = nextBit(pixel + 1, rowTo, true)) {
                copy.set((y - region.y()) * region.width() + pixel - rowFrom);
            }
        }
        return copy;
    }

    /** Replaces this mask with source pixels shifted into target coordinates. */
    public void copyShiftedFrom(ValidityMask source, PixelShift shift) {
        ValidityMask snapshot = source.copy();
        clear();

        int sourceXFrom = Math.max(0, -shift.dx());
        int sourceXTo = Math.min(source.width, width - shift.dx());
        int sourceYFrom = Math.max(0, -shift.dy());
        int sourceYTo = Math.min(source.height, height - shift.dy());

        for (int sourceY = sourceYFrom; sourceY < sourceYTo; sourceY++) {
            int targetY = sourceY + shift.dy();
            int runStart = -1;

            for (int sourceX = sourceXFrom; sourceX < sourceXTo; sourceX++) {
                int targetX = sourceX + shift.dx();
                if (snapshot.isReady(sourceX, sourceY)) {
                    if (runStart < 0) {
                        runStart = targetX;
                    }
                } else if (runStart >= 0) {
                    markReady(new RenderRegion(runStart, targetY, targetX - runStart, 1));
                    runStart = -1;
                }
            }

            if (runStart >= 0) {
                markReady(new RenderRegion(
                        runStart,
                        targetY,
                        sourceXTo + shift.dx() - runStart,
                        1
                ));
            }
        }
    }

    private static boolean scanCancelled(BooleanSupplier cancelled) {
        return cancelled != null && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted());
    }

    /** Searches only words intersecting [from, to); returns to when absent. */
    private int nextBit(int from, int to, boolean set) {
        if (from >= to) return to;
        int word = from >>> 6;
        int lastWord = (to - 1) >>> 6;
        long bits = (set ? ready[word] : ~ready[word]) & (-1L << from);
        while (true) {
            if (word == lastWord) bits &= -1L >>> -to;
            if (bits != 0) return (word << 6) + Long.numberOfTrailingZeros(bits);
            if (word == lastWord) return to;
            word++;
            bits = set ? ready[word] : ~ready[word];
        }
    }

    private void validateRegion(RenderRegion region) {
        if (region.x() < 0
                || region.y() < 0
                || region.width() <= 0
                || region.height() <= 0
                || region.x() > width - region.width()
                || region.y() > height - region.height()) {
            throw new IllegalArgumentException("Region is outside validity mask");
        }
    }
}
