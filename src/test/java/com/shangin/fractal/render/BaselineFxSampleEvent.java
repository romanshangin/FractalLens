package com.shangin.fractal.render;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Diagnostic clock bridge: JFR begin lies between the two monotonic anchor readings. */
@Name("com.shangin.fractal.BaselineFxSample")
@Label("FX baseline sample boundaries")
@StackTrace(false)
final class BaselineFxSampleEvent extends Event {
    String fixture, step, requestedMode, effectiveMode, phase;
    int width, height, run;
    long request;
    int planningVersion;
    long gridStartNanos, gridEndNanos, frameStartNanos, frameEndNanos;
    long gridCpuNanos, frameCpuNanos, fxThreadId;
    long anchorBeforeNanos, anchorAfterNanos;
    long requestNanos, fxStartNanos, plannedNanos, firstBackendRegionNanos;
    long firstVisibleNanos, fullPostLayoutNanos, snapshotStartNanos, snapshotEndNanos;

    static void emit(BaselineFxBenchmark.Result r, String fixture, String phase, int run) {
        var event = new BaselineFxSampleEvent();
        if (!event.isEnabled()) {
            throw new IllegalStateException("baseline.fx.jfr requires an active recording with BaselineFxSample enabled");
        }
        // Construct and commit after measurement; no event allocation on the timed FX path.
        event.anchorBeforeNanos = System.nanoTime();
        event.begin();
        event.anchorAfterNanos = System.nanoTime();
        event.fixture = fixture;
        event.step = r.step.name();
        event.width = r.step.job().width();
        event.height = r.step.job().height();
        event.requestedMode = r.requested.name();
        event.effectiveMode = r.effective.name();
        event.phase = phase;
        event.run = run;
        event.request = r.request;
        event.planningVersion = 1;
        event.fxThreadId = r.fxThreadId;
        event.gridStartNanos = r.gridStart;
        event.gridEndNanos = r.gridEnd;
        event.frameStartNanos = r.frameStart;
        event.frameEndNanos = r.frameEnd;
        event.gridCpuNanos = r.gridCpu;
        event.frameCpuNanos = r.frameCpu;
        event.requestNanos = r.start;
        event.fxStartNanos = r.fxStart;
        event.plannedNanos = r.planned;
        event.firstBackendRegionNanos = r.firstRegion.get();
        event.firstVisibleNanos = r.firstVisible;
        event.fullPostLayoutNanos = r.fullPostLayout;
        event.snapshotStartNanos = r.snapshotStart;
        event.snapshotEndNanos = r.snapshotEnd;
        event.end();
        if (!event.shouldCommit()) {
            throw new IllegalStateException("BaselineFxSample recording must use a zero event threshold");
        }
        event.commit();
    }
}
