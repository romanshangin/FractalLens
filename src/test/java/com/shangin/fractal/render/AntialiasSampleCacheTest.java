package com.shangin.fractal.render;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.formula.FractalSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntialiasSampleCacheTest {

    private static final double INFINITE = Double.POSITIVE_INFINITY;

    @Test
    void snapshotsShowOnlyEntriesThePalettesThresholdSelects() {
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var steep = new SmoothPaletteColoring(new com.shangin.fractal.coloring.GradientPalette(java.util.List.of(
                new com.shangin.fractal.coloring.ColorStop(0.0, 0xFF000000),
                new com.shangin.fractal.coloring.ColorStop(0.5, 0xFF000000),
                new com.shangin.fractal.coloring.ColorStop(0.52, 0xFFFFFFFF),
                new com.shangin.fractal.coloring.ColorStop(1.0, 0xFFFFFFFF))),
                SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.0);
        double shared = com.shangin.fractal.coloring.PaletteEdgeThresholds.sharedThreshold(false);
        double steepThreshold = com.shangin.fractal.coloring.PaletteEdgeThresholds.phaseThreshold(steep.palette(), false);
        assertTrue(steepThreshold < shared);
        double between = (steepThreshold + shared) / 2;
        var cache = new AntialiasSampleCache();
        var batch = cache.newBatch(3, steep);
        var lookup = new SmoothColorLookup(steep);
        batch.add(1, samples(10, 4), lookup, INFINITE, false);
        batch.add(2, samples(11, 4), lookup, between, false);
        // A deep entry is compared with the deep threshold, which is twice as large for Ice.
        batch.add(3, samples(12, 4), lookup, shared * 1.5, true);
        cache.merge(batch);

        var forSteep = cache.snapshotFor(steep);
        assertEquals(java.util.Set.of(1, 2, 3), toSet(forSteep.pixelMask()));
        var forIce = cache.snapshotFor(ice);
        assertEquals(java.util.Set.of(1), toSet(forIce.pixelMask()), "Ice must not show the steep palette's extra AA");
        assertSame(forIce, cache.snapshotFor(new SmoothPaletteColoring(PalettePreset.FIRE.palette(),
                SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.4)), "Palettes sharing a threshold share one selection");
        assertEquals(3, cache.size(), "Filtering must keep every cached sample");
        var retained = cache.retain();
        assertEquals(INFINITE, retained.completeThreshold());
        assertEquals(java.util.Set.of(1), toSet(retained.snapshotFor(ice).pixelMask()));
        assertEquals(0.5, retained.withCompleteThreshold(0.5).completeThreshold());
    }

    private static java.util.Set<Integer> toSet(java.util.BitSet bits) {
        return bits.stream().boxed().collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void rejectsPixelsBeyondTheBudgetAndReportsTheDropUntilCleared() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        AntialiasSampleCache cache = new AntialiasSampleCache(budgetHolding(2, 4));
        cache.put(1, samples(1.0, 4), coloring);
        cache.put(2, samples(2.0, 4), coloring);
        assertFalse(cache.dropped());
        cache.put(3, samples(3.0, 4), coloring);

        assertTrue(cache.usedBytes() <= cache.maxBytes());
        assertTrue(cache.contains(1), "Cached samples are never evicted for newer ones");
        assertTrue(cache.contains(2));
        assertFalse(cache.contains(3));
        assertTrue(cache.dropped(), "A rejected pixel must be reported as missing");
        var retained = cache.retain();
        assertTrue(retained.dropped(), "Retained samples remember that some were rejected");
        var restored = new AntialiasSampleCache();
        restored.restore(retained);
        assertTrue(restored.dropped(), "Restoring keeps the rejection");
        cache.clear();
        assertFalse(cache.dropped());
        cache.put(3, samples(3.0, 4), coloring);
        assertTrue(cache.contains(3));
    }

    /** The smallest budget that holds the first {@code kept} of {@code kept + 1} pixels. */
    private static long budgetHolding(int kept, int phases) {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        for (long budget = 0; budget < 1 << 16; budget++) {
            var cache = new AntialiasSampleCache(budget);
            for (int pixel = 1; pixel <= kept + 1; pixel++) cache.put(pixel, samples(pixel, phases), coloring);
            if (cache.size() == kept) return budget;
        }
        throw new AssertionError("No budget holds exactly " + kept + " entries");
    }

    @Test
    void replacingAPixelKeepsOneEntryAndCompactsTheOldOnes() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        var cache = new AntialiasSampleCache(1 << 20);
        for (int round = 0; round < 1000; round++) {
            cache.put(round % 3, samples(round, 4), coloring);
        }
        assertEquals(3, cache.size());
        assertFalse(cache.dropped());
        var expected = new AntialiasSampleCache(1 << 20);
        for (int pixel = 0; pixel < 3; pixel++) expected.put(pixel, samples(997 + pixel, 4), coloring);
        for (int pixel = 0; pixel < 3; pixel++) {
            assertEquals(expected.color(pixel, coloring, 100), cache.color(pixel, coloring, 100));
        }
        assertTrue(cache.usedBytes() < 8192, "Replaced entries must not accumulate: " + cache.usedBytes());
    }

    @Test
    void spaceOfReplacedEntriesIsReclaimedBeforeAPixelIsRejected() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        var cache = new AntialiasSampleCache(budgetHolding(3, 4));
        cache.put(1, samples(1.0, 4), coloring);
        cache.put(2, samples(2.0, 4), coloring);
        cache.put(1, samples(5.0, 4), coloring);
        cache.put(3, samples(3.0, 4), coloring);

        assertTrue(cache.contains(3), "The replaced entry's space must be reused");
        assertFalse(cache.dropped());
        assertEquals(0, cache.rejections());
        assertEquals(3, cache.size());
        var expected = new AntialiasSampleCache();
        expected.put(1, samples(5.0, 4), coloring);
        assertEquals(expected.color(1, coloring, 100), cache.color(1, coloring, 100));
        cache.put(4, samples(4.0, 4), coloring);
        assertTrue(cache.dropped());
        assertEquals(1, cache.rejections());
        cache.markComplete();
        assertFalse(cache.dropped());
        cache.clear();
        assertEquals(1, cache.rejections(), "Clearing keeps the rejection count");
    }

    @Test
    void snapshotsKeepTheirEntriesWhileTheCacheAppendsGrowsAndShifts() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        var lookup = new SmoothColorLookup(coloring);
        var cache = new AntialiasSampleCache(1 << 20);
        cache.put(0, samples(1.0, 4), coloring);
        cache.put(1, samples(2.0, 16), coloring);
        var snapshot = cache.snapshotFor(coloring);
        int[] before = new int[4];
        snapshot.recolorInto(before, lookup);
        for (int pixel = 2; pixel < 1000; pixel++) cache.put(pixel % 4 == 0 ? 0 : pixel, samples(pixel, 4), coloring);
        cache.shift(10, 10, 100, new PixelShift(1, 0));
        int[] after = new int[4];
        snapshot.recolorInto(after, lookup);
        assertArrayEquals(before, after);
        assertEquals(2, snapshot.size());
        assertEquals(java.util.Set.of(0, 1), toSet(snapshot.pixelMask()));
    }

    @Test
    void defaultBudgetRetainsEveryPixelOfARetinaWindowFrame() {
        // The verified 2592x1738 Retina window whose palette-independent candidates
        // overflowed the previous per-object cache; here every pixel is a direct candidate.
        int pixels = 2592 * 1738;
        var cache = new AntialiasSampleCache();
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var sixteen = samples(6, 16);
        for (int pixel = 0; pixel < pixels; pixel++) {
            cache.put(pixel, sixteen, coloring);
        }

        assertEquals(pixels, cache.size(), "The default budget must hold the whole frame");
        assertFalse(cache.dropped());
        assertTrue(cache.usedBytes() <= cache.maxBytes());
        cache.clear();
    }

    @Test
    void recolorsStoredSamplesAndShiftsThemForFrameReuse() {
        AntialiasSampleCache cache = new AntialiasSampleCache(4096);
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.02, 0.5);
        cache.put(1, samples(20.0, 4), ice);

        assertNotEquals(cache.color(1, ice, 100), cache.color(1, fire, 100));
        cache.shift(4, 3, new PixelShift(1, 1));
        assertFalse(cache.contains(1));
        assertTrue(cache.contains(6));
    }

    @Test
    void retainedSamplesSurviveLaterChangesAndRestoreTheSameColors() {
        var cache = new AntialiasSampleCache(8192);
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.02, 0.5);
        assertNull(cache.retain(), "An empty cache retains nothing");
        cache.put(1, samples(20.0, 4), ice);
        cache.put(5, samples(7.0, 4), ice);
        Integer one = cache.color(1, fire, 100);
        Integer five = cache.color(5, fire, 100);

        AntialiasSampleCache.Retained retained = cache.retain();
        assertEquals(2, retained.size());
        assertEquals(cache.usedBytes(), retained.bytes());
        cache.shift(4, 3, new PixelShift(1, 1));
        cache.put(2, samples(3.0, 4), ice);
        cache.clear();

        var restored = new AntialiasSampleCache(8192);
        restored.restore(retained);
        assertEquals(2, restored.size());
        assertEquals(one, restored.color(1, fire, 100));
        assertEquals(five, restored.color(5, fire, 100));
        assertFalse(restored.contains(2));
        assertEquals(2, retained.snapshotFor(fire).size(), "Any palette of the same color scale reuses them");
        assertEquals(0, retained.snapshotFor(new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.05, 0.0)).size());

        var small = new AntialiasSampleCache(1100);
        small.restore(retained);
        assertTrue(small.usedBytes() <= small.maxBytes(), "Restoring respects the receiving budget");
    }

    @Test
    void preservesSmoothPhaseAtVeryHighIterationCounts() {
        AntialiasSampleCache cache = new AntialiasSampleCache(4096);
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.0075, 0.37);
        FractalSample sample = new FractalSample(20_000_001, true, 4.0, 0.0);

        cache.put(0, new FractalSample[]{sample}, coloring);

        assertEquals(
                coloring.color(
                        sample.iterations(), sample.smoothIterations(), true, 20_000_100),
                cache.color(0, coloring, 20_000_100)
        );
    }

    @Test
    void accountsForRetainedObjectsAndReleasesTheTableOnClear() {
        var cache = new AntialiasSampleCache(4096);
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        cache.put(0, samples(4, 16), coloring);
        assertTrue(cache.usedBytes() > 16 * Short.BYTES + Integer.BYTES,
                "Encoded payload alone must not be reported as retained cache memory");
        long beforeSnapshot = cache.usedBytes();
        assertEquals(1, cache.snapshotFor(coloring).size());
        assertEquals(beforeSnapshot, cache.usedBytes(), "Current snapshot storage is prepaid");
        cache.clear();
        assertEquals(0, cache.usedBytes());
        assertEquals(0, cache.size());
    }

    @Test
    void collisionsGrowthReplacementAndShiftStayWithinTheRetainedBudget() {
        var cache = new AntialiasSampleCache(8192);
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        for (int index = 0; index < 500; index++) {
            // These keys share their low bits and exercise open-addressing collisions.
            int key = index * 65537;
            cache.put(key, samples(8, index % 2 == 0 ? 2 : 32), coloring);
            assertTrue(cache.usedBytes() <= cache.maxBytes());
        }
        int size = cache.size();
        cache.shift(65536, 65536, 65536, new PixelShift(0, 0));
        assertEquals(size, cache.size());
        assertTrue(cache.usedBytes() <= cache.maxBytes());
        cache.clear();
        assertEquals(0, cache.usedBytes());
    }

    @Test
    void defaultBudgetRetainsTheMeasuredRetinaDeepZoomFrame() {
        // Entry mix of the deep-glitch-aa fixture at 3024x1964, the heaviest measured default frame.
        int fourPhase = 1_711_190, sixteenPhase = 6_421;
        var cache = new AntialiasSampleCache();
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var four = samples(6, 4);
        var sixteen = samples(6, 16);
        for (int pixel = 0; pixel < fourPhase + sixteenPhase; pixel++) {
            cache.put(pixel, pixel < fourPhase ? four : sixteen, coloring);
        }

        assertEquals(fourPhase + sixteenPhase, cache.size(), "The default budget must not evict this frame");
        assertTrue(cache.usedBytes() <= cache.maxBytes());
        cache.clear();
    }

    private static FractalSample[] samples(double smooth, int count) {
        FractalSample[] samples = new FractalSample[count];
        for (int index = 0; index < count; index++) {
            samples[index] = new FractalSample(10, true, smooth + index, 0.0);
        }
        return samples;
    }

    @Test
    void tileBatchCopiesScratchSamplesAndColorsWithoutPublishingPartialEntries() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var lookup = new SmoothColorLookup(coloring);
        var cache = new AntialiasSampleCache(4096);
        var reference = new AntialiasSampleCache(4096);
        var scratch = samples(12, 16);
        reference.put(3, scratch, coloring);
        int expected = reference.color(3, lookup);
        var batch = cache.newBatch(2, coloring);
        assertEquals(expected, batch.add(3, scratch, lookup, INFINITE, false));
        java.util.Arrays.fill(scratch, new FractalSample(100, false, 0, 0));
        assertEquals(0xFF000000, batch.add(4, scratch, lookup, INFINITE, false));
        assertEquals(0, cache.size(), "Workers must not publish a partially calculated tile");
        cache.merge(batch);
        assertEquals(expected, cache.color(3, lookup));
        assertEquals(0xFF000000, cache.color(4, lookup));
        assertThrows(IllegalArgumentException.class, () -> cache.merge(batch));
    }

    @Test
    void tileMergeKeepsMemoryBoundAndInvalidatesSnapshotAndColorScale() {
        var cache = new AntialiasSampleCache(budgetHolding(2, 4));
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0);
        cache.put(1, samples(4, 4), ice);
        var retained = cache.snapshotFor(ice);
        var batch = cache.newBatch(3, ice);
        var lookup = new SmoothColorLookup(ice);
        batch.add(2, samples(8, 4), lookup, INFINITE, false);
        batch.add(3, samples(12, 4), lookup, INFINITE, false);
        cache.merge(batch);
        assertTrue(cache.usedBytes() <= cache.maxBytes());
        assertEquals(2, cache.size());
        assertTrue(cache.contains(1));
        assertTrue(cache.contains(2));
        assertFalse(cache.contains(3));
        assertTrue(cache.dropped());
        assertEquals(1, retained.size());
        var mask = retained.pixelMask();
        mask.clear();
        assertTrue(retained.pixelMask().get(1), "Returned masks cannot mutate a retained snapshot");
        var changedScale = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.04, 0);
        var changed = cache.newBatch(1, changedScale);
        changed.add(7, samples(20, 2), new SmoothColorLookup(changedScale), INFINITE, false);
        cache.merge(changed);
        assertEquals(1, cache.size());
        assertFalse(cache.dropped(), "A new color scale starts an empty cache");
        assertEquals(0, cache.snapshotFor(ice).size());
        assertEquals(1, cache.snapshotFor(changedScale).size());
    }

    @Test
    void disabledOrTooSmallCacheRequestsTheUncachedColorPath() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        for (int budget : new int[]{0, 35, 800}) {
            var cache = new AntialiasSampleCache(budget);
            var batch = cache.newBatch(1, coloring);
            assertNull(batch.add(0, samples(10, 16), new SmoothColorLookup(coloring), INFINITE, false));
            assertTrue(batch.isEmpty());
            cache.merge(batch);
            assertEquals(0, cache.size());
        }
    }
}
