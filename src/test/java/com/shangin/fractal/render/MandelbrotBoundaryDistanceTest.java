package com.shangin.fractal.render;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.IterationSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MandelbrotBoundaryDistanceTest {
    private static final int WIDTH = 280;
    private static final int HEIGHT = 192;
    private static final RenderGrid GRID = RenderGrid.from(FractalPreset.MANDELBROT.defaultViewport(), WIDTH, HEIGHT);
    private static SamplePlane samples;
    private static MandelbrotBoundaryDistance distance;

    @BeforeAll static void renderMap() throws InterruptedException {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withIterations(new IterationSettings(1_000, 0));
        try (var renderer = new ScenePreviewRenderer()) {
            samples = renderer.renderSamples(scene, WIDTH, HEIGHT, () -> false);
        }
        distance = MandelbrotBoundaryDistance.of(samples, GRID, 8);
    }

    @Test void pointsFarFromTheBoundaryReachTheRange() {
        assertEquals(8, distance.pixels(0, 0), "The cardioid's center is over 20 pixels inside");
        assertEquals(8, distance.pixels(1, 0), "1 is about 0.75 outside the set");
    }

    @Test void theDistanceShrinksTowardTheBoundaryFromBothSides() {
        double pixel = GRID.realStep();
        // A point on the period-2 bulb's circle (center -1, radius 1/4) with no large bulb attached.
        double angle = 1;
        double radiusReal = 0.25 * Math.cos(angle);
        double radiusImaginary = 0.25 * Math.sin(angle);
        java.util.function.DoubleUnaryOperator at = offset -> distance.pixels(
                -1 + radiusReal * (1 + offset * pixel / 0.25), radiusImaginary * (1 + offset * pixel / 0.25));
        double outsideFar = at.applyAsDouble(5);
        double outsideNear = at.applyAsDouble(1);
        assertTrue(outsideNear < outsideFar, outsideNear + " >= " + outsideFar);
        assertTrue(outsideNear < 1.5, "One pixel outside the bulb: " + outsideNear);
        double insideFar = at.applyAsDouble(-5);
        double insideNear = at.applyAsDouble(-1);
        assertTrue(insideNear < insideFar, insideNear + " >= " + insideFar);
        // Inside, the distance comes from the grid, so it is accurate to about a pixel.
        assertTrue(insideNear < 2, "One pixel inside the bulb: " + insideNear);
        assertTrue(insideFar > 3.5, "Five pixels inside the bulb: " + insideFar);
    }

    @Test void exteriorFilamentsThinnerThanAPixelCountAsBoundary() {
        // The real axis left of the period-2 bulb is a filament of the set: thin, but on the boundary.
        double justOff = distance.pixels(-1.9, GRID.imaginaryStep() * 0.05);
        assertTrue(justOff < 0.5, "A twentieth of a pixel beside the filament: " + justOff);
    }

    @Test void rejectsAnEmptyRange() {
        assertThrows(IllegalArgumentException.class, () -> MandelbrotBoundaryDistance.of(samples, GRID, 0));
    }
}
