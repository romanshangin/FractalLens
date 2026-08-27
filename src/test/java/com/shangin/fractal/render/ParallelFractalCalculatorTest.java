package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.ParallelFractalCalculator.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParallelFractalCalculatorTest {

    private FractalCalculator calculator;
    private Viewport viewport;
    private ParallelFractalCalculator parallelCalculator;

    private static double distanceSquared(Tile tile, double x, double y) {
        double tileX = (tile.xFrom() + tile.xTo()) / 2.0;

        double tileY = (tile.yFrom() + tile.yTo()) / 2.0;

        double dx = tileX - x;

        double dy = tileY - y;

        return dx * dx + dy * dy;
    }

    @BeforeEach
    void setUp() {
        FractalPreset preset = FractalPreset.MANDELBROT;
        calculator = new FractalCalculator(preset.createFormula());
        viewport = preset.defaultViewport();
        parallelCalculator = new ParallelFractalCalculator();
    }

    @AfterEach
    void tearDown() {
        parallelCalculator.close();
    }

    @Test
    void tilesShouldBeOrderedByDistanceFromPriorityPoint() {

        RenderPriority priority = new RenderPriority(0.75, 0.25);

        RenderRequest request = new RenderRequest(calculator, viewport, 256, 256, 100, priority);

        List<Tile> tiles = parallelCalculator.createOrderedTiles(request);

        double priorityX = request.width() * priority.x();

        double priorityY = request.height() * priority.y();

        double previousDistance = Double.NEGATIVE_INFINITY;

        assertFalse(tiles.isEmpty());

        for (Tile tile : tiles) {

            double distance = distanceSquared(tile, priorityX, priorityY);

            assertTrue(distance >= previousDistance, "Tiles are not ordered by distance");

            previousDistance = distance;
        }
    }

    @Test
    void changingPriorityShouldChangeTileOrdering() {

        RenderRequest topLeftRequest = new RenderRequest(
                calculator,
                viewport,
                256,
                256,
                100,
                new RenderPriority(0.0, 0.0));

        RenderRequest bottomRightRequest = new RenderRequest(
                calculator,
                viewport,
                256,
                256,
                100,
                new RenderPriority(1.0, 1.0));

        Tile topLeftFirst = parallelCalculator.createOrderedTiles(topLeftRequest).getFirst();

        Tile bottomRightFirst = parallelCalculator.createOrderedTiles(bottomRightRequest).getFirst();

        assertNotEquals(topLeftFirst, bottomRightFirst);

        assertTrue(topLeftFirst.xFrom() < bottomRightFirst.xFrom());

        assertTrue(topLeftFirst.yFrom() < bottomRightFirst.yFrom());
    }

}
