package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GpuJuliaPreviewRequestTest {
    private static final SmoothPaletteColoring COLORING = new SmoothPaletteColoring(PalettePreset.ICE.palette());

    @Test
    void packsRawAxesAndTheFixedConstant() {
        RenderJob job = job(new Viewport(0, 0, 2.4), 8, 6, 300, -0.7269, 0.1889);
        RenderGrid grid = RenderGrid.from(job.viewport(), 8, 6);
        var request = new GpuJuliaPreviewRequest(job, grid, COLORING, new int[48]);
        FloatBuffer axes = FloatBuffer.allocate(8 + 6);
        request.writeAxes(axes);
        assertEquals(axes.capacity(), axes.position());
        for (int x = 0; x < 8; x++) assertEquals((float) grid.realAt(x), axes.get(x));
        for (int y = 0; y < 6; y++) assertEquals((float) grid.imaginaryAt(y), axes.get(8 + y));
        assertEquals((float) -0.7269, request.cReal());
        assertEquals((float) 0.1889, request.cImaginary());
        assertEquals(PaletteOffset.from(COLORING), request.offset());
    }

    @Test
    void rejectsJobsOutsideThePreviewEnvelope() {
        Viewport view = new Viewport(0, 0, 2.4);
        RenderJob supported = job(view, 8, 6, 300, -0.8, 0.156);
        RenderGrid grid = RenderGrid.from(view, 8, 6);
        assertThrows(IllegalArgumentException.class,
                () -> new GpuJuliaPreviewRequest(supported, grid, COLORING, new int[47]));
        RenderJob mandelbrot = new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                view, 8, 6, 300);
        assertThrows(IllegalArgumentException.class,
                () -> new GpuJuliaPreviewRequest(mandelbrot, grid, COLORING, new int[48]));
        RenderJob trapped = new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.CROSS),
                view, 8, 6, 300);
        assertThrows(IllegalArgumentException.class,
                () -> new GpuJuliaPreviewRequest(trapped, grid, COLORING, new int[48]));
        assertThrows(IllegalArgumentException.class, () -> new GpuJuliaPreviewRequest(
                job(view, 8, 6, GpuJuliaPreviewRequest.MAX_ITERATIONS + 1, -0.8, 0.156), grid, COLORING,
                new int[48]));
        assertThrows(IllegalArgumentException.class, () -> new GpuJuliaPreviewRequest(
                job(view, 8, 6, 300, 4.5, 0), grid, COLORING, new int[48]));
        // Neighboring pixels collapse to one FP32 coordinate: preview would show blocks, so it is refused.
        Viewport deep = new Viewport("0.3", "0.2", "1e-9");
        assertThrows(IllegalArgumentException.class, () -> new GpuJuliaPreviewRequest(
                job(deep, 8, 6, 300, -0.8, 0.156), RenderGrid.from(deep, 8, 6), COLORING, new int[48]));
        assertThrows(IllegalArgumentException.class, () -> new GpuJuliaPreviewRequest(supported, grid,
                new SmoothPaletteColoring(PalettePreset.ICE.palette(), 1.5, 0), new int[48]));
    }

    @Test
    void runtimeWithoutPreviewKernelReturnsEmptyAndDisablesNativeWork() throws Exception {
        RenderJob job = job(new Viewport(0, 0, 2.4), 8, 6, 40, -0.8, 0.156);
        var request = new GpuJuliaPreviewRequest(job, RenderGrid.from(job.viewport(), 8, 6), COLORING, new int[48]);
        try (GpuRuntime unavailable = new UnavailableGpuRuntime(GpuPlatform.MACOS, "disabled")) {
            assertTrue(unavailable.renderJuliaPreview(request).isEmpty());
        }
        var device = new GpuDevice("fake", "1.1", 0, 0, 0, 1024, 1L << 30,
                java.util.Set.of(GpuNumericCapability.FLOAT32));
        GpuSession session = new GpuSession() {
            public java.util.List<GpuDevice> devices() { return java.util.List.of(device); }
            public GpuDevice selectedDevice() { return device; }
            public void checkHealth() {}
            public void close(boolean deviceLost) {}
        };
        try (GpuRuntime runtime = ManagedGpuRuntime.open(GpuPlatform.MACOS, () -> session)) {
            assertTrue(runtime.renderJuliaPreview(request).isEmpty());
            assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
            assertTrue(runtime.capabilityReport().detail().contains("Julia preview kernel unavailable"));
        }
    }

    static RenderJob job(Viewport view, int width, int height, int iterations, double real, double imaginary) {
        var scene = new com.shangin.fractal.scene.FractalScene(FractalPreset.JULIA, view,
                new com.shangin.fractal.scene.IterationSettings(iterations, 0),
                new com.shangin.fractal.scene.ColoringSettings(PalettePreset.ICE),
                new com.shangin.fractal.scene.AntialiasSettings(),
                new com.shangin.fractal.scene.JuliaParameters(real, imaginary));
        return new RenderJob(FormulaDefinition.forScene(scene), view, width, height, iterations,
                RenderPriority.center(), Optional.empty());
    }
}
