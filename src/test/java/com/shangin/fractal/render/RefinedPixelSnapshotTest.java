package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinedPixelSnapshotTest {

    @Test
    void shouldDefensivelyCopyColorsAndValidity() {
        int[] colors = {1, 2, 3, 4};
        ValidityMask validity = new ValidityMask(2, 2);
        validity.markReady(new RenderRegion(0, 0, 1, 1));

        RefinedPixelSnapshot snapshot = new RefinedPixelSnapshot(
                2,
                2,
                colors,
                validity
        );
        colors[0] = 99;
        validity.clear();
        snapshot.colors()[1] = 88;
        snapshot.validity().clear();

        assertEquals(1, snapshot.color(0, 0));
        assertEquals(2, snapshot.color(1, 0));
        assertTrue(snapshot.isRefined(0, 0));
        assertFalse(snapshot.isRefined(1, 0));
        assertTrue(snapshot.isRegionRefined(new RenderRegion(0, 0, 1, 1)));
        assertFalse(snapshot.isRegionRefined(new RenderRegion(0, 0, 2, 1)));
    }

    @Test
    void emptySnapshotShouldContainNoReusablePixels() {
        RefinedPixelSnapshot snapshot = RefinedPixelSnapshot.empty(3, 2);

        assertEquals(0, snapshot.validity().readyPixelCount());
        assertFalse(snapshot.isRefined(2, 1));
        assertEquals(0, snapshot.color(2, 1));
    }

    @Test
    void shouldRejectMismatchedDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RefinedPixelSnapshot(
                        2,
                        2,
                        new int[3],
                        new ValidityMask(2, 2)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new RefinedPixelSnapshot(
                        2,
                        2,
                        new int[4],
                        new ValidityMask(3, 2)
                )
        );
    }
}
