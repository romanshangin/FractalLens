package com.shangin.fractal.scene;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IterationSettingsTest {

    @Test
    void calculatesAdaptiveLimitFromSceneParameters() {
        IterationSettings settings = new IterationSettings(300, 50);

        assertEquals(300, settings.maxIterations(2.4, 2.4));
        assertEquals(350, settings.maxIterations(2.4, 1.2));
        assertEquals(400, settings.maxIterations(2.4, 0.6));
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> new IterationSettings(0, 50));
        assertThrows(IllegalArgumentException.class, () -> new IterationSettings(300, -1));
    }
}
