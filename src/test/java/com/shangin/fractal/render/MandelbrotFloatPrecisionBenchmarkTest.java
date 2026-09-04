package com.shangin.fractal.render;

import com.shangin.fractal.formula.MandelbrotFormula;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static com.shangin.fractal.render.MandelbrotFloatPrecisionBenchmark.*;
import static org.junit.jupiter.api.Assertions.*;

class MandelbrotFloatPrecisionBenchmarkTest {
    @Test
    void preservesCpuEscapeRadiusAndLastIterationConvention() {
        for (boolean fused : new boolean[]{false, true}) {
            assertFalse(calculate(2.0f, 0.0f, 2, fused).escaped());
            var escaped = calculate(2.0f, 0.0f, 3, fused);
            assertTrue(escaped.escaped());
            assertEquals(2, escaped.iterations());
            assertEquals(6.0, escaped.zr());
            assertFalse(calculate(0.0f, 0.0f, 300, fused).escaped());
            assertFalse(calculate(-1.0f, 0.0f, 300, fused).escaped());
        }
    }

    @Test
    void smallCoordinateErrorDoesNotImplySmallSmoothIterationError() {
        // Fixed counterexample, independent of the viewport's raster convention.
        double stepX = 3.6 / 383, stepY = 2.4 / 255;
        double re = -2.55 + 254 * stepX, im = 1.2 - 17 * stepY;
        double coordinateError = Math.max(Math.abs((float) re - re) / stepX,
                Math.abs((float) im - im) / stepY);
        var reference = new MandelbrotFormula().calculate(re, im, 300);
        var candidate = calculate((float) re, (float) im, 300, false);
        assertTrue(coordinateError < MAX_COORDINATE_ERROR_PIXELS);
        assertTrue(reference.escaped());
        assertTrue(candidate.escaped());
        assertTrue(Math.abs(reference.smoothIterations() - candidate.smoothIterations()) > MAX_SMOOTH_ERROR);
    }

    @Test
    void coordinateRoundingAloneCanChangeEscapedClassificationInAnOverview() {
        var grid = RenderGrid.from(new Viewport(-0.75, 0.0, 2.4), 384, 256);
        var formula = new MandelbrotFormula();
        // A full small frame is inexpensive and avoids relying on an arbitrary shader implementation.
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 384; x++) {
                double re = grid.realAt(x), im = grid.imaginaryAt(y);
                if (formula.calculate(re, im, 300).escaped()
                        != formula.calculate((float) re, (float) im, 300).escaped()) return;
            }
        }
        fail("The numeric screen must expose the known input-rounding counterexample");
    }

    @Test
    void gridModelRetainsConjugateRowsAcrossPanOffsets() {
        var grid = RenderGrid.from(new Viewport(-0.75, 0.0, 2.4), 384, 256);
        for (int y = 0; y < 128; y++) {
            assertEquals(imaginaryAt(grid, y, Mode.FLOAT_GRID),
                    -imaginaryAt(grid, 255 - y, Mode.FLOAT_GRID));
        }
        var shifted = grid.shifted(new PixelShift(3, -7));
        assertEquals(realAt(grid, 10, Mode.FLOAT_GRID), realAt(shifted, 13, Mode.FLOAT_GRID));
        assertEquals(imaginaryAt(grid, 10, Mode.FLOAT_GRID), imaginaryAt(shifted, 3, Mode.FLOAT_GRID));
    }
}
