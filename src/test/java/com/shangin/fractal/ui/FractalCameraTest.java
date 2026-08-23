package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FractalCameraTest {

    private static final double DELTA = 1e-10;

    @Test
    void firstResizeShouldFitDefaultViewport() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);

        int width = 1000;
        int height = 700;

        camera.resize(width, height);
        Viewport expected = camera.defaultViewport(width, height);
        assertEquals(expected.centerReal(), camera.viewport().centerReal(), DELTA);
        assertEquals(expected.centerImaginary(), camera.viewport().centerImaginary(), DELTA);
        assertEquals(expected.scale(), camera.viewport().scale(), DELTA);
    }

    @Test
    void resizeAtDefaultShouldFitNewWindow() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);

        camera.resize(1000, 700);
        camera.resize(500, 1000);

        Viewport expected = camera.defaultViewport(500, 1000);
        assertEquals(expected.centerReal(), camera.viewport().centerReal(), DELTA);
        assertEquals(expected.centerImaginary(), camera.viewport().centerImaginary(), DELTA);
        assertEquals(expected.scale(), camera.viewport().scale(), DELTA);
    }

    @Test
    void resizeShouldPreserveZoomLevel() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);

        int oldWidth = 1000;
        int oldHeight = 700;

        camera.resize(oldWidth, oldHeight);
        camera.zoomIn(500, 350, oldWidth, oldHeight, 2000, 1400);
        Viewport oldDefault = camera.defaultViewport(oldWidth, oldHeight);
        double zoomBefore = oldDefault.scale() / camera.viewport().scale();
        camera.resize(500, 1000);
        Viewport newDefault = camera.defaultViewport(500, 1000);
        double zoomAfter = newDefault.scale() / camera.viewport().scale();
        assertEquals(zoomBefore, zoomAfter, DELTA);
    }

    @Test
    void resizeShouldPreserveViewportCenter() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);

        int width = 1000;
        int height = 700;

        camera.resize(width, height);
        camera.zoomIn(750, 200, width, height, 2000, 1400);
        double centerRealBefore = camera.viewport().centerReal();
        double centerImaginaryBefore = camera.viewport().centerImaginary();
        camera.resize(600, 900);
        assertEquals(centerRealBefore, camera.viewport().centerReal(), DELTA);
        assertEquals(centerImaginaryBefore, camera.viewport().centerImaginary(), DELTA);
    }
}