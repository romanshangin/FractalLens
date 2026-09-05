package com.shangin.fractal.controller;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderActivityTrackerTest {

    @Test
    void timingBelongsOnlyToLatestSuccessfulGeneration() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        RenderActivityTracker tracker = new RenderActivityTracker(clock::get);
        List<RenderStatus> statuses = new ArrayList<>();
        tracker.setStatusListener(statuses::add);
        long first = tracker.begin();
        clock.set(100);
        long second = tracker.begin();
        clock.set(350);
        tracker.finish(first);
        tracker.fail(first);
        tracker.finish(second);
        tracker.finish(second);
        assertEquals(List.of(
                new RenderStatus(RenderStatus.State.IDLE, 0),
                new RenderStatus(RenderStatus.State.RENDERING, 0),
                new RenderStatus(RenderStatus.State.RENDERING, 0),
                new RenderStatus(RenderStatus.State.COMPLETE, 250)), statuses);
    }

    @Test
    void cancelledAndFailedRendersDoNotPublishCompletedTime() {
        RenderActivityTracker tracker = new RenderActivityTracker(() -> 100L);
        List<RenderStatus> statuses = new ArrayList<>();
        tracker.setStatusListener(statuses::add);
        long cancelled = tracker.begin();
        tracker.cancel();
        tracker.finish(cancelled);
        long failed = tracker.begin();
        tracker.fail(failed);
        tracker.finish(failed);
        assertEquals(List.of(RenderStatus.State.IDLE, RenderStatus.State.RENDERING,
                RenderStatus.State.CANCELLED, RenderStatus.State.RENDERING,
                RenderStatus.State.FAILED), statuses.stream().map(RenderStatus::state).toList());
    }

    @Test
    void optionalRefinementAddsItsTimeWithoutCountingIdleTime() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        RenderActivityTracker tracker = new RenderActivityTracker(clock::get);
        List<RenderStatus> statuses = new ArrayList<>();
        tracker.setStatusListener(statuses::add);
        long base = tracker.begin();
        clock.set(100);
        tracker.finish(base);
        clock.set(500);
        long refinement = tracker.beginRefinement();
        clock.set(550);
        tracker.finish(refinement);
        assertEquals(new RenderStatus(RenderStatus.State.COMPLETE, 150), statuses.getLast());
    }


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
