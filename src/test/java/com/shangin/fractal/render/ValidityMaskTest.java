package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
