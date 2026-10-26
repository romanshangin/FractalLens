package com.shangin.fractal.scene;

import java.util.Objects;

/** Immutable interactive antialiasing parameters. */
public record AntialiasSettings(InteractiveRenderMode renderMode) {

    public AntialiasSettings {
        Objects.requireNonNull(renderMode);
    }

    public AntialiasSettings() {
        this(InteractiveRenderMode.REFINED);
    }
}
