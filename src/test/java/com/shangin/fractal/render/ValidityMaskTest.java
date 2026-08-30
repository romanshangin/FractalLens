package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidityMaskTest {

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
    void shiftedCopyShouldRejectDifferentDimensions() {
        ValidityMask target = new ValidityMask(4, 3);

        assertThrows(
                IllegalArgumentException.class,
                () -> target.copyShiftedFrom(
                        new ValidityMask(5, 3),
                        new PixelShift(0, 0)
                )
        );
    }
}
