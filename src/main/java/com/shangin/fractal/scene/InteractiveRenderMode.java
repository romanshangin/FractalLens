package com.shangin.fractal.scene;

/**
 * Interactive antialiasing presentation for standard or deep zoom. Enum names
 * are the stable scene format identifiers; display labels may change. The
 * declaration order is the interaction latency benchmark order (Refined, then
 * Fast, as established); the Settings window defines its own display order.
 */
public enum InteractiveRenderMode {
    /** Shows only refined antialiased tiles. */
    REFINED("Antialiased display"),
    /** Shows progressive base samples, then antialiases them in place. */
    FAST("Base, then antialiasing"),
    /** Shows progressive base samples and completes without an antialiasing pass. */
    BASE_ONLY("Base only");

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
