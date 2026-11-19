package com.shangin.fractal.export;

import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.PixelShift;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramSampleStoreTest {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 4;
    private static final int SAMPLES = 16;

    @Test
    void framesWhosePixelArraysExceedTheBudgetAreNotBound() {
        HistogramSampleStore store = new HistogramSampleStore(HistogramSampleStore.pixelBytes(WIDTH, HEIGHT) - 1);
        store.bind(WIDTH, HEIGHT, 100);

        assertNull(store.view());
        assertEquals(0, store.bytes());
    }

    @Test
    void theBudgetChargesWholeTilesAndRejectsSamplesThatDoNotFit() {
        long oneTile = HistogramSampleStore.blockBytes(1, SAMPLES);
        HistogramSampleStore store = new HistogramSampleStore(HistogramSampleStore.pixelBytes(WIDTH, HEIGHT) + oneTile);
        store.bind(WIDTH, HEIGHT, 100);
        HistogramSampleStore.View view = store.view();

        store.commit(view, batch(0));
        assertEquals(HistogramSampleStore.pixelBytes(WIDTH, HEIGHT) + oneTile, store.bytes());
        HistogramSampleStore.Batch rejected = batch(1);
        rejected.recordDistanceEdge(2, true);
        store.commit(view, rejected);

        assertTrue(view.hasSamples(0));
        assertFalse(view.hasSamples(1), "Samples beyond the budget are calculated again");
        assertEquals(Boolean.TRUE, view.distanceEdge(2), "Distance tests cost no budget beyond the pixel arrays");
        assertEquals(HistogramSampleStore.pixelBytes(WIDTH, HEIGHT) + oneTile, store.bytes());
    }

    @Test
    void aShiftReleasesTilesShiftedOutAndKeepsPartlyClippedTilesWhole() {
        HistogramSampleStore store = new HistogramSampleStore(Long.MAX_VALUE);
        store.bind(WIDTH, HEIGHT, 100);
        HistogramSampleStore.View view = store.view();
        store.commit(view, batch(0, 1));   // Columns 0 and 1 of row 0.
        store.commit(view, batch(WIDTH - 1));

        store.shift(WIDTH, WIDTH, HEIGHT, new PixelShift(-1, 0), 100);
        assertNotNull(store.prepareMove(), "A recorded shift waits for the next pass");
        store.bind(WIDTH, HEIGHT, 100);
        HistogramSampleStore.View moved = store.view();

        assertTrue(moved.hasSamples(0), "Source column 1 moves to column 0");
        assertTrue(moved.hasSamples(WIDTH - 2), "Source column 7 moves to column 6");
        assertFalse(moved.hasSamples(1));
        // The first tile lost column 0 but keeps its arrays; nothing was shifted out entirely.
        assertEquals(HistogramSampleStore.pixelBytes(WIDTH, HEIGHT)
                + HistogramSampleStore.blockBytes(2, 2 * SAMPLES)
                + HistogramSampleStore.blockBytes(1, SAMPLES), store.bytes());

        store.shift(WIDTH, WIDTH, HEIGHT, new PixelShift(-2, 0), 100);
        store.bind(WIDTH, HEIGHT, 100);
        assertEquals(HistogramSampleStore.pixelBytes(WIDTH, HEIGHT) + HistogramSampleStore.blockBytes(1, SAMPLES),
                store.bytes(), "A tile with no entry left in the frame is released");
    }

    @Test
    void growingTheFrameBeyondTheBudgetClearsTheValuesWithoutMovingThem() {
        HistogramSampleStore store = new HistogramSampleStore(
                HistogramSampleStore.pixelBytes(WIDTH, HEIGHT) + HistogramSampleStore.blockBytes(1, SAMPLES));
        store.bind(WIDTH, HEIGHT, 100);
        store.commit(store.view(), batch(0));
        assertTrue(store.view().hasSamples(0), "The fixture must start with stored samples");

        store.shift(WIDTH, 4 * WIDTH, 4 * HEIGHT, new PixelShift(12, 6), 100);

        assertNull(store.prepareMove(), "No target arrays may be allocated for a frame beyond the budget");
        assertNull(store.view());
        assertEquals(0, store.bytes());
        store.bind(4 * WIDTH, 4 * HEIGHT, 100);
        assertNull(store.view(), "The larger frame's pass samples as usual");
    }

    @Test
    void aShiftToAnotherIterationLimitClearsTheValues() {
        HistogramSampleStore store = new HistogramSampleStore(Long.MAX_VALUE);
        store.bind(WIDTH, HEIGHT, 100);
        store.commit(store.view(), batch(0));

        store.shift(WIDTH, WIDTH, HEIGHT, new PixelShift(0, 0), 200);

        assertNull(store.view());
        assertNull(store.prepareMove());
    }

    @Test
    void aCommitToValuesNoLongerBoundIsIgnored() {
        HistogramSampleStore store = new HistogramSampleStore(Long.MAX_VALUE);
        store.bind(WIDTH, HEIGHT, 100);
        HistogramSampleStore.View stale = store.view();
        store.clear();
        store.bind(WIDTH, HEIGHT, 100);

        store.commit(stale, batch(0));

        assertFalse(store.view().hasSamples(0));
    }

    private static HistogramSampleStore.Batch batch(int... pixels) {
        HistogramSampleStore.Batch batch = new HistogramSampleStore.Batch();
        FractalSample[] samples = new FractalSample[SAMPLES];
        Arrays.fill(samples, new FractalSample(10, true, 4.0, 0.0));
        for (int pixel : pixels) batch.addSamples(pixel, samples);
        return batch;
    }
}
