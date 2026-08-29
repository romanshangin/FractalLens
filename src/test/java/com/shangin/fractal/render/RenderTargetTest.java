package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RenderTargetTest {

    @Test
    void usesCenteredPriorityByDefault() {
        RenderTarget target = new RenderTarget(1920, 1080);

        assertEquals(RenderPriority.center(), target.priority());
    }

    @Test
    void rejectsDimensionsTooSmallForCoordinateGrid() {
        assertThrows(IllegalArgumentException.class, () -> new RenderTarget(1, 1080));
        assertThrows(IllegalArgumentException.class, () -> new RenderTarget(1920, 1));
    }
}
