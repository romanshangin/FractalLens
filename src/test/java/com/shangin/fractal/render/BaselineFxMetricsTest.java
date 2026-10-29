package com.shangin.fractal.render;

import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaselineFxMetricsTest {
    @Test void diagnosticFactoryPreservesEveryFixtureGridAndEmptySampleContract() {
        for (var fixture : BaselineFixtures.matrix(96, 66)) for (var step : fixture.steps()) {
            var result = new BaselineFxBenchmark.Result(step, InteractiveRenderMode.FAST);
            var actual = BaselineFxBenchmark.createDiagnosticFrame(result);
            var expected = RenderFrame.create(step.job());
            assertEquals(Thread.currentThread().threadId(), result.fxThreadId);
            assertSame(step.job(), actual.job());
            assertEquals(expected.renderGrid(), actual.renderGrid(), fixture.id());
            assertEquals(0, actual.validity().readyPixelCount());
            assertFalse(actual.isComplete());
            assertTrue(result.gridStart <= result.gridEnd);
            assertTrue(result.gridEnd <= result.frameStart);
            assertTrue(result.frameStart <= result.frameEnd);
            assertTrue(result.gridCpu >= -1);
            assertTrue(result.frameCpu >= -1);
            for (int index = 0; index < expected.samplePlane().size(); index++) {
                assertEquals(expected.samplePlane().iterations(index), actual.samplePlane().iterations(index));
                assertEquals(expected.samplePlane().smoothIterations(index), actual.samplePlane().smoothIterations(index));
                assertEquals(expected.samplePlane().escaped(index), actual.samplePlane().escaped(index));
                assertEquals(expected.samplePlane().orbitTrapDistance(index), actual.samplePlane().orbitTrapDistance(index));
            }
        }
    }

    @Test void hiddenBaseAndMissingPulsesStayUnmeasured() {
        var step = BaselineFixtures.matrix(96, 66).getFirst().steps().getFirst();
        var result = new BaselineFxBenchmark.Result(step, InteractiveRenderMode.REFINED);
        result.published("frame_promoted", 1000);
        result.published("aa_publish", 1100);
        assertEquals(1000, result.firstVisible);
        assertEquals(1100, result.firstAa);
        assertEquals(-1, result.firstBase);
        assertEquals(-1, result.firstPostLayout);
        assertEquals(-1, result.gridStart);
        assertEquals(-1, result.frameEnd);
        assertEquals(-1, result.gridCpu);
        assertEquals(-1, result.frameCpu);
        assertEquals(-1, BaselineFxBenchmark.delta(-1, 100));
        assertEquals(-1, BaselineFxBenchmark.delta(100, -1));
    }

    @Test void conformanceChecksSmoothValuesAndTrapDataBeyondIterationHashes() {
        // Trap distances are stored only for an orbit-trap formula.
        var job = BaselineFixtures.matrix(96, 66).stream()
                .filter(fixture -> fixture.id().equals("mandelbrot-trap-aa"))
                .findFirst().orElseThrow().steps().getFirst().job();
        var a = RenderFrame.create(job); var b = RenderFrame.create(job);
        var whole = new RenderRegion(0, 0, job.width(), job.height());
        a.validity().markReady(whole); b.validity().markReady(whole);
        BaselineFxBenchmark.assertSamples(a, b);
        b.samplePlane().setValues(0, 0, 0.125, false, Double.NaN);
        assertThrows(IllegalStateException.class, () -> BaselineFxBenchmark.assertSamples(a, b));
        a.samplePlane().setValues(0, 0, 0.125, false, Double.NaN);
        b.samplePlane().setValues(0, 0, 0.125, false, 0.75);
        assertThrows(IllegalStateException.class, () -> BaselineFxBenchmark.assertSamples(a, b));
    }
}
