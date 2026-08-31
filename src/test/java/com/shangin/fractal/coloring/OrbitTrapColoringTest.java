package com.shangin.fractal.coloring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrbitTrapColoringTest {

    @Test
    void unavailableTrapDistanceUsesInteriorColor() {
        OrbitTrapColoring coloring = new OrbitTrapColoring(position -> 0xFFFFFFFF, 0.0);

        assertEquals(0xFF000000, coloring.color(5, 5.0, true, 100, Double.NaN));
    }

    @Test
    void distanceSelectsPalettePositionForInteriorAndExteriorOrbits() {
        Palette palette = position -> 0xFF000000 | (int) Math.round(position * 255.0);
        OrbitTrapColoring coloring = new OrbitTrapColoring(palette, 0.0);

        int near = coloring.color(100, 100.0, false, 100, 0.01);
        int far = coloring.color(5, 5.0, true, 100, 0.2);

        assertNotEquals(near, far);
    }
}
