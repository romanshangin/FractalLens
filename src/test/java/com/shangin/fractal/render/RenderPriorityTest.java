package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class RenderPriorityTest {

    @Test
    void centerShouldReturnViewportCenter() {
        RenderPriority priority = RenderPriority.center();

        assertEquals(0.5, priority.x());

        assertEquals(0.5, priority.y());
    }

    @Test
    void shouldAllowBoundaryCoordinates() {
        assertDoesNotThrow(() -> new RenderPriority(0.0, 0.0));

        assertDoesNotThrow(() -> new RenderPriority(1.0, 1.0));
    }

    @Test
    void shouldRejectCoordinatesOutsideViewport() {
        assertThrows(IllegalArgumentException.class, () -> new RenderPriority(-0.01, 0.5));

        assertThrows(IllegalArgumentException.class, () -> new RenderPriority(1.01, 0.5));

        assertThrows(IllegalArgumentException.class, () -> new RenderPriority(0.5, -0.01));

        assertThrows(IllegalArgumentException.class, () -> new RenderPriority(0.5, 1.01));
    }

    @Test
    void shouldRejectNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new RenderPriority(Double.NaN, 0.5));

        assertThrows(IllegalArgumentException.class, () -> new RenderPriority(0.5, Double.POSITIVE_INFINITY));
    }

}