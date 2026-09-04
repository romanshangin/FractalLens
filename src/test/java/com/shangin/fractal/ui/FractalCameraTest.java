package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FrameReusePlanner;
import com.shangin.fractal.render.FrameReuseResult;
import com.shangin.fractal.render.ParallelFractalCalculator;
import com.shangin.fractal.render.PixelShift;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.RenderRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

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
    void resizeShouldPreservePixelSpacing() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);

        int oldWidth = 1000;
        int oldHeight = 700;

        camera.resize(oldWidth, oldHeight);
        camera.zoomIn(500, 350, oldWidth, oldHeight, 2000, 1400);
        Viewport before = camera.viewport();
        double stepBefore = before.imaginaryUnitsPerPixel(oldHeight);
        camera.resize(500, 1000);
        assertEquals(stepBefore, camera.viewport().imaginaryUnitsPerPixel(1000), DELTA);
        assertEquals(stepBefore, camera.viewport().realUnitsPerPixel(500, 1000), DELTA);
        assertEquals(before.center(), camera.viewport().center());
        camera.resize(oldWidth, oldHeight);
        assertEquals(before.scale(), camera.viewport().scale(), DELTA);
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
    void zoomInStopsBeforeDoublePrecisionBecomesInsufficient() {
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

        int zooms = 0;
        while (camera.zoomBy(
                logicalWidth / 2.0,
                logicalHeight / 2.0,
                0.8,
                logicalWidth,
                logicalHeight,
                renderWidth,
                renderHeight
        )) {
            zooms++;
        }

        assertTrue(zooms > 0);
        assertTrue(camera.viewport().hasSufficientPrecision(
                renderWidth,
                renderHeight,
                FractalPreset.JULIA.minimumUlpsPerPixel()
        ));
        assertFalse(camera.zoomIn(
                logicalWidth / 2.0,
                logicalHeight / 2.0,
                logicalWidth,
                logicalHeight,
                renderWidth,
                renderHeight
        ));
    }

    @Test
    void mandelbrotZoomContinuesIntoDeepZoomPrecision() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        int logicalWidth = 1000;
        int logicalHeight = 700;
        int renderWidth = 2000;
        int renderHeight = 1400;
        camera.resize(logicalWidth, logicalHeight);

        for (int zoom = 0; zoom < 200; zoom++) {
            assertTrue(camera.zoomBy(
                    logicalWidth / 2.0,
                    logicalHeight / 2.0,
                    0.8,
                    logicalWidth,
                    logicalHeight,
                    renderWidth,
                    renderHeight
            ));
        }

        assertFalse(camera.viewport().hasSufficientPrecision(
                renderWidth,
                renderHeight,
                FractalPreset.MANDELBROT.minimumUlpsPerPixel()
        ));
    }

    @Test
    void snappedPanAtDeepestSupportedZoomShouldCalculateOnlyExposedPixels() throws Exception {
        FractalPreset preset = FractalPreset.MANDELBROT;
        FractalCamera camera = new FractalCamera(preset);
        int logicalWidth = 320;
        int logicalHeight = 240;
        int renderWidth = 640;
        int renderHeight = 480;
        camera.resize(logicalWidth, logicalHeight);

        for (int zoom = 0; zoom < 120; zoom++) {
            assertTrue(camera.zoomBy(
                    logicalWidth / 2.0,
                    logicalHeight / 2.0,
                    0.8,
                    logicalWidth,
                    logicalHeight,
                    renderWidth,
                    renderHeight
            ));
        }

        Viewport sourceViewport = camera.viewport();
        AtomicInteger formulaCalls = new AtomicInteger();
        FractalCalculator calculator = new FractalCalculator(
                (real, imaginary, maximum) -> {
                    formulaCalls.incrementAndGet();
                    return new FractalSample(1, true, 1.0, 0.0);
                }
        );
        RenderRequest sourceRequest = new RenderRequest(
                calculator,
                sourceViewport,
                renderWidth,
                renderHeight,
                1_000
        );
        RenderFrame sourceFrame = RenderFrame.create(sourceRequest);
        sourceFrame.validity().markReady(
                new RenderRegion(0, 0, renderWidth, renderHeight)
        );

        assertTrue(camera.pan(3.0, -2.0, logicalWidth, logicalHeight));
        camera.snapToRenderGrid(sourceViewport, renderWidth, renderHeight);

        FrameReuseResult reuse = new FrameReusePlanner().plan(
                sourceFrame,
                new RenderRequest(
                        calculator,
                        camera.viewport(),
                        renderWidth,
                        renderHeight,
                        1_000
                )
        );

        double rawShiftX = (sourceViewport.centerReal() - camera.viewport().centerReal())
                / sourceViewport.realUnitsPerPixel(renderWidth, renderHeight);
        double rawShiftY = (camera.viewport().centerImaginary() - sourceViewport.centerImaginary())
                / sourceViewport.imaginaryUnitsPerPixel(renderHeight);
        assertTrue(
                reuse.reused(),
                "A render-grid-snapped deep pan must not discard the frame; raw shift="
                        + rawShiftX + ", " + rawShiftY
        );
        PixelShift expectedShift = new PixelShift(6, -4);
        int expectedReused = (renderWidth - 6) * (renderHeight - 4);
        assertEquals(expectedShift, reuse.shift().orElseThrow());
        assertEquals(expectedReused, reuse.reusedPixels());

        try (ParallelFractalCalculator workers = new ParallelFractalCalculator(4)) {
            workers.calculate(reuse.frame(), () -> false, ignored -> {});
        }

        assertEquals(renderWidth * renderHeight - expectedReused, formulaCalls.get());
        assertTrue(reuse.frame().isComplete());
    }
}
