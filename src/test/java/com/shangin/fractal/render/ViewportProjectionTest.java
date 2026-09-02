package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewportProjectionTest {

    @Test
    void deepPanPreservesFractionalScreenTranslationBelowCoordinateUlp() {
        Viewport source = new Viewport(
                "-0.8317528516858322713653476366999",
                "0.207813754242134522471317257011028",
                "1.6e-13");
        int width = 1000;
        int height = 800;
        BigDecimal realShift = source.realUnitsPerPixelExact(width, height)
                .multiply(new BigDecimal("2.375"), source.mathContext());
        BigDecimal imaginaryShift = source.imaginaryUnitsPerPixelExact(height)
                .multiply(new BigDecimal("-1.625"), source.mathContext());
        Viewport target = source.pan(realShift.negate(), imaginaryShift);

        ViewportProjection projection = ViewportProjection.between(
                source, target, width, height);

        assertEquals(2.375, projection.translateX(), 1e-12);
        assertEquals(-1.625, projection.translateY(), 1e-12);
    }

    @Test
    void deepCursorAnchoredZoomOutKeepsTheCursorFixed() {
        Viewport source = new Viewport(
                "-0.8317528516858322713653476366999",
                "0.207813754242134522471317257011028",
                "1.6e-13");
        int width = 1000;
        int height = 800;
        double anchorX = 250.0;
        double anchorY = 300.0;
        Viewport target = source.zoomAt(
                BigDecimal.valueOf(anchorX), BigDecimal.valueOf(anchorY),
                width, height, new BigDecimal("1.25"));

        ViewportProjection projection = ViewportProjection.between(
                source, target, width, height);

        assertEquals(anchorX,
                projection.translateX() + projection.scaleX() * anchorX, 1e-10);
        assertEquals(anchorY,
                projection.translateY() + projection.scaleY() * anchorY, 1e-10);
    }
}
