package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RenderGridTest {

    @Test
    void centeredGridShouldHaveExactConjugateRows() {
        RenderGrid grid = RenderGrid.from(
                new Viewport(-0.75, 0.0, 2.4),
                17,
                9
        );

        assertTrue(grid.isConjugateSymmetric(9));

        RenderGrid fullHdGrid = RenderGrid.from(
                new Viewport(-0.75, 0.0, 2.4),
                1920,
                1080
        );

        assertTrue(fullHdGrid.isConjugateSymmetric(1080));
    }

    @Test
    void verticallyShiftedGridShouldNotHaveConjugateRows() {
        RenderGrid grid = RenderGrid.from(
                new Viewport(-0.75, 0.1, 2.4),
                17,
                9
        );

        assertFalse(grid.isConjugateSymmetric(9));
    }

    @Test
    void identicalViewportsShouldHaveZeroShift() {
        Viewport viewport =
                FractalPreset.MANDELBROT
                        .defaultViewport();

        Optional<PixelShift> result =
                PixelShiftCalculator.calculate(
                        viewport,
                        viewport,
                        1000,
                        700
                );

        assertTrue(result.isPresent());

        assertEquals(
                new PixelShift(0, 0),
                result.get()
        );
    }

    @Test
    void shiftedRenderGridShouldPreserveExactCoordinates() {
        int width = 2000;
        int height = 1408;

        Viewport viewport =
                FractalPreset.MANDELBROT
                        .defaultViewport();

        RenderGrid sourceGrid =
                RenderGrid.from(
                        viewport,
                        width,
                        height
                );

        PixelShift shift =
                new PixelShift(
                        340,
                        424
                );

        RenderGrid targetGrid =
                sourceGrid.shifted(shift);

        assertCoordinatesMatchExactly(
                sourceGrid,
                targetGrid,
                shift,
                width,
                height
        );
    }

    private static void assertCoordinatesMatchExactly(
            RenderGrid sourceGrid,
            RenderGrid targetGrid,
            PixelShift shift,
            int width,
            int height
    ) {
        int sourceXFrom =
                Math.max(
                        0,
                        -shift.dx()
                );

        int sourceXTo =
                Math.min(
                        width,
                        width - shift.dx()
                );

        int sourceYFrom =
                Math.max(
                        0,
                        -shift.dy()
                );

        int sourceYTo =
                Math.min(
                        height,
                        height - shift.dy()
                );

        assertTrue(sourceXFrom < sourceXTo);
        assertTrue(sourceYFrom < sourceYTo);

        for (int sourceX = sourceXFrom;
             sourceX < sourceXTo;
             sourceX++) {

            int targetX =
                    sourceX + shift.dx();

            double sourceReal =
                    sourceGrid.realAt(sourceX);

            double targetReal =
                    targetGrid.realAt(targetX);

            assertEquals(
                    Double.doubleToLongBits(sourceReal),
                    Double.doubleToLongBits(targetReal),
                    "Real coordinate differs at sourceX="
                            + sourceX
                            + ", targetX="
                            + targetX
            );
        }

        for (int sourceY = sourceYFrom;
             sourceY < sourceYTo;
             sourceY++) {

            int targetY =
                    sourceY + shift.dy();

            double sourceImaginary =
                    sourceGrid.imaginaryAt(sourceY);

            double targetImaginary =
                    targetGrid.imaginaryAt(targetY);

            assertEquals(
                    Double.doubleToLongBits(sourceImaginary),
                    Double.doubleToLongBits(targetImaginary),
                    "Imaginary coordinate differs at sourceY="
                            + sourceY
                            + ", targetY="
                            + targetY
            );
        }
    }
}
