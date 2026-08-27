package com.shangin.fractal.math;

import com.shangin.fractal.formula.FractalPreset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ViewportTest {
    private static final double DELTA = 1e-10;

    @Test
    void leftPixelShouldMapToMinReal() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.4);

        double imaginary = viewport.imaginaryAt(0, 700);

        assertEquals(1.2, imaginary, DELTA);
    }

    @Test
    void rightPixelShouldMapToMaxReal() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.0);

        double real = viewport.realAt(999, 1000, 1000);

        assertEquals(1.0, real, DELTA);
    }

    @Test
    void topPixelShouldMapToMaxImaginary() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.4);

        double imaginary = viewport.imaginaryAt(0, 700);

        assertEquals(1.2, imaginary, DELTA);
    }

    @Test
    void bottomPixelShouldMapToMinImaginary() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.4);

        double imaginary = viewport.imaginaryAt(699, 700);

        assertEquals(-1.2, imaginary, DELTA);
    }

    @Test
    void zoomShouldChangeScaleButKeepCenter() {
        Viewport viewport = new Viewport(-0.75, 0.0, 2.4);

        Viewport zoomed = viewport.zoom(0.5);

        assertEquals(-0.75, zoomed.centerReal(), DELTA);

        assertEquals(0.0, zoomed.centerImaginary(), DELTA);

        assertEquals(1.2, zoomed.scale(), DELTA);
    }

    @Test
    void panShouldMoveCenterButKeepScale() {
        Viewport viewport = new Viewport(-0.75, 0.0, 2.4);

        Viewport moved = viewport.pan(0.1, -0.2);

        assertEquals(-0.65, moved.centerReal(), DELTA);

        assertEquals(-0.2, moved.centerImaginary(), DELTA);

        assertEquals(2.4, moved.scale(), DELTA);
    }

    @Test
    void visibleWidthShouldRespectAspectRatio() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.4);

        double width = viewport.visibleWidth(1000, 500);

        assertEquals(4.8, width, DELTA);
    }

    @Test
    void fitShouldPreserveContentWidthInNarrowWindow() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.4);

        Viewport fitted = viewport.fit(3.5, 2.4, 500, 1000);

        double visibleWidth = fitted.visibleWidth(500, 1000);

        assertEquals(3.5, visibleWidth, DELTA);
    }

    @Test
    void zoomShouldKeepPointUnderCursor() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.4);

        int width = 1000;
        int height = 700;

        double x = 750;
        double y = 200;

        double realBefore = viewport.realAt(x, width, height);

        double imaginaryBefore = viewport.imaginaryAt(y, height);

        Viewport zoomed = viewport.zoomAt(x, y, width, height, 0.5);

        double realAfter = zoomed.realAt(x, width, height);

        double imaginaryAfter = zoomed.imaginaryAt(y, height);

        assertEquals(realBefore, realAfter, DELTA);

        assertEquals(imaginaryBefore, imaginaryAfter, DELTA);
    }

    @Test
    void zoomInShouldDecreaseScale() {
        Viewport viewport = new Viewport(0.0, 0.0, 2.4);

        Viewport zoomed = viewport.zoomAt(500, 350, 1000, 700, 0.5);

        assertEquals(1.2, zoomed.scale(), DELTA);
    }

    @Test
    void realMappingShouldBeReversible() {
        Viewport viewport = FractalPreset.MANDELBROT.defaultViewport();

        int width = 1000;
        int height = 700;

        double[] positions = {
                0.0,
                1.0,
                100.0,
                499.5,
                999.0};

        for (double x : positions) {
            double real = viewport.realAt(
                    x,
                    width,
                    height);

            double restoredX = viewport.xAt(
                            real,
                            width,
                            height);

            assertEquals(
                    x,
                    restoredX,
                    1e-9
            );
        }
    }

    @Test
    void imaginaryMappingShouldBeReversible() {
        Viewport viewport = FractalPreset.MANDELBROT.defaultViewport();

        int height = 700;

        double[] positions = {
                0.0,
                1.0,
                100.0,
                349.5,
                699.0
        };

        for (double y : positions) {
            double imaginary = viewport.imaginaryAt(
                    y,
                    height);

            double restoredY = viewport.yAt(
                    imaginary,
                    height);

            assertEquals(
                    y,
                    restoredY,
                    1e-9
            );
        }
    }
}