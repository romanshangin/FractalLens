package com.shangin.fractal.scene;

import java.util.Objects;

/** Immutable interactive antialiasing parameters. */
public record AntialiasSettings(
        SamplingPattern samplingPattern,
        InteractiveRenderMode renderMode
) {

    public AntialiasSettings {
        Objects.requireNonNull(samplingPattern);
        Objects.requireNonNull(renderMode);
    }

    public AntialiasSettings(SamplingPattern samplingPattern) {
        this(samplingPattern, InteractiveRenderMode.REFINED);
    }

    public AntialiasSettings() {
        this(SamplingPattern.REGULAR, InteractiveRenderMode.REFINED);
    }
}
