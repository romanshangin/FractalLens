package com.shangin.fractal.render;

import com.shangin.fractal.scene.InteractiveRenderMode;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BaselineFxSampleEventTest {
    @TempDir Path directory;

    @Test void recordingPreservesSampleIdentityBoundariesAndMissingMilestones() throws Exception {
        var step = BaselineFixtures.matrix(96, 66).getFirst().steps().getFirst();
        var result = new BaselineFxBenchmark.Result(step, InteractiveRenderMode.REFINED);
        result.effective = InteractiveRenderMode.FAST;
        result.request = 7;
        result.start = System.nanoTime();
        result.fxStart = result.start + 10;
        result.planned = result.start + 30;
        result.gridStart = result.fxStart;
        result.gridEnd = result.start + 20;
        result.frameStart = result.gridEnd;
        result.frameEnd = result.planned;
        result.fxThreadId = 42;
        result.gridCpu = 8;
        result.frameCpu = 9;
        result.firstVisible = result.start + 50;
        result.snapshotStart = result.start + 70;
        result.snapshotEnd = result.start + 90;
        Path output = directory.resolve("sample.jfr");
        try (var recording = new Recording()) {
            recording.enable(BaselineFxSampleEvent.class).withThreshold(Duration.ZERO).withoutStackTrace();
            recording.start();
            BaselineFxSampleEvent.emit(result, "overview", "sample", 2);
            recording.stop();
            recording.dump(output);
        }
        var events = RecordingFile.readAllEvents(output).stream()
                .filter(event -> event.getEventType().getName().equals("com.shangin.fractal.BaselineFxSample")).toList();
        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals("overview", event.getString("fixture"));
        assertEquals(step.name(), event.getString("step"));
        assertEquals("sample", event.getString("phase"));
        assertEquals("REFINED", event.getString("requestedMode"));
        assertEquals("FAST", event.getString("effectiveMode"));
        assertEquals(2, event.getInt("run"));
        assertEquals(7, event.getLong("request"));
        assertEquals(96, event.getInt("width"));
        assertEquals(66, event.getInt("height"));
        assertEquals(result.start, event.getLong("requestNanos"));
        assertEquals(result.fxStart, event.getLong("fxStartNanos"));
        assertEquals(result.planned, event.getLong("plannedNanos"));
        assertEquals(1, event.getInt("planningVersion"));
        assertEquals(42, event.getLong("fxThreadId"));
        assertEquals(result.gridStart, event.getLong("gridStartNanos"));
        assertEquals(result.gridEnd, event.getLong("gridEndNanos"));
        assertEquals(result.frameStart, event.getLong("frameStartNanos"));
        assertEquals(result.frameEnd, event.getLong("frameEndNanos"));
        assertEquals(8, event.getLong("gridCpuNanos"));
        assertEquals(9, event.getLong("frameCpuNanos"));
        assertEquals(result.firstVisible, event.getLong("firstVisibleNanos"));
        assertEquals(result.snapshotStart, event.getLong("snapshotStartNanos"));
        assertEquals(result.snapshotEnd, event.getLong("snapshotEndNanos"));
        assertEquals(-1, event.getLong("firstBackendRegionNanos"));
        assertEquals(-1, event.getLong("fullPostLayoutNanos"));
        assertTrue(event.getLong("anchorBeforeNanos") <= event.getLong("anchorAfterNanos"));
        assertTrue(event.getLong("anchorBeforeNanos") >= result.start);
    }

    @Test void disabledRecordingCannotSilentlyDiscardRequestedDiagnostics() {
        var step = BaselineFixtures.matrix(96, 66).getFirst().steps().getFirst();
        var result = new BaselineFxBenchmark.Result(step, InteractiveRenderMode.FAST);
        assertThrows(IllegalStateException.class, () -> BaselineFxSampleEvent.emit(result, "overview", "sample", 0));
    }

    @Test void thresholdFilteredRecordingCannotSilentlyDiscardRequestedDiagnostics() throws Exception {
        var step = BaselineFixtures.matrix(96, 66).getFirst().steps().getFirst();
        var result = new BaselineFxBenchmark.Result(step, InteractiveRenderMode.FAST);
        result.effective = InteractiveRenderMode.FAST;
        try (var recording = new Recording()) {
            recording.enable(BaselineFxSampleEvent.class).withThreshold(Duration.ofDays(1));
            recording.start();
            assertThrows(IllegalStateException.class, () -> BaselineFxSampleEvent.emit(result, "overview", "sample", 0));
        }
    }
}
