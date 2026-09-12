package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepZoomRenderPolicyTest {

    private static final RenderTarget TARGET = new RenderTarget(1920, 1080);

    @Test
    void reportedJuliaCriticalPointUsesTheSameDeepPolicyAsTheBackend() throws Exception {
        FractalScene scene = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                .withViewport(com.shangin.fractal.render.ReportedJuliaFixture.viewport());
        assertTrue(DeepZoomRenderPolicy.isDeepZoom(scene, new RenderTarget(2600, 1675)));
    }

    @Test
    void deepMandelbrotUsesFastBasePresentationBeforeOptionalPreciseRefinement() {
        FractalScene scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withViewport(new Viewport("-0.8267486182939549503119853330756",
                        "0.2150828768422976562284460839629", "1e-16"));

        assertTrue(DeepZoomRenderPolicy.isDeepZoom(scene, TARGET));
        assertEquals(InteractiveRenderMode.FAST,
                DeepZoomRenderPolicy.presentationMode(scene, TARGET));
    }

    @Test
    void deepJuliaUsesFastBasePresentationBeforeOptionalPreciseRefinement() {
        FractalScene scene = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                .withViewport(new Viewport("-0.8267486182939549503119853330756",
                        "0.2150828768422976562284460839629", "1e-16"));

        assertTrue(DeepZoomRenderPolicy.isDeepZoom(scene, TARGET));
        assertEquals(InteractiveRenderMode.FAST,
                DeepZoomRenderPolicy.presentationMode(scene, TARGET));
    }

    @Test
    void normalMandelbrotKeepsRequestedPresentationMode() {
        FractalScene scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);

        assertFalse(DeepZoomRenderPolicy.isDeepZoom(scene, TARGET));
        assertEquals(scene.antialiasing().renderMode(),
                DeepZoomRenderPolicy.presentationMode(scene, TARGET));
    }
}
