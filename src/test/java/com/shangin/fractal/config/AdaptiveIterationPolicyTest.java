package com.shangin.fractal.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveIterationPolicyTest {

    private final IterationPolicy policy = new AdaptiveIterationPolicy(50);

    @Test
    void defaultScaleShouldUseBaseIterations() {
        int result = policy.maxIterations(300, 2.4, 2.4);

        assertEquals(300, result);
    }

    @Test
    void twoTimesZoomShouldAddOneStep() {
        int result = policy.maxIterations(300, 2.4, 1.2);

        assertEquals(350, result);
    }

    @Test
    void fourTimesZoomShouldAddTwoSteps() {
        int result = policy.maxIterations(300, 2.4, 0.6);

        assertEquals(400, result);
    }

    @Test
    void zoomOutShouldNotDecreaseBaseIterations() {
        int result = policy.maxIterations(300, 2.4, 3.0);

        assertEquals(300, result);
    }

    @Test
    void intermediateZoomShouldIncreaseIterationsGradually() {
        int result = policy.maxIterations(300, 2.4, 0.9);

        assertEquals(370, result);
    }
}