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
        AntialiasSampleCache cache = new AntialiasSampleCache(16);
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
        AntialiasSampleCache cache = new AntialiasSampleCache(1024);
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0.0);
        var fire = new SmoothPaletteColoring(PalettePreset.FIRE.palette(), 0.02, 0.5);
        cache.put(1, samples(20.0, 4), ice);

        assertNotEquals(cache.color(1, ice, 100), cache.color(1, fire, 100));
        cache.shift(4, 3, new PixelShift(1, 1));
        assertFalse(cache.contains(1));
        assertTrue(cache.contains(6));
    }

    @Test
    void preservesSmoothPhaseAtVeryHighIterationCounts() {
        AntialiasSampleCache cache = new AntialiasSampleCache(1024);
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.0075, 0.37);
        FractalSample sample = new FractalSample(20_000_001, true, 4.0, 0.0);

        cache.put(0, new FractalSample[]{sample}, coloring);

        assertEquals(
                coloring.color(
                        sample.iterations(), sample.smoothIterations(), true, 20_000_100),
                cache.color(0, coloring, 20_000_100)
        );
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
        var cache = new AntialiasSampleCache(1024);
        var reference = new AntialiasSampleCache(1024);
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
        var cache = new AntialiasSampleCache(16);
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.02, 0);
        cache.put(1, samples(4, 2), ice);
        var retained = cache.snapshotFor(ice);
        var batch = cache.newBatch(3, ice);
        var lookup = new SmoothColorLookup(ice);
        batch.add(2, samples(8, 2), lookup);
        batch.add(3, samples(12, 2), lookup);
        cache.merge(batch);
        assertEquals(16, cache.usedBytes());
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
        for (int budget : new int[]{0, 35}) {
            var cache = new AntialiasSampleCache(budget);
            var batch = cache.newBatch(1, coloring);
            assertNull(batch.add(0, samples(10, 16), new SmoothColorLookup(coloring)));
            assertTrue(batch.isEmpty());
            cache.merge(batch);
            assertEquals(0, cache.size());
        }
    }
}
