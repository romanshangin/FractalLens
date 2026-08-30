package com.shangin.fractal.controller;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderActivityTrackerTest {

    @Test
    void shouldStayActiveUntilLatestRenderFinishes() {
        RenderActivityTracker tracker = new RenderActivityTracker();
        List<Boolean> states = new ArrayList<>();
        tracker.setListener(states::add);

        long first = tracker.begin();
        long second = tracker.begin();
        tracker.finish(first);
        tracker.finish(second);

        assertEquals(List.of(false, true, false), states);
    }

    @Test
    void cancellationShouldUnlockAppearanceAndIgnoreLateCompletion() {
        RenderActivityTracker tracker = new RenderActivityTracker();
        List<Boolean> states = new ArrayList<>();
        tracker.setListener(states::add);

        long generation = tracker.begin();
        tracker.cancel();
        tracker.finish(generation);

        assertEquals(List.of(false, true, false), states);
    }

    @Test
    void listenerShouldImmediatelyReceiveCurrentState() {
        RenderActivityTracker tracker = new RenderActivityTracker();
        tracker.begin();
        List<Boolean> states = new ArrayList<>();

        tracker.setListener(states::add);

        assertEquals(List.of(true), states);
    }
}
