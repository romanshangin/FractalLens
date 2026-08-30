package com.shangin.fractal.scene;

/** Controls whether raw interactive samples may be displayed before refinement. */
public enum InteractiveRenderMode {
    REFINED("Refined tiles"),
    FAST("Fast preview");

    private final String displayName;

    InteractiveRenderMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
