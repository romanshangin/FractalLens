package com.shangin.fractal.controller;

import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;

/** Selects the scene's standard or deep zoom presentation for a render target. */
final class DeepZoomRenderPolicy {

    private DeepZoomRenderPolicy() {
    }

    static boolean isDeepZoom(FractalScene scene, RenderTarget target) {
        return scene.fractal().supportsDeepZoom()
                && !scene.fractal().hasSufficientDirectPrecision(scene.viewport(), target.width(), target.height());
    }

    static InteractiveRenderMode presentationMode(
            FractalScene scene,
            RenderTarget target
    ) {
        return isDeepZoom(scene, target)
                ? scene.antialiasing().deepZoomRenderMode()
                : scene.antialiasing().renderMode();
    }
}
