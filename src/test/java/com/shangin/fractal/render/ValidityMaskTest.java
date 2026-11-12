package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidityMaskTest {

    @Test
    void cachedCompletenessFollowsEveryChangeAndCompleteMasksReportNoMissingWork() {
        ValidityMask mask = new ValidityMask(65, 3);
        RenderRegion all = new RenderRegion(0, 0, 65, 3);
        RenderRegion lastTile = new RenderRegion(32, 0, 33, 3);
        assertFalse(mask.isComplete());

        mask.markReady(new RenderRegion(0, 0, 65, 2));
        assertFalse(mask.isComplete());
        assertEquals(List.of(new RenderRegion(0, 2, 65, 1)), mask.missingRowSpans(all));
        assertEquals(64, mask.markReadyCountingNew(new RenderRegion(0, 2, 64, 1)));
        assertFalse(mask.isComplete(), "One pixel is still missing");
        assertEquals(List.of(new RenderRegion(64, 2, 1, 1)), mask.missingRowSpans(lastTile));
        assertFalse(mask.isRegionReady(lastTile));

        mask.markReady(new RenderRegion(64, 2, 1, 1));
        assertTrue(mask.isComplete());
        assertTrue(mask.missingRowSpans(lastTile).isEmpty());
        assertTrue(mask.missingRowSpans(lastTile, () -> false).isEmpty());
        assertTrue(mask.isRegionReady(lastTile));
        assertEquals(0, mask.markReadyCountingNew(all));
        assertTrue(mask.isComplete(), "Adding ready pixels keeps a complete mask complete");

        ValidityMask copy = mask.copy();
        assertTrue(copy.isComplete());
        copy.copyShiftedFrom(mask, new PixelShift(1, 0));
        assertFalse(copy.isComplete(), "A shift exposes a missing column");
        assertEquals(List.of(new RenderRegion(0, 0, 1, 1), new RenderRegion(0, 1, 1, 1), new RenderRegion(0, 2, 1, 1)),
                copy.missingRowSpans(new RenderRegion(0, 0, 32, 3)));

        ValidityMask partial = new ValidityMask(65, 3);
        partial.markReady(new RenderRegion(0, 0, 10, 1));
        mask.copyFrom(partial);
        assertFalse(mask.isComplete(), "Copying a partial mask forgets completeness");
        assertFalse(mask.isRegionReady(lastTile));
        mask.copyFrom(copy.copy());
        mask.markReady(new RenderRegion(0, 0, 1, 3));
        assertTrue(mask.isComplete());

        mask.clear();
        assertFalse(mask.isComplete());
        assertEquals(3, mask.missingRowSpans(lastTile).size());
    }

    @Test
    void anyReadyFindsOnlyPixelsInsideTheRegion() {
        RenderRegion region = new RenderRegion(3, 2, 4, 3);
        assertFalse(new ValidityMask(10, 8).anyReady(region));

        int[][] inside = {{3, 2}, {6, 2}, {3, 4}, {6, 4}, {5, 3}};
        int[][] outside = {{2, 2}, {7, 2}, {2, 4}, {7, 4}, {4, 1}, {4, 5}, {0, 0}, {9, 7}, {9, 1}, {0, 6}};
        for (int[] pixel : inside) {
            assertTrue(maskWith(pixel).anyReady(region), "Inside " + pixel[0] + "," + pixel[1]);
        }
        for (int[] pixel : outside) {
            assertFalse(maskWith(pixel).anyReady(region), "Outside " + pixel[0] + "," + pixel[1]);
        }
        // Ready pixels left and right of the region in its rows, and one below it.
        ValidityMask around = maskWith(outside);
        assertFalse(around.anyReady(region));
        around.markReady(new RenderRegion(6, 4, 1, 1));
        assertTrue(around.anyReady(region));
        assertTrue(maskWith(new int[] {0, 0}).anyReady(new RenderRegion(0, 0, 1, 1)));
    }

    @Test
    void anyReadyMatchesAPixelByPixelSearch() {
        Random random = new Random(20261107);
        for (int trial = 0; trial < 2000; trial++) {
            int width = 1 + random.nextInt(12);
            int height = 1 + random.nextInt(9);
            ValidityMask mask = new ValidityMask(width, height);
            for (int pixels = random.nextInt(5); pixels > 0; pixels--) {
                mask.markReady(new RenderRegion(random.nextInt(width), random.nextInt(height), 1, 1));
            }
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            RenderRegion region = new RenderRegion(x, y, 1 + random.nextInt(width - x), 1 + random.nextInt(height - y));
            boolean expected = false;
            for (int py = region.y(); py < region.y() + region.height(); py++) {
                for (int px = region.x(); px < region.x() + region.width(); px++) {
                    expected |= mask.isReady(px, py);
                }
            }
            assertEquals(expected, mask.anyReady(region), "Trial " + trial);
        }
    }

    private static ValidityMask maskWith(int[]... pixels) {
        ValidityMask mask = new ValidityMask(10, 8);
        for (int[] pixel : pixels) mask.markReady(new RenderRegion(pixel[0], pixel[1], 1, 1));
        return mask;
    }

    @Test
    void copyFromReplacesTheMaskWithAnEquallySizedSource() {
        ValidityMask source = new ValidityMask(8, 6);
        source.markReady(new RenderRegion(1, 1, 2, 3));
        ValidityMask target = new ValidityMask(8, 6);
        target.markReady(new RenderRegion(5, 0, 3, 6));

        target.copyFrom(source);

        assertEquals(6, target.readyPixelCount());
        assertTrue(target.isRegionReady(new RenderRegion(1, 1, 2, 3)));
        assertFalse(target.isReady(5, 0));
        assertThrows(IllegalArgumentException.class, () -> target.copyFrom(new ValidityMask(4, 6)));
    }

    @Test
    void markReadyCountingNewCountsOnlyPreviouslyMissingPixels() {
        ValidityMask mask = new ValidityMask(8, 6);
        mask.markReady(new RenderRegion(2, 1, 3, 2));

        assertEquals(4 * 4 - 3 * 2, mask.markReadyCountingNew(new RenderRegion(1, 0, 4, 4)));
        assertEquals(0, mask.markReadyCountingNew(new RenderRegion(1, 0, 4, 4)));
        assertEquals(16, mask.readyPixelCount());
        assertTrue(mask.isRegionReady(new RenderRegion(1, 0, 4, 4)));
        assertThrows(IllegalArgumentException.class,
                () -> mask.markReadyCountingNew(new RenderRegion(6, 0, 4, 1)));
    }

    @Test
    void overflowingRegionExtentsAreRejected() {
        ValidityMask mask = new ValidityMask(8, 8);
        RenderRegion outside = new RenderRegion(Integer.MAX_VALUE, 0, 2, 1);
        assertThrows(IllegalArgumentException.class, () -> mask.markReady(outside));
        assertThrows(IllegalArgumentException.class, () -> mask.missingRowSpans(outside));
    }

    @Test
    void missingRowSpansShouldDescribeOnlyGapsInsideRegion() {
        ValidityMask mask = new ValidityMask(8, 3);
        mask.markReady(new RenderRegion(0, 0, 2, 1));
        mask.markReady(new RenderRegion(5, 0, 3, 1));
        mask.markReady(new RenderRegion(2, 1, 4, 1));

        assertEquals(
                List.of(
                        new RenderRegion(2, 0, 3, 1),
                        new RenderRegion(0, 1, 2, 1),
                        new RenderRegion(6, 1, 2, 1),
                        new RenderRegion(0, 2, 8, 1)
                ),
                mask.missingRowSpans(new RenderRegion(0, 0, 8, 3))
        );
    }

    @Test
    void fullyReadyRegionShouldHaveNoMissingSpans() {
        ValidityMask mask = new ValidityMask(8, 3);
        RenderRegion region = new RenderRegion(2, 1, 4, 2);
        mask.markReady(region);

        assertTrue(mask.missingRowSpans(region).isEmpty());
    }

    @Test
    void shiftedCopyShouldPreserveOnlyOverlappingReadyPixels() {
        ValidityMask source = new ValidityMask(6, 4);
        source.markReady(new RenderRegion(1, 1, 4, 2));
        ValidityMask target = new ValidityMask(6, 4);

        target.copyShiftedFrom(source, new PixelShift(2, -1));

        assertEquals(6, target.readyPixelCount());
        assertTrue(target.isRegionReady(new RenderRegion(3, 0, 3, 2)));
        assertFalse(target.isReady(2, 0));
        assertFalse(target.isReady(5, 2));
        assertEquals(8, source.readyPixelCount());
    }

    @Test
    void inPlaceShiftShouldUseAnUnmodifiedSnapshot() {
        ValidityMask mask = new ValidityMask(6, 2);
        mask.markReady(new RenderRegion(0, 0, 5, 1));

        mask.copyShiftedFrom(mask, new PixelShift(1, 1));

        assertEquals(5, mask.readyPixelCount());
        assertTrue(mask.isRegionReady(new RenderRegion(1, 1, 5, 1)));
        assertFalse(mask.isReady(0, 0));
    }

    @Test
    void zeroShiftShouldCopyEveryReadyPixelAndRemainIndependent() {
        ValidityMask source = new ValidityMask(4, 3);
        source.markReady(new RenderRegion(1, 0, 2, 3));
        ValidityMask target = new ValidityMask(4, 3);

        target.copyShiftedFrom(source, new PixelShift(0, 0));
        source.clear();

        assertEquals(6, target.readyPixelCount());
        assertEquals(0, source.readyPixelCount());
    }

    @Test
    void nonOverlappingShiftShouldClearTheTarget() {
        ValidityMask mask = new ValidityMask(4, 3);
        mask.markReady(new RenderRegion(0, 0, 4, 3));

        mask.copyShiftedFrom(mask, new PixelShift(4, 0));

        assertEquals(0, mask.readyPixelCount());
    }

    @Test
    void negativeHorizontalAndPositiveVerticalShiftShouldPreserveOverlap() {
        ValidityMask source = new ValidityMask(6, 4);
        source.markReady(new RenderRegion(2, 0, 4, 3));
        ValidityMask target = new ValidityMask(6, 4);

        target.copyShiftedFrom(source, new PixelShift(-2, 1));

        assertEquals(12, target.readyPixelCount());
        assertTrue(target.isRegionReady(new RenderRegion(0, 1, 4, 3)));
        assertFalse(target.isReady(4, 1));
        assertFalse(target.isReady(0, 0));
    }

    @Test
    void shiftedCopyShouldCropDifferentDimensions() {
        ValidityMask source = new ValidityMask(6, 5);
        source.markReady(new RenderRegion(0, 0, 6, 5));
        ValidityMask target = new ValidityMask(4, 3);
        target.copyShiftedFrom(source, new PixelShift(-1, -1));
        assertTrue(target.isComplete());
        ValidityMask expanded = new ValidityMask(6, 5);
        expanded.copyShiftedFrom(target, new PixelShift(1, 1));
        assertEquals(12, expanded.readyPixelCount());
        assertTrue(expanded.isRegionReady(new RenderRegion(1, 1, 4, 3)));
        assertFalse(expanded.isReady(0, 0));
    }
    @Test
    void wordBoundariesCountsAndSnapshotsShouldMatchPixelOracle() {
        var random = new java.util.Random(910903);
        for (int width : new int[]{1, 31, 63, 64, 65, 127, 129, 3024}) {
            int height = 7;
            ValidityMask mask = new ValidityMask(width, height);
            java.util.BitSet expected = new java.util.BitSet(width * height);
            for (int trial = 0; trial < 100; trial++) {
                int x = random.nextInt(width), y = random.nextInt(height);
                RenderRegion marked = new RenderRegion(x, y, 1 + random.nextInt(width - x), 1 + random.nextInt(height - y));
                mask.markReady(marked);
                mask.markReady(marked); // Repeated publication must not double-count.
                for (int row = y; row < y + marked.height(); row++) {
                    expected.set(row * width + x, row * width + x + marked.width());
                }
                assertEquals(expected.cardinality(), mask.readyPixelCount());
                assertEquals(expected.cardinality() == width * height, mask.isComplete());
                assertEquals(expected, mask.readyBitsCopy());
                assertEquals(expected, mask.copy().readyBitsCopy());
                x = random.nextInt(width); y = random.nextInt(height);
                RenderRegion query = new RenderRegion(x, y, 1 + random.nextInt(width - x), 1 + random.nextInt(height - y));
                var gaps = new java.util.ArrayList<RenderRegion>();
                var local = new java.util.BitSet(query.width() * query.height());
                for (int row = y; row < y + query.height(); row++) {
                    int start = -1;
                    for (int col = x; col < x + query.width(); col++) {
                        boolean ready = expected.get(row * width + col);
                        assertEquals(ready, mask.isReady(col, row));
                        if (ready) local.set((row - y) * query.width() + col - x);
                        if (!ready && start < 0) start = col;
                        if (ready && start >= 0) {
                            gaps.add(new RenderRegion(start, row, col - start, 1));
                            start = -1;
                        }
                    }
                    if (start >= 0) gaps.add(new RenderRegion(start, row, x + query.width() - start, 1));
                }
                assertEquals(gaps, mask.missingRowSpans(query));
                assertEquals(gaps.isEmpty(), mask.isRegionReady(query));
                assertEquals(local, mask.readyBitsCopy(query));
                if (trial % 10 == 9) {
                    ValidityMask copy = mask.copy();
                    mask.clear();
                    assertEquals(expected, copy.readyBitsCopy());
                    expected.clear();
                    assertEquals(0, mask.readyPixelCount());
                    assertFalse(mask.isComplete());
                }
            }
        }
    }

    @Test
    void cancellationDuringScanMustDiscardPartialPlanWithoutChangingMask() {
        ValidityMask mask = new ValidityMask(65, 20);
        mask.markReady(new RenderRegion(32, 0, 1, 20));
        var before = mask.readyBitsCopy();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(mask.missingRowSpans(new RenderRegion(0, 0, 65, 20),
                () -> checks.incrementAndGet() >= 4).isEmpty());
        assertEquals(4, checks.get());
        assertEquals(before, mask.readyBitsCopy());
        assertFalse(mask.isComplete());
    }

    @Test
    void interruptionCancelsPlanningButDoesNotChangePlainSnapshotSemantics() {
        ValidityMask mask = new ValidityMask(65, 1);
        RenderRegion all = new RenderRegion(0, 0, 65, 1);
        Thread.currentThread().interrupt();
        try {
            assertTrue(mask.missingRowSpans(all, () -> false).isEmpty());
            assertEquals(List.of(all), mask.missingRowSpans(all));
        } finally {
            Thread.interrupted();
        }
    }

}
