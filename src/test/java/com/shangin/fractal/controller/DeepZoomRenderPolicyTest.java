package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(FractalPreset.class)
    void deepZoomUsesTheSceneDeepZoomModeAndKeepsBaseOnlyByDefault(FractalPreset preset) {
        FractalScene scene = FractalScene.create(preset, PalettePreset.ICE)
                .withViewport(new Viewport("-0.8267486182939549503119853330756",
                        "0.2150828768422976562284460839629", "1e-16"));
        if (!preset.supportsDeepZoom()) {
            assertFalse(DeepZoomRenderPolicy.isDeepZoom(scene, TARGET));
            return;
        }

        assertTrue(DeepZoomRenderPolicy.isDeepZoom(scene, TARGET));
        assertEquals(InteractiveRenderMode.BASE_ONLY, DeepZoomRenderPolicy.presentationMode(scene, TARGET));
        for (InteractiveRenderMode mode : InteractiveRenderMode.values()) {
            FractalScene selected = scene.withAntialiasing(
                    new AntialiasSettings(InteractiveRenderMode.REFINED, mode));
            assertEquals(mode, DeepZoomRenderPolicy.presentationMode(selected, TARGET), mode.name());
        }
    }

    @Test
    void normalMandelbrotKeepsRequestedPresentationMode() {
        FractalScene scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);

        assertFalse(DeepZoomRenderPolicy.isDeepZoom(scene, TARGET));
        for (InteractiveRenderMode mode : InteractiveRenderMode.values()) {
            FractalScene selected = scene.withAntialiasing(new AntialiasSettings(mode, InteractiveRenderMode.REFINED));
            assertEquals(mode, DeepZoomRenderPolicy.presentationMode(selected, TARGET), mode.name());
        }
    }
}
