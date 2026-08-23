package com.shangin.fractal.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FractalSampleTest {
    @Test
    void nonEscapedPointShouldReturnPlainIterations() {
        FractalSample sample = new FractalSample(100, false, 0.0, 0.0);

        assertEquals(100.0, sample.smoothIterations());
    }

    @Test
    void escapedPointShouldReturnFractionalSmoothValue() {
        FractalSample sample = new FractalSample(10, true, 3.0, 0.0);

        double smooth = sample.smoothIterations();

        assertTrue(smooth > 0.0);
        assertTrue(smooth < 11.0);
    }
}
