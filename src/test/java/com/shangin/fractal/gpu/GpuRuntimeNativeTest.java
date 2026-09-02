package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static com.shangin.fractal.gpu.GpuNumericCapability.FLOAT32;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in native gate; absence of a GPU fails this gate instead of skipping it. */
@EnabledIfSystemProperty(named = "fractal.gpu.nativeSmoke", matches = "true")
class GpuRuntimeNativeTest {
    @Test
    void validatesNativeLifecycleAndMatchingCpuFallback() throws Exception {
        if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) {
            try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
                System.out.println(runtime.capabilityReport());
                assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
                verifyCpuFallback(runtime);
            }
            return;
        }
        try (GpuRuntime first = GpuRuntimeFactory.createDefault();
             GpuRuntime second = GpuRuntimeFactory.createDefault()) {
            System.out.println(first.capabilityReport());
            assertEquals(GpuRuntimeState.AVAILABLE, first.capabilityReport().state(),
                    () -> first.capabilityReport().detail());
            assertTrue(first.checkHealth());
            assertTrue(second.checkHealth(), () -> second.capabilityReport().detail());
            first.close();
            assertEquals(GpuRuntimeState.CLOSED, first.capabilityReport().state());
            assertTrue(second.checkHealth(), "Closing one runtime must not unload another's loader");
        }
        try (GpuRuntime reopened = GpuRuntimeFactory.createDefault()) {
            assertTrue(reopened.checkHealth(), () -> reopened.capabilityReport().detail());
            // Exercise the loss path without provoking a real hardware fault.
            reopened.handleDeviceLoss(new GpuException("simulated device loss", true));
            assertEquals(GpuRuntimeState.DEVICE_LOST, reopened.capabilityReport().state());
            assertFalse(reopened.checkHealth());
            verifyCpuFallback(reopened);
        }
    }

    private static void verifyCpuFallback(GpuRuntime runtime) throws Exception {
        try (PrecisionSelectingRenderBackend cpu = new PrecisionSelectingRenderBackend(
                new DirectDoubleRenderBackend(), new MandelbrotPerturbationRenderBackend())) {
            for (Viewport viewport : new Viewport[]{new Viewport(-0.75, 0, 2.4),
                    new Viewport("-0.7436438870371510000000000000000000000001",
                            "0.1318259042053300000000000000000000000002", "1e-80")}) {
                RenderJob job = new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT,
                        OrbitTrap.NONE), viewport, 8, 8, 100);
                RenderFrame frame = RenderFrame.create(job);
                RenderFrame result = runtime.runOrFallback(FLOAT32,
                        () -> fail("GPU execution after failure"),
                        () -> cpu.render(frame, () -> false, region -> {}, stats -> {}));
                assertTrue(result.isComplete());
            }
        }
    }
}
