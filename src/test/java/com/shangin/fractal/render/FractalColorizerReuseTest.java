package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.formula.FractalSample;
import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FractalColorizerReuseTest {

    @Test
    void readyBaseColoringPreservesShiftedRefinedPixels() {
        FractalData data = new FractalData(4, 1, 100);
        for (int index = 0; index < data.size(); index++) {
            data.set(index, new FractalSample(index + 1, true, 3.0, 0.0));
        }

        ValidityMask ready = new ValidityMask(4, 1);
        ready.markReady(new RenderRegion(0, 0, 4, 1));
        ValidityMask preservedRefinement = new ValidityMask(4, 1);
        preservedRefinement.markReady(new RenderRegion(1, 0, 2, 1));

        int[] pixels = {90, 91, 92, 93};
        ColoringStrategy coloring = (iterations, smooth, escaped, max) -> iterations;

        new FractalColorizer().colorReadyPixels(
                data,
                IntBuffer.wrap(pixels),
                coloring,
                ready,
                preservedRefinement
        );

        assertArrayEquals(new int[]{1, 91, 92, 4}, pixels);

        pixels[0] = 90;
        pixels[3] = 93;
        new FractalColorizer().colorRegion(data, IntBuffer.wrap(pixels), coloring,
                new RenderRegion(0, 0, 4, 1), preservedRefinement);
        assertArrayEquals(new int[]{1, 91, 92, 4}, pixels,
                "A progress tile straddling a reused edge must not overwrite AA colors");
    }

    @Test
    void regionColoringUsesCoordinatesLocalToCompactPreservationSnapshot() {
        FractalData data = new FractalData(4, 3, 100);
        for (int index = 0; index < data.size(); index++) {
            data.set(index, new FractalSample(index + 1, true, 3.0, 0.0));
        }
        ValidityMask preserved = new ValidityMask(4, 3);
        preserved.markReady(new RenderRegion(2, 1, 1, 1));
        int[] pixels = new int[12];
        java.util.Arrays.fill(pixels, 99);

        new FractalColorizer().colorRegion(data, IntBuffer.wrap(pixels),
                (iterations, smooth, escaped, max) -> iterations,
                new RenderRegion(1, 1, 2, 2), preserved);

        assertArrayEquals(new int[]{99, 99, 99, 99, 99, 6, 99, 99, 99, 10, 11, 99}, pixels);
    }
}
