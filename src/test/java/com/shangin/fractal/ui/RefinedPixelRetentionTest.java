package com.shangin.fractal.ui;

import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.ValidityMask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RefinedPixelRetentionTest {

    @Test
    void refinedPresentationShouldDiscardEveryUnconfirmedBasePixel() {
        int[] pixels = {
                0xFF123456, 0xFF123456, 0xFF123456,
                0xFF123456, 0xFF123456, 0xFF123456
        };
        ValidityMask refined = new ValidityMask(3, 2);
        refined.markReady(new RenderRegion(1, 0, 1, 2));

        RefinedPixelRetention.clearUnconfirmed(pixels, 3, 2, refined);

        assertArrayEquals(new int[]{
                0, 0xFF123456, 0,
                0, 0xFF123456, 0
        }, pixels);
    }
}
