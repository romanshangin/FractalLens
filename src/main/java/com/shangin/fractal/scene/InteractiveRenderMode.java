package com.shangin.fractal.scene;

/**
 * Standard-zoom antialiasing presentation. Enum names are the stable scene
 * format identifiers; display labels may change.
 */
public enum InteractiveRenderMode {
    /** Shows progressive base samples and completes without an antialiasing pass. */
    BASE_ONLY("Base only"),
    /** Shows progressive base samples, then antialiases them in place. */
    FAST("Base, then antialiasing"),
    /** Shows only refined antialiased tiles. */
    REFINED("Antialiased display");

    private final String displayName;

    InteractiveRenderMode(String displayName) {
        this.displayName = displayName;
    }

    public boolean antialiases() {
        return this != BASE_ONLY;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
