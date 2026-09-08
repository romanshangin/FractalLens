package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class InteractionLatencyTest {
    @Test void disabledRecorderDoesNotWrapExecutorOrPublish() {
        var trace = new InteractionLatency();
        java.util.concurrent.Executor direct = Runnable::run;
        assertSame(direct, trace.callbacks("base", direct));
        trace.input("scroll"); trace.preview(); trace.renderStarted(); trace.mark("complete");
        assertTrue(trace.stop().isEmpty());
    }

    @Test void separatesQueueWaitFromCallbackWorkAndKeepsOriginalGeneration() {
        AtomicLong clock = new AtomicLong(100);
        var trace = new InteractionLatency(clock::get);
        List<Runnable> queue = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        trace.arm(event -> markers.add(event.stage()));
        trace.input("scroll"); trace.renderStarted();
        var executor = trace.callbacks("base", queue::add);
        executor.execute(() -> clock.addAndGet(7));
        trace.input("scroll"); // supersedes input while the old callback is queued
        trace.mark("base_publish");
        assertTrue(markers.isEmpty());
        clock.set(140);
        queue.removeFirst().run();
        var events = trace.stop();
        var wait = events.stream().filter(e -> e.stage().equals("base_queue_sum")).findFirst().orElseThrow();
        var work = events.stream().filter(e -> e.stage().equals("base_fx_work_sum")).findFirst().orElseThrow();
        assertEquals(40, wait.durationNanos());
        assertEquals(7, work.durationNanos());
        assertEquals(1, wait.input());
        assertEquals(1, wait.render());
    }

    @Test void recordsFirstPublicationOncePerRenderAndRetainsFirstInputPreview() {
        var trace = new InteractionLatency();
        List<String> markers = new ArrayList<>();
        trace.arm(event -> markers.add(event.stage()));
        trace.input("drag"); trace.preview(); trace.renderStarted();
        trace.mark("aa_publish"); trace.mark("aa_publish");
        trace.renderStarted(); trace.mark("aa_publish");
        assertEquals(List.of("preview_publish", "aa_publish", "aa_publish"), markers);
        assertEquals(2, trace.stop().stream().filter(e -> e.stage().equals("aa_publish")).count());
    }

    @Test void queuedCallbackFromAnEarlierTrialCannotContaminateRearmedRecorder() {
        var trace = new InteractionLatency();
        List<Runnable> queue = new ArrayList<>();
        trace.arm(ignored -> {});
        trace.input("scroll"); trace.renderStarted();
        trace.callbacks("base", queue::add).execute(() -> {});
        trace.stop();
        trace.arm(ignored -> {});
        queue.removeFirst().run();
        assertTrue(trace.stop().isEmpty());
    }

    @Test void screenObservationKeepsPublicationIdentityAfterAnotherInput() {
        var trace = new InteractionLatency();
        List<InteractionLatency.Event> publications = new ArrayList<>();
        trace.arm(publications::add);
        trace.input("scroll"); trace.renderStarted(); trace.mark("base_publish");
        var original = publications.getFirst();
        trace.input("scroll"); trace.renderStarted();
        trace.observed(original, "_screen_observed");
        var observed = trace.stop().stream()
                .filter(e -> e.stage().equals("base_publish_screen_observed"))
                .findFirst().orElseThrow();
        assertEquals(1, observed.input());
        assertEquals(1, observed.render());
        assertTrue(observed.nanos() >= original.nanos());
    }

    @Test void oldScreenObservationCannotEnterANewRecording() {
        var trace = new InteractionLatency();
        List<InteractionLatency.Event> publications = new ArrayList<>();
        trace.arm(publications::add);
        trace.input("scroll"); trace.preview();
        var original = publications.getFirst();
        trace.stop();
        trace.arm(ignored -> {});
        trace.observed(original, "_screen_observed");
        assertTrue(trace.stop().isEmpty());
    }
    @Test void diagnosticsRetainOriginalIdentityAndStopRequiresWorkerDrain() {
        var trace = new InteractionLatency();
        var done = new java.util.concurrent.CompletableFuture<com.shangin.fractal.render.RenderDiagnostics.Snapshot>();
        trace.arm(ignored -> {});
        trace.input("scroll"); trace.renderStarted();
        trace.attachDiagnostics(done);
        trace.input("scroll"); trace.renderStarted();
        assertThrows(IllegalStateException.class, trace::stop);
        done.complete(new com.shangin.fractal.render.RenderDiagnostics.Snapshot(27,
                java.util.Map.of("request_drained", 900L, "backend_exit", 800L, "worker_run_sum_ns", 123L)));
        trace.diagnosticsDrained().join();
        var events = trace.stop();
        var exit = events.stream().filter(e -> e.stage().equals("diagnostic_backend_exit")).findFirst().orElseThrow();
        assertEquals(1, exit.input()); assertEquals(1, exit.render()); assertEquals(800, exit.nanos());
        var work = events.stream().filter(e -> e.stage().equals("diagnostic_worker_run_sum_ns")).findFirst().orElseThrow();
        assertEquals(123, work.durationNanos()); assertEquals(900, work.nanos());
    }

    @Test void lateDiagnosticsFromOldEpochCannotEnterNextTrial() {
        var trace = new InteractionLatency();
        var done = new java.util.concurrent.CompletableFuture<com.shangin.fractal.render.RenderDiagnostics.Snapshot>();
        trace.arm(ignored -> {}); trace.input("scroll"); trace.renderStarted(); trace.attachDiagnostics(done);
        trace.arm(ignored -> {});
        done.complete(new com.shangin.fractal.render.RenderDiagnostics.Snapshot(3, java.util.Map.of("request_drained", 900L)));
        assertTrue(trace.stop().isEmpty());
    }

}
