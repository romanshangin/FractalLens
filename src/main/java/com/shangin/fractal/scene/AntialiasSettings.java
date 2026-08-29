package com.shangin.fractal.scene;

import java.util.Objects;

/** Immutable interactive antialiasing parameters. */
public record AntialiasSettings(SamplingPattern samplingPattern) {

    public AntialiasSettings {
        Objects.requireNonNull(samplingPattern);
    }

    public AntialiasSettings() {
        this(SamplingPattern.REGULAR);
    }
}
