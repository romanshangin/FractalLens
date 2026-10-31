package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.PreciseComplex;
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

import java.math.BigDecimal;
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
    void firstResizeShouldPreserveRestoredViewportExactly() {
        Viewport restored = new Viewport(
                "-0.743643887037151000000000000000000000000000000000000000000001",
                "0.131825904205330000000000000000000000000000000000000000000007",
                "1.2E-75");
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT, restored);

        camera.resize(1000, 700);

        assertEquals(restored, camera.viewport());
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
    void destinationAppliesZoomRelativeToFittedHomeView() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        camera.resize(1000, 700);
        var center = new PreciseComplex("-0.743643887037151000000000000000001", "0.131825904205330000000000002");
        BigDecimal home = camera.defaultViewport(1000, 700).scaleExact();

        Viewport deep = camera.destination(center, new BigDecimal("1E40"), 1000, 700);

        assertEquals(center, deep.center());
        assertEquals(0, home.movePointLeft(40).compareTo(deep.scaleExact()));
        assertEquals(0, home.compareTo(camera.destination(center, BigDecimal.ONE, 1000, 700).scaleExact()));
    }

    @Test
    void repeatedGoToWithTheCurrentZoomDoesNotGrowPrecision() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        camera.resize(1000, 700);
        Viewport home = camera.defaultViewport(1000, 700);
        Viewport viewport = camera.destination(
                new PreciseComplex("-0.743643887037151", "0.13182590420533"), new BigDecimal("1.8121147956826947E10"),
                1000, 700);
        int first = viewport.mathContext().getPrecision();
        for (int cycle = 0; cycle < 10; cycle++) {
            // Same derivation as Copy Coordinates and Zoom.
            BigDecimal zoom = home.scaleExact().divide(viewport.scaleExact(), viewport.mathContext());
            viewport = camera.destination(viewport.center(), zoom, 1000, 700);
        }
        assertEquals(first, viewport.mathContext().getPrecision());
        assertTrue(first < 64, "A 1e10 zoom needs no more than the minimum budget plus guards");
    }

    @Test
    void copiedTextGoesBackWithinAThousandthOfARenderPixelAndFiveTimesTenToTheMinusTwelveZoom() {
        var cases = new Viewport[] {
                new Viewport("-0.75", "0", "2"),
                new Viewport("0", "-0.0000000000000000000000000000004", "1.5"),
                new Viewport("-0.743643887037151234567890123456789012345678901", "0.131825904205330987654321098765",
                        "3.1415926535897932384626433832795E-12"),
                new Viewport(new PreciseComplex("-1.7685736562998748386395147208736447271957906", "-0.0000000000123"),
                        new BigDecimal("2.718281828459045235360287471352662E-80"))};
        int width = 1000;
        int height = 700;
        for (Viewport source : cases) {
            for (int renderScale : new int[] {1, 2, 3}) {
                int renderWidth = width * renderScale;
                int renderHeight = height * renderScale;
                FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT, source);
                camera.resize(width, height);
                Viewport home = camera.defaultViewport(width, height);
                BigDecimal zoom = home.scaleExact().divide(source.scaleExact(), source.mathContext());
                CoordinateText text = CoordinateText.of(source, renderWidth, renderHeight, zoom);
                // Another view re-enters every field, as after pasting the copied text.
                CoordinateTarget target = CoordinateTarget.parse(new CoordinateText("", "", ""),
                        text.real(), text.imaginary(), text.zoom());
                Viewport destination = camera.destination(
                        target.center(home.center()), target.zoom(), width, height);

                BigDecimal step = source.imaginaryUnitsPerPixelExact(renderHeight);
                var pixels = java.math.MathContext.DECIMAL64;
                assertTrue(destination.center().real().subtract(source.center().real()).abs()
                        .divide(step, pixels).compareTo(new BigDecimal("0.001")) < 0, text::real);
                assertTrue(destination.center().imaginary().subtract(source.center().imaginary()).abs()
                        .divide(step, pixels).compareTo(new BigDecimal("0.001")) < 0, text::imaginary);
                // The scale comes back through home / zoom, so the bound includes that division.
                BigDecimal scaleError = destination.scaleExact().subtract(source.scaleExact()).abs()
                        .divide(source.scaleExact(), pixels);
                assertTrue(scaleError.compareTo(new BigDecimal("5.00000001E-12")) <= 0, text::zoom);
                assertFalse(text.real().contains("E") || text.imaginary().contains("E"), text::toString);
            }
        }
    }

    @Test
    void coordinateTextUsesTheFinerRenderGridAndKeepsFewerDigitsAtLowerDensity() {
        Viewport viewport = new Viewport("-0.743643887037151234567", "0.131825904205330987654", "0.0001");
        CoordinateText standard = CoordinateText.of(viewport, 1000, 700, BigDecimal.TEN);
        CoordinateText retina = CoordinateText.of(viewport, 2000, 1400, BigDecimal.TEN);
        // 0.001 of 1.43e-7 rounds to 1e-10; on the doubled grid, to 1e-11.
        assertEquals("-0.743643887", standard.real());
        assertEquals("-0.74364388704", retina.real());
        assertEquals("Real: -0.743643887\nImaginary: 0.1318259042\nZoom: 10", standard.clipboard());
    }

    @Test
    void repeatedGoToAndSceneJsonCyclesDoNotGrowPrecision() {
        int width = 1000;
        int height = 700;
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        camera.resize(width, height);
        Viewport home = camera.defaultViewport(width, height);
        var scene = com.shangin.fractal.scene.FractalScene.create(
                FractalPreset.MANDELBROT, com.shangin.fractal.coloring.PalettePreset.ICE)
                .withViewport(camera.destination(new PreciseComplex("-0.743643887037151", "0.13182590420533"),
                        new BigDecimal("2.5E45"), width, height));
        int first = -1;
        for (int cycle = 0; cycle < 10; cycle++) {
            Viewport viewport = scene.viewport();
            BigDecimal zoom = home.scaleExact().divide(viewport.scaleExact(), viewport.mathContext());
            CoordinateText text = CoordinateText.of(viewport, 2 * width, 2 * height, zoom);
            CoordinateTarget target = CoordinateTarget.parse(new CoordinateText("", "", ""),
                    text.real(), text.imaginary(), text.zoom());
            scene = com.shangin.fractal.scene.FractalSceneJson.read(com.shangin.fractal.scene.FractalSceneJson.write(
                    scene.withViewport(camera.destination(target.center(viewport.center()), target.zoom(),
                            width, height))));
            int precision = scene.viewport().mathContext().getPrecision();
            if (first < 0) first = precision;
            assertEquals(first, precision, "cycle " + cycle);
        }
    }

    @Test
    void destinationWithoutZoomKeepsCurrentScaleExactly() {
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        camera.resize(1000, 700);
        camera.zoomBy(310, 270, 0.37, 1000, 700, 2000, 1400);
        BigDecimal scale = camera.viewport().scaleExact();

        Viewport moved = camera.destination(new PreciseComplex("0.25", "-0.5"), null, 1000, 700);

        assertEquals(scale, moved.scaleExact());
        assertEquals(new PreciseComplex("0.25", "-0.5"), moved.center());
    }

    @Test
    void canGoToRejectsZoomBeyondDirectPrecisionOnlyWithoutDeepZoom() {
        var center = new PreciseComplex("-0.5", "0.25");
        for (FractalPreset preset : FractalPreset.values()) {
            FractalCamera camera = new FractalCamera(preset);
            camera.resize(1000, 700);
            assertTrue(camera.canGoTo(camera.destination(center, new BigDecimal("1000"), 1000, 700), 2000, 1400));
            assertEquals(preset.supportsDeepZoom(),
                    camera.canGoTo(camera.destination(center, new BigDecimal("1E30"), 1000, 700), 2000, 1400),
                    preset::toString);
        }
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
        FractalCamera camera = new FractalCamera(FractalPreset.TRICORN);
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
                FractalPreset.TRICORN.minimumUlpsPerPixel()
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
    void juliaZoomContinuesIntoDeepZoomPrecision() {
        FractalCamera camera = new FractalCamera(FractalPreset.JULIA);
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
                FractalPreset.JULIA.minimumUlpsPerPixel()
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
