package com.shangin.fractal.render;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.formula.FractalSample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntialiasSampleCacheTest {

    @Test
    void evictsLeastRecentlyUsedPixelsWithinMemoryLimit() {
        AntialiasSampleCache cache = new AntialiasSampleCache(1550);
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        cache.put(1, samples(1.0, 2), coloring);
        cache.put(2, samples(2.0, 2), coloring);
        assertTrue(cache.contains(1)); // make pixel 1 newest
        cache.put(3, samples(3.0, 2), coloring);

        assertTrue(cache.usedBytes() <= cache.maxBytes());
        assertTrue(cache.contains(1));
        assertFalse(cache.contains(2));
        assertTrue(cache.contains(3));
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
            // These integer hashes share their low bits and exercise treeified map buckets.
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
        assertEquals(expected, batch.add(3, scratch, lookup));
        java.util.Arrays.fill(scratch, new FractalSample(100, false, 0, 0));
        assertEquals(0xFF000000, batch.add(4, scratch, lookup));
        assertEquals(0, cache.size(), "Workers must not publish a partially calculated tile");
        cache.merge(batch);
        assertEquals(expected, cache.color(3, lookup));
        assertEquals(0xFF000000, cache.color(4, lookup));
        assertThrows(IllegalArgumentException.class, () -> cache.merge(batch));
    }

    @Test
    void tileMergeKeepsMemoryBoundAndInvalidatesSnapshotAndColorScale() {
        var cache = new AntialiasSampleCache(1550);
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0);
        cache.put(1, samples(4, 2), ice);
        var retained = cache.snapshotFor(ice);
        var batch = cache.newBatch(3, ice);
        var lookup = new SmoothColorLookup(ice);
        batch.add(2, samples(8, 2), lookup);
        batch.add(3, samples(12, 2), lookup);
        cache.merge(batch);
        assertTrue(cache.usedBytes() <= cache.maxBytes());
        assertEquals(2, cache.size());
        assertFalse(cache.contains(1));
        assertTrue(cache.contains(2));
        assertTrue(cache.contains(3));
        assertEquals(1, retained.size());
        var mask = retained.pixelMask();
        mask.clear();
        assertTrue(retained.pixelMask().get(1), "Returned masks cannot mutate a retained snapshot");
        var changedScale = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.04, 0);
        var changed = cache.newBatch(1, changedScale);
        changed.add(7, samples(20, 2), new SmoothColorLookup(changedScale));
        cache.merge(changed);
        assertEquals(1, cache.size());
        assertEquals(0, cache.snapshotFor(ice).size());
        assertEquals(1, cache.snapshotFor(changedScale).size());
    }

    @Test
    void disabledOrTooSmallCacheRequestsTheUncachedColorPath() {
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        for (int budget : new int[]{0, 35, 1000}) {
            var cache = new AntialiasSampleCache(budget);
            var batch = cache.newBatch(1, coloring);
            assertNull(batch.add(0, samples(10, 16), new SmoothColorLookup(coloring)));
            assertTrue(batch.isEmpty());
            cache.merge(batch);
            assertEquals(0, cache.size());
        }
    }
}
