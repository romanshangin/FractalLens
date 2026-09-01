package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MandelbrotPerturbationInteriorTest {

    @Test
    void rejectsOnlyPointsSafelyInsideKnownMandelbrotInterior() {
        assertTrue(MandelbrotPerturbationRenderBackend.isSafelyInsideKnownInterior(0.0, 0.0));
        assertTrue(MandelbrotPerturbationRenderBackend.isSafelyInsideKnownInterior(-1.0, 0.0));
        assertFalse(MandelbrotPerturbationRenderBackend.isSafelyInsideKnownInterior(0.5, 0.0));
        assertFalse(MandelbrotPerturbationRenderBackend.isSafelyInsideKnownInterior(0.25, 0.0));
    }
}
