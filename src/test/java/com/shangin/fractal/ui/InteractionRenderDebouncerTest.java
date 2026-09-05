package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InteractionRenderDebouncerTest {

    private final List<Long> delays = new ArrayList<>();
    private int stops;
    private int renders;
    private final List<Boolean> preservedLimits = new ArrayList<>();
    private final InteractionRenderDebouncer debouncer = new InteractionRenderDebouncer(
            delays::add, () -> stops++, preserve -> {
                renders++;
                preservedLimits.add(preserve);
            });

    @Test
    void onlyPurePanBatchesMayPreserveTheIterationBudget() {
        debouncer.requestPanRender();
        debouncer.finish();
        debouncer.requestZoomRender();
        debouncer.finish();
        debouncer.zoomStarted();
        debouncer.requestZoomRender();
        debouncer.zoomFinished();
        debouncer.requestZoomRender();
        debouncer.requestPanRender();
        debouncer.finish();
        debouncer.requestPanRender();
        debouncer.requestZoomRender();
        debouncer.finish();
        debouncer.requestPanRender();
        debouncer.finish();

        assertEquals(List.of(true, false, false, false, false, true), preservedLimits);
    }

    @Test
    void cancellationClearsThePendingZoomBudgetChange() {
        debouncer.requestZoomRender();
        debouncer.cancel();
        debouncer.requestPanRender();
        debouncer.finish();
        assertEquals(List.of(true), preservedLimits);
    }

    @Test
    void wheelChangesRestartAShortTimerWithoutRenderingEveryEvent() {
        debouncer.requestZoomRender();
        debouncer.requestZoomRender();
        debouncer.requestZoomRender();

        assertEquals(List.of(30L, 30L, 30L), delays);
        assertEquals(0, renders);

        debouncer.finish();
        assertEquals(1, renders);
    }

    @Test
    void gestureFinishStopsTheTimerAndRendersOnlyOnce() {
        debouncer.zoomStarted();
        debouncer.requestZoomRender();
        debouncer.zoomFinished();

        assertEquals(1, stops);
        assertEquals(1, renders);

        // A later timer notification or duplicate gesture boundary is a no-op.
        debouncer.finish();
        assertEquals(1, stops);
        assertEquals(1, renders);
    }

    @Test
    void unchangedGestureDoesNotStartARender() {
        debouncer.zoomStarted();
        debouncer.zoomFinished();

        assertEquals(0, stops);
        assertEquals(0, renders);
    }

    @Test
    void cancellingDiscardsPendingWorkButAllowsTheNextZoom() {
        debouncer.requestZoomRender();
        debouncer.cancel();
        debouncer.finish();
        assertEquals(0, renders);

        debouncer.requestZoomRender();
        debouncer.finish();
        assertEquals(1, renders);
    }

    @Test
    void panningRetainsItsExistingDelayWhenSwitchingInteractionTypes() {
        debouncer.requestPanRender();
        debouncer.requestZoomRender();
        debouncer.requestPanRender();

        assertEquals(List.of(75L, 30L, 75L), delays);
        debouncer.finish();
        assertEquals(1, renders);
    }

    @Test
    void livePinchRetainsCoalescingAndWheelUsesTheShortDelayAfterItEnds() {
        debouncer.zoomStarted();
        debouncer.requestZoomRender();
        debouncer.requestZoomRender();
        assertEquals(List.of(75L, 75L), delays);
        assertEquals(0, renders);

        debouncer.zoomFinished();
        assertEquals(1, renders);
        debouncer.requestZoomRender();
        assertEquals(List.of(75L, 75L, 30L), delays);
    }

    @Test
    void timerExpiryDuringAPinchDoesNotCauseADuplicateRenderAtItsEnd() {
        debouncer.zoomStarted();
        debouncer.requestZoomRender();
        debouncer.finish();
        debouncer.zoomFinished();

        assertEquals(1, renders);
        assertEquals(1, stops);
    }
}
