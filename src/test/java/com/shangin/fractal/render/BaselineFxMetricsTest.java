package com.shangin.fractal.render;

import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaselineFxMetricsTest {
    @Test void hiddenBaseAndMissingPulsesStayUnmeasured() {
        var step = BaselineFixtures.matrix(96, 66).getFirst().steps().getFirst();
        var result = new BaselineFxBenchmark.Result(step, InteractiveRenderMode.REFINED);
        result.published("frame_promoted", 1000);
        result.published("aa_publish", 1100);
        assertEquals(1000, result.firstVisible);
        assertEquals(1100, result.firstAa);
        assertEquals(-1, result.firstBase);
        assertEquals(-1, result.firstPostLayout);
        assertEquals(-1, BaselineFxBenchmark.delta(-1, 100));
        assertEquals(-1, BaselineFxBenchmark.delta(100, -1));
    }

    @Test void conformanceChecksSmoothValuesAndTrapDataBeyondIterationHashes() {
        var job = BaselineFixtures.matrix(96, 66).getFirst().steps().getFirst().job();
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
