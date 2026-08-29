package com.shangin.fractal.scene;

/** Subpixel placement used by interactive antialias refinement. */
public enum SamplingPattern {
    REGULAR("Regular grid"),
    DETERMINISTIC_JITTER("Deterministic jitter");

    private final String displayName;

    SamplingPattern(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
