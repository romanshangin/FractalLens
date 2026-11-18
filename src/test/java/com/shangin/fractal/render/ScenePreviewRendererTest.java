package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.scene.SceneGallery;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenePreviewRendererTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 100;

    @Test
    void everyBundledGalleryEntryRendersVisibleStructure() throws IOException, InterruptedException {
        try (var renderer = new ScenePreviewRenderer()) {
            for (var entry : SceneGallery.loadBundled().entries()) {
                int[] pixels = renderer.render(entry.scene(), WIDTH, HEIGHT, () -> false);
                assertNotNull(pixels, entry.id());
                assertEquals(WIDTH * HEIGHT, pixels.length);
                var colors = new HashSet<Integer>();
                for (int pixel : pixels) colors.add(pixel);
                // A flat or nearly flat preview means the place no longer shows its structure.
                assertTrue(colors.size() >= 32, entry.id() + " has " + colors.size() + " colors");
            }
        }
    }

    @Test
    void galleryCoversStandardAndDeepZoomRendering() throws IOException {
        int standard = 0;
        int deepMandelbrot = 0;
        int deepJulia = 0;
        for (var entry : SceneGallery.loadBundled().entries()) {
            var scene = entry.scene();
            // A realistic Retina window; deep entries must need the high-precision backend.
            if (scene.fractal().hasSufficientDirectPrecision(scene.viewport(), 2400, 1500)) standard++;
            else if (scene.fractal() == FractalPreset.MANDELBROT) deepMandelbrot++;
            else if (scene.fractal() == FractalPreset.JULIA) deepJulia++;
        }
        assertTrue(standard > 0);
        assertTrue(deepMandelbrot > 0);
        assertTrue(deepJulia > 0);
    }

    @Test
    void cancelledPreviewPublishesNothing() throws IOException, InterruptedException {
        try (var renderer = new ScenePreviewRenderer()) {
            var scene = SceneGallery.loadBundled().entries().getFirst().scene();
            assertNull(renderer.render(scene, WIDTH, HEIGHT, () -> true));
        }
    }
}
