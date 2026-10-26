package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.JuliaFormula;
import com.shangin.fractal.formula.MandelbrotFormula;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exactness and tile-contract checks for the experimental interleaved kernel. */
class InterleavedOrbitKernelTest {

    private static final int WIDTH = 192;
    private static final int HEIGHT = 108;
    private static final double JULIA_REAL = -0.8;
    private static final double JULIA_IMAGINARY = 0.156;

    @ParameterizedTest(name = "{0} lanes, scalar prefix {1}")
    @CsvSource({"2, 0", "4, 0", "2, 8", "4, 8", "4, 32"})
    void matchesScalarKernelOnFormulaBenchmarkScenes(int lanes, int prefix) {
        for (FormulaCalculationBenchmark.Scenario scenario : FormulaCalculationBenchmark.scenarios()) {
            int maxIterations = FormulaCalculationBenchmark.maxIterations(scenario, 300);
            RenderGrid grid = RenderGrid.from(scenario.viewport(), WIDTH, HEIGHT);
            InterleavedOrbitKernel kernel = scenario.preset() == FractalPreset.MANDELBROT
                    ? InterleavedOrbitKernel.mandelbrot(lanes, prefix)
                    : InterleavedOrbitKernel.julia(lanes, prefix, JULIA_REAL, JULIA_IMAGINARY);

            FractalData expected = new FractalCalculator(scenario.formula())
                    .calculate(WIDTH, HEIGHT, grid, maxIterations);

            InterleavedOrbitBenchmark.requireIdentical(expected,
                    kernel.calculate(WIDTH, HEIGHT, grid, maxIterations),
                    scenario.name() + " " + scenario.zoom() + "x full frame");
            InterleavedOrbitBenchmark.requireIdentical(expected,
                    tiled(kernel, grid, maxIterations, 32),
                    scenario.name() + " " + scenario.zoom() + "x 32-pixel tiles");
            InterleavedOrbitBenchmark.requireIdentical(expected,
                    tiled(kernel, grid, maxIterations, 5),
                    scenario.name() + " " + scenario.zoom() + "x 5-pixel tiles");
        }
    }

    @ParameterizedTest(name = "{0} lanes, scalar prefix {1}")
    @CsvSource({"2, 0", "4, 0", "2, 8", "4, 8", "4, 32"})
    void matchesScalarKernelAtSmallIterationCaps(int lanes, int prefix) {
        RenderGrid grid = RenderGrid.from(new Viewport(-0.75, 0.0, 3.0), 37, 23);
        for (int maxIterations : new int[] {0, 1, 2, 3, 17}) {
            InterleavedOrbitBenchmark.requireIdentical(
                    new FractalCalculator(new MandelbrotFormula()).calculate(37, 23, grid, maxIterations),
                    InterleavedOrbitKernel.mandelbrot(lanes, prefix).calculate(37, 23, grid, maxIterations),
                    "Mandelbrot cap " + maxIterations);
            InterleavedOrbitBenchmark.requireIdentical(
                    new FractalCalculator(new JuliaFormula(0.285, 0.01)).calculate(37, 23, grid, maxIterations),
                    InterleavedOrbitKernel.julia(lanes, prefix, 0.285, 0.01).calculate(37, 23, grid, maxIterations),
                    "Julia cap " + maxIterations);
        }
    }

    @ParameterizedTest(name = "{0} lanes, scalar prefix {1}")
    @CsvSource({"2, 0", "4, 0", "2, 8", "4, 8", "4, 32"})
    void reportsRowsInScalarOrderAndSkipsReadyPixels(int lanes, int prefix) {
        RenderGrid grid = RenderGrid.from(new Viewport(-0.74, 0.13, 0.02), 40, 30);
        PixelReady ready = (x, y) -> (x + 3 * y) % 7 == 0 || y == 4;
        FractalData expected = new FractalData(40, 30, 500);
        FractalData actual = new FractalData(40, 30, 500);
        List<RenderRegion> expectedRows = new ArrayList<>();
        List<RenderRegion> actualRows = new ArrayList<>();

        assertTrue(new FractalCalculator(new MandelbrotFormula()).calculateTile(
                expected, grid, 3, 6, 2, 9, 500, () -> false, ready, expectedRows::add));
        assertTrue(InterleavedOrbitKernel.mandelbrot(lanes, prefix).calculateTile(
                actual, grid, 3, 6, 2, 9, 500, () -> false, ready, actualRows::add));

        assertEquals(expectedRows, actualRows);
        InterleavedOrbitBenchmark.requireIdentical(expected, actual, "ready-pixel tile");
    }

    @ParameterizedTest(name = "{0} lanes, scalar prefix {1}")
    @CsvSource({"2, 0", "4, 0", "2, 8", "4, 8", "4, 32"})
    void stopsOnCancellationAndReportsOnlyCompletedRows(int lanes, int prefix) {
        RenderGrid grid = RenderGrid.from(new Viewport(-0.74, 0.13, 0.02), 32, 32);
        FractalData expected = new FractalCalculator(new MandelbrotFormula())
                .calculate(32, 32, grid, 800);
        FractalData actual = new FractalData(32, 32, 800);
        List<RenderRegion> rows = new ArrayList<>();
        AtomicInteger polls = new AtomicInteger();

        boolean completed = InterleavedOrbitKernel.mandelbrot(lanes, prefix).calculateTile(
                actual, grid, 0, 32, 0, 32, 800,
                () -> polls.incrementAndGet() > 20, (x, y) -> false, rows::add);

        assertFalse(completed);
        assertTrue(rows.size() < 32);
        for (int index = 0; index < rows.size(); index++) {
            RenderRegion row = rows.get(index);
            assertEquals(new RenderRegion(0, index, 32, 1), row);
            for (int x = 0; x < 32; x++) {
                int pixel = row.y() * 32 + x;
                assertEquals(expected.iterations(pixel), actual.iterations(pixel));
                assertEquals(expected.smoothIterations(pixel), actual.smoothIterations(pixel));
            }
        }
    }

    @ParameterizedTest(name = "{0} lanes")
    @ValueSource(ints = {2, 4})
    void matchesScalarKernelAroundScalarPrefixBoundary(int lanes) {
        List<RenderGrid> grids = List.of(
                RenderGrid.from(new Viewport(-0.75, 0.0, 3.0), 96, 54),
                RenderGrid.from(new Viewport(-0.745, 0.11, 0.1), 96, 54));
        for (int prefix : new int[] {1, 2, 3, 4, 8, 16, 32}) {
            boolean mandelbrotEscapesAtPrefix = false;
            boolean juliaEscapesAtPrefix = false;
            for (RenderGrid grid : grids) {
                for (int maxIterations : new int[] {prefix - 1, prefix, prefix + 1, 300}) {
                    FractalData mandelbrot = new FractalCalculator(new MandelbrotFormula())
                            .calculate(96, 54, grid, maxIterations);
                    FractalData julia = new FractalCalculator(new JuliaFormula(JULIA_REAL, JULIA_IMAGINARY))
                            .calculate(96, 54, grid, maxIterations);
                    if (maxIterations == 300) {
                        mandelbrotEscapesAtPrefix |= hasEscapeAt(mandelbrot, prefix);
                        juliaEscapesAtPrefix |= hasEscapeAt(julia, prefix);
                    }
                    String label = " prefix " + prefix + " cap " + maxIterations;
                    InterleavedOrbitBenchmark.requireIdentical(mandelbrot,
                            InterleavedOrbitKernel.mandelbrot(lanes, prefix)
                                    .calculate(96, 54, grid, maxIterations),
                            "Mandelbrot" + label);
                    InterleavedOrbitBenchmark.requireIdentical(julia,
                            InterleavedOrbitKernel.julia(lanes, prefix, JULIA_REAL, JULIA_IMAGINARY)
                                    .calculate(96, 54, grid, maxIterations),
                            "Julia" + label);
                }
            }
            assertTrue(mandelbrotEscapesAtPrefix && juliaEscapesAtPrefix,
                    "Fixtures must contain orbits escaping exactly at step " + prefix);
        }
    }

    private static boolean hasEscapeAt(FractalData data, int iteration) {
        for (int index = 0; index < data.size(); index++) {
            if (data.escaped(index) && data.iterations(index) == iteration) {
                return true;
            }
        }
        return false;
    }

    private static FractalData tiled(
            InterleavedOrbitKernel kernel, RenderGrid grid, int maxIterations, int tileSize
    ) {
        FractalData data = new FractalData(WIDTH, HEIGHT, maxIterations);
        for (int y = 0; y < HEIGHT; y += tileSize) {
            for (int x = 0; x < WIDTH; x += tileSize) {
                assertTrue(kernel.calculateTile(data, grid,
                        x, Math.min(x + tileSize, WIDTH), y, Math.min(y + tileSize, HEIGHT),
                        maxIterations, () -> false, (px, py) -> false, ignored -> {}));
            }
        }
        return data;
    }
}
