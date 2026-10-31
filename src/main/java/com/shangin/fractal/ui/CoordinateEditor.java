package com.shangin.fractal.ui;

/**
 * Live Go to Coordinates state, used on the FX thread by the native and JavaFX editors.
 * Drafts are compared with the text shown when the editor opened; untouched fields take
 * the current exact values at the time of the call, not a snapshot from opening.
 */
interface CoordinateEditor {
    /**
     * Zero when the drafts resolve to a reachable view or change nothing, otherwise the
     * one-based field to correct (1 real, 2 imaginary, 3 zoom). Never changes the scene.
     */
    int invalidField(CoordinateText shown, String real, String imaginary, String zoom);

    /**
     * Resolves, validates and applies the drafts in one step with the same result as
     * {@link #invalidField}. Nothing changes on failure; unchanged drafts are a no-op.
     */
    int submit(CoordinateText shown, String real, String imaginary, String zoom);
}
