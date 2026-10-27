package com.shangin.fractal.scene;

import java.util.Objects;

/**
 * Immutable interactive antialiasing parameters: one presentation for standard
 * zoom and one for deep zoom.
 */
public record AntialiasSettings(InteractiveRenderMode renderMode, InteractiveRenderMode deepZoomRenderMode) {

    /** Deep zoom AA is expensive, so scenes without an explicit choice keep it off. */
    public static final InteractiveRenderMode DEFAULT_DEEP_ZOOM_RENDER_MODE = InteractiveRenderMode.BASE_ONLY;

    public AntialiasSettings {
        Objects.requireNonNull(renderMode);
        Objects.requireNonNull(deepZoomRenderMode);
    }

    public AntialiasSettings(InteractiveRenderMode renderMode) {
        this(renderMode, DEFAULT_DEEP_ZOOM_RENDER_MODE);
    }

    public AntialiasSettings() {
        this(InteractiveRenderMode.REFINED);
    }
}
