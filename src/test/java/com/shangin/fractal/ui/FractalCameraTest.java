package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void resetShouldRestoreFittedPresetViewport() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        int width = 1000;
        int height = 700;

        camera.resize(width, height);
        camera.zoomIn(750, 200, width, height, 2000, 1400);
        camera.pan(100, -50, width, height);
        camera.reset(width, height);

        assertEquals(camera.defaultViewport(width, height), camera.viewport());
    }

    @Test
    void setCenterShouldMoveViewportToExplicitCoordinates() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        int width = 1000;
        int height = 700;
        camera.resize(width, height);
        camera.zoomIn(500, 350, width, height, 2000, 1400);

        assertTrue(camera.setCenter(-0.5, 0.2, width, height));
        assertEquals(-0.5, camera.viewport().centerReal(), DELTA);
        assertEquals(0.2, camera.viewport().centerImaginary(), DELTA);

        assertTrue(camera.setCenter(100.0, -100.0, width, height));
        assertEquals(100.0, camera.viewport().centerReal(), DELTA);
        assertEquals(-100.0, camera.viewport().centerImaginary(), DELTA);
        assertFalse(camera.setCenter(100.0, -100.0, width, height));
    }

    @Test
    void setCenterShouldRejectNonFiniteCoordinates() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        camera.resize(1000, 700);

        assertThrows(
                IllegalArgumentException.class,
                () -> camera.setCenter(Double.NaN, 0.0, 1000, 700)
        );
    }

    @Test
    void continuousZoomShouldUseGestureFactorAndFocalPoint() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        int width = 1000;
        int height = 700;
        camera.resize(width, height);
        Viewport before = camera.viewport();

        assertTrue(camera.zoomBy(750, 200, 0.5, width, height, 2000, 1400));

        assertEquals(before.scale() * 0.5, camera.viewport().scale(), DELTA);
        assertTrue(camera.viewport().centerReal() > before.centerReal());
        assertTrue(camera.viewport().centerImaginary() > before.centerImaginary());
    }

    @Test
    void continuousZoomOutShouldStopAtDefaultViewport() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        int width = 1000;
        int height = 700;
        camera.resize(width, height);
        camera.zoomBy(500, 350, 0.5, width, height, 2000, 1400);

        assertTrue(camera.zoomBy(500, 350, 10.0, width, height, 2000, 1400));
        assertEquals(camera.defaultViewport(width, height), camera.viewport());
        assertFalse(camera.zoomBy(500, 350, 1.1, width, height, 2000, 1400));
    }

    @Test
    void continuousZoomShouldRejectInvalidFactor() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        camera.resize(1000, 700);

        assertThrows(
                IllegalArgumentException.class,
                () -> camera.zoomBy(500, 350, 0.0, 1000, 700, 2000, 1400)
        );
    }

    @Test
    void juliaShouldStopBeforeOrbitDetailBecomesNumericallyBlocky() {
        FractalCamera camera = new FractalCamera(FractalPreset.JULIA);
        int logicalWidth = 1000;
        int logicalHeight = 700;
        int renderWidth = 2000;
        int renderHeight = 1400;
        camera.resize(logicalWidth, logicalHeight);

        assertTrue(camera.zoomIn(
                650,
                250,
                logicalWidth,
                logicalHeight,
                renderWidth,
                renderHeight
        ));

        int acceptedZooms = 0;
        while (camera.zoomBy(
                logicalWidth / 2.0,
                logicalHeight / 2.0,
                0.8,
                logicalWidth,
                logicalHeight,
                renderWidth,
                renderHeight
        )) {
            acceptedZooms++;
            assertTrue(acceptedZooms < 200, "Julia precision guard did not stop zooming");
        }

        assertTrue(camera.viewport().hasSufficientPrecision(
                renderWidth,
                renderHeight,
                FractalPreset.JULIA.minimumUlpsPerPixel()
        ));
        assertTrue(acceptedZooms > 20);
    }
}
