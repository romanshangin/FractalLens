package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.IntBuffer;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fractal.gpu.residentNative", matches = "true")
class GpuResidentMandelbrotNativeTest {
    @Test
    void keepsSamplesResidentAndReturnsConformantFinalColors() throws Exception {
        int width = 384, height = 256;
        List<Scene> scenes = List.of(
                new Scene("overview", new Viewport(-0.75, 0, 2.4)),
                new Scene("exterior", new Viewport(1, 1, 0.2)),
                new Scene("seahorse", new Viewport(-0.743643887037151, 0.13182590420533, 0.024)));
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault();
             var backend = new DirectDoubleRenderBackend()) {
            for (Scene scene : scenes) {
                RenderJob job = new RenderJob(
                        FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                        scene.viewport(), width, height, 300,
                        RenderPriority.center(), Optional.empty());
                RenderGrid grid = RenderGrid.from(job.viewport(), width, height);
                var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
                int[] gpuColors = new int[width * height];
                var resident = runtime.renderResidentMandelbrot(
                        new GpuResidentMandelbrotRequest(job, grid, coloring, gpuColors));
                assertTrue(resident.isPresent(), runtime.capabilityReport().toString());
                GpuResidentMandelbrotResult result = resident.orElseThrow();
                assertSame(gpuColors, result.colors());
                assertEquals(width * height, result.certifiedPixels() + result.recoveredPixels());
                assertEquals(result.recoveredPixels(), result.rejections().total());
                assertTrue(result.certifiedPixels() > 0, scene.name());
                assertTrue(result.recoveredPixels() > 0, scene.name());
                assertTrue(result.timing().readbackBytes() < (long) width * height * 8, scene.name());
                assertTrue(result.timing().nativeBytes() > 0, scene.name());

                RenderFrame cpu = RenderFrame.create(job, grid);
                backend.render(cpu, () -> false, ignored -> {}, null);
                int[] cpuColors = new int[width * height];
                new FractalColorizer().color(cpu.samplePlane(), IntBuffer.wrap(cpuColors), coloring);
                int differing = 0, maximumChannelError = 0;
                for (int i = 0; i < cpuColors.length; i++) {
                    if (cpuColors[i] != gpuColors[i]) differing++;
                    for (int shift : new int[]{24, 16, 8, 0}) {
                        maximumChannelError = Math.max(maximumChannelError, Math.abs(
                                ((cpuColors[i] >>> shift) & 0xff)
                                        - ((gpuColors[i] >>> shift) & 0xff)));
                    }
                }
                System.out.println("Resident " + scene.name() + " conformance: differing=" + differing
                        + "; max channel error=" + maximumChannelError
                        + "; rejections=" + result.rejections());
                assertTrue(differing < cpuColors.length / 100, scene.name());
                assertTrue(maximumChannelError <= 1, scene.name());
            }
        }
    }

    private record Scene(String name, Viewport viewport) {}
}
