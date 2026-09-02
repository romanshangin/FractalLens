package com.shangin.fractal.controller;

/** Transient opt-in state; leaving deep zoom always restores the safe default. */
final class DeepZoomAntialiasState {

    private boolean deepZoomActive;
    private boolean enabled;

    void setDeepZoomActive(boolean active) {
        deepZoomActive = active;
        if (!active) {
            enabled = false;
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = deepZoomActive && enabled;
    }

    boolean isDeepZoomActive() {
        return deepZoomActive;
    }

    boolean shouldRefine() {
        return deepZoomActive && enabled;
    }
}
