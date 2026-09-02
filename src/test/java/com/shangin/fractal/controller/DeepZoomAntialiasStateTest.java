package com.shangin.fractal.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepZoomAntialiasStateTest {

    @Test
    void staysDisabledByDefaultWhenDeepZoomStarts() {
        DeepZoomAntialiasState state = new DeepZoomAntialiasState();

        state.setDeepZoomActive(true);

        assertTrue(state.isDeepZoomActive());
        assertFalse(state.shouldRefine());
    }

    @Test
    void optInSurvivesSubsequentDeepFramesAndResetsOnExit() {
        DeepZoomAntialiasState state = new DeepZoomAntialiasState();
        state.setDeepZoomActive(true);
        state.setEnabled(true);

        state.setDeepZoomActive(true);
        assertTrue(state.shouldRefine());

        state.setDeepZoomActive(false);
        assertFalse(state.isDeepZoomActive());
        assertFalse(state.shouldRefine());

        state.setDeepZoomActive(true);
        assertFalse(state.shouldRefine());
    }

    @Test
    void cannotBeEnabledOutsideDeepZoom() {
        DeepZoomAntialiasState state = new DeepZoomAntialiasState();

        state.setEnabled(true);

        assertFalse(state.shouldRefine());
    }
}
