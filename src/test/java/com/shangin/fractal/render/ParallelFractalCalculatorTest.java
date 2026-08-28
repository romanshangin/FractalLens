package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalFormula;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.ParallelFractalCalculator.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void customTileSizeShouldControlGridPartitioning() {
        try (ParallelFractalCalculator customCalculator =
                     new ParallelFractalCalculator(1, 64)) {

            RenderRequest request = new RenderRequest(
                    calculator,
                    viewport,
                    129,
                    65,
                    100
            );

            assertEquals(
                    6,
                    customCalculator.createOrderedTiles(request).size()
            );
        }
    }

    @Test
    void tileSizeShouldBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParallelFractalCalculator(1, 0)
        );
    }

    @Test
    void timingStatisticsShouldDescribeCalculatedTiles() throws InterruptedException {
        RenderRequest request = new RenderRequest(
                calculator,
                viewport,
                65,
                33,
                100
        );

        RenderFrame frame = RenderFrame.create(request);
        AtomicReference<TileTimingStats> capturedStats = new AtomicReference<>();

        RenderFrame result = parallelCalculator.calculate(
                frame,
                () -> false,
                ignored -> {},
                capturedStats::set
        );

        TileTimingStats stats = capturedStats.get();

        assertTrue(result.isComplete());
        assertNotNull(stats);
        assertEquals(3, stats.tileCount());
        assertTrue(stats.minMs() >= 0.0);
        assertTrue(stats.medianMs() >= stats.minMs());
        assertTrue(stats.maxMs() >= stats.medianMs());
    }

    @Test
    void completedFrameShouldReportEmptyTimingStatistics() throws InterruptedException {
        RenderFrame frame = RenderFrame.create(
                new RenderRequest(
                        calculator,
                        viewport,
                        32,
                        32,
                        100
                )
        );

        parallelCalculator.calculate(
                frame,
                () -> false,
                ignored -> {}
        );

        AtomicReference<TileTimingStats> capturedStats = new AtomicReference<>();

        parallelCalculator.calculate(
                frame,
                () -> false,
                ignored -> fail("Completed frame must not produce progress"),
                capturedStats::set
        );

        assertEquals(
                new TileTimingStats(0, 0.0, 0.0, 0.0),
                capturedStats.get()
        );
    }

    @Test
    void cancelledCalculationShouldNotReportTimingStatistics() throws InterruptedException {
        RenderFrame frame = RenderFrame.create(
                new RenderRequest(
                        calculator,
                        viewport,
                        64,
                        64,
                        100
                )
        );

        AtomicInteger callbackCount = new AtomicInteger();

        RenderFrame result = parallelCalculator.calculate(
                frame,
                () -> true,
                ignored -> fail("Cancelled render must not produce progress"),
                ignored -> callbackCount.incrementAndGet()
        );

        assertSame(frame, result);
        assertFalse(result.isComplete());
        assertEquals(0, callbackCount.get());
    }

    @Test
    void conjugateSymmetryShouldCalculateOnlyOneHalfOfEmptyFrame() throws InterruptedException {
        AtomicInteger calculationCount = new AtomicInteger();
        FractalFormula symmetricFormula = new FractalFormula() {
            @Override
            public boolean hasConjugateSymmetry() {
                return true;
            }

            @Override
            public FractalSample calculate(double real, double imaginary, int maxIterations) {
                calculationCount.incrementAndGet();
                return new FractalSample(1, true, real, Math.abs(imaginary));
            }
        };

        RenderRequest request = new RenderRequest(
                new FractalCalculator(symmetricFormula),
                new Viewport(-0.75, 0.0, 2.4),
                17,
                9,
                100
        );
        RenderFrame frame = RenderFrame.create(request);

        parallelCalculator.calculate(frame, () -> false, ignored -> {});

        assertTrue(frame.isComplete());
        assertEquals(17 * 5, calculationCount.get());

        for (int y = 0; y < request.height(); y++) {
            int mirrorY = request.height() - 1 - y;

            for (int x = 0; x < request.width(); x++) {
                int index = y * request.width() + x;
                int mirrorIndex = mirrorY * request.width() + x;

                assertEquals(frame.fractalData().iterations(index), frame.fractalData().iterations(mirrorIndex));
                assertEquals(frame.fractalData().smoothIterations(index), frame.fractalData().smoothIterations(mirrorIndex));
                assertEquals(frame.fractalData().escaped(index), frame.fractalData().escaped(mirrorIndex));
            }
        }
    }

    @Test
    void asymmetricGridShouldCalculateEveryPixel() throws InterruptedException {
        AtomicInteger calculationCount = new AtomicInteger();
        FractalFormula symmetricFormula = new FractalFormula() {
            @Override
            public boolean hasConjugateSymmetry() {
                return true;
            }

            @Override
            public FractalSample calculate(double real, double imaginary, int maxIterations) {
                calculationCount.incrementAndGet();
                return new FractalSample(1, true, real, imaginary);
            }
        };

        RenderRequest request = new RenderRequest(
                new FractalCalculator(symmetricFormula),
                new Viewport(-0.75, 0.1, 2.4),
                17,
                9,
                100
        );
        RenderFrame frame = RenderFrame.create(request);

        parallelCalculator.calculate(frame, () -> false, ignored -> {});

        assertTrue(frame.isComplete());
        assertEquals(17 * 9, calculationCount.get());
    }

    @Test
    void mandelbrotSymmetryShouldMatchFullCalculation() throws InterruptedException {
        int width = 401;
        int height = 301;
        int maxIterations = 500;
        RenderGrid grid = RenderGrid.from(viewport, width, height);
        FractalData reference = calculator.calculate(width, height, grid, maxIterations);
        RenderRequest request = new RenderRequest(
                calculator,
                viewport,
                width,
                height,
                maxIterations
        );
        RenderFrame frame = RenderFrame.create(request, grid);

        parallelCalculator.calculate(frame, () -> false, ignored -> {});

        assertTrue(frame.isComplete());

        for (int index = 0; index < reference.size(); index++) {
            assertEquals(reference.iterations(index), frame.fractalData().iterations(index));
            assertEquals(reference.smoothIterations(index), frame.fractalData().smoothIterations(index));
            assertEquals(reference.escaped(index), frame.fractalData().escaped(index));
        }
    }

}
