package com.shangin.fractal.controller;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;

/** Keeps double-only refinement out of Mandelbrot deep-zoom renders. */
final class DeepZoomRenderPolicy {

    private DeepZoomRenderPolicy() {
    }

    static boolean isDeepZoom(FractalScene scene, RenderTarget target) {
        return scene.fractal() == FractalPreset.MANDELBROT
                && !scene.viewport().hasSufficientPrecision(
                target.width(),
                target.height(),
                scene.fractal().minimumUlpsPerPixel());
    }

    static InteractiveRenderMode presentationMode(
            FractalScene scene,
            RenderTarget target
    ) {
        return isDeepZoom(scene, target)
                ? InteractiveRenderMode.FAST
                : scene.antialiasing().renderMode();
    }
}
