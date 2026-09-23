package com.shangin.fractal.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecolorFailureGateTest {
    @Test
    void queuedSuccessfulRequestSuppressesAnEarlierFailure() {
        RecolorFailureGate gate = new RecolorFailureGate();

        assertFalse(gate.shouldReport(1, 1, 1, 2, 0, true));
        assertFalse(gate.shouldReport(1, 1, 1, 2, 2, true));
    }

    @Test
    void continuingFailuresReportOnceEvenWithNewerRequestsQueued() {
        RecolorFailureGate gate = new RecolorFailureGate();

        assertFalse(gate.shouldReport(1, 1, 1, 2, 0, true));
        assertTrue(gate.shouldReport(1, 1, 2, 3, 0, true));
        assertFalse(gate.shouldReport(1, 1, 3, 4, 0, true));
    }

    @Test
    void successStartsANewFailurePeriod() {
        RecolorFailureGate gate = new RecolorFailureGate();

        assertTrue(gate.shouldReport(1, 1, 1, 1, 0, true));
        assertFalse(gate.shouldReport(1, 1, 2, 3, 2, true));
        assertTrue(gate.shouldReport(1, 1, 3, 3, 2, true));
    }

    @Test
    void replacementFrameOrEpochSuppressesStaleFailure() {
        RecolorFailureGate gate = new RecolorFailureGate();

        assertFalse(gate.shouldReport(1, 2, 4, 4, 0, true));
        assertFalse(gate.shouldReport(2, 2, 4, 4, 0, false));
        assertTrue(gate.shouldReport(2, 2, 5, 5, 0, true));
    }
}
