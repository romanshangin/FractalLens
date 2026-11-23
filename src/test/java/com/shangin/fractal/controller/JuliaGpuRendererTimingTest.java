package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.FakeJuliaPreviewRuntime;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.scene.FractalScene;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The worker reports the runtime's stage costs, asks the runtime to filter and filters itself when the GPU cannot. */
class JuliaGpuRendererTimingTest {
    private record Frame(JuliaGpuRenderer.FrameTiming timing, int[] colors) {}

    @Test
    void reportsRuntimeStagesAndForwardsTheFilterRequest() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            for (boolean filter : new boolean[] {true, false}) {
                var timing = render(renderer, filter ? 1 : 2, filter).timing();
                // The fake runtime reports a 1 ns dispatch and a 1 ns total.
                assertEquals(1, timing.gpu().dispatchNanos());
                assertEquals(1, timing.gpu().totalNanos());
                assertTrue(timing.callNanos() >= 0);
                assertTrue(timing.readyNanos() - timing.startNanos() >= timing.callNanos());
                assertEquals(filter, runtime.lastFilterBrightPoints());
                assertEquals(0, timing.cpuFilterNanos());
            }
        }
    }

    @Test
    void filtersOnTheCpuWhenTheGpuPassDidNotRun() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        runtime.showIsolatedBrightPixel();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            var gpu = render(renderer, 1, true);
            runtime.refuseGpuFilter(true);
            var cpu = render(renderer, 2, true);
            var unfiltered = render(renderer, 3, false);

            // The isolated white pixel at (1, 1) on a 0x202020 background is dimmed to 0x303030.
            assertEquals(0xff303030, gpu.colors()[64 + 1]);
            assertArrayEquals(gpu.colors(), cpu.colors());
            assertEquals(0xffffffff, unfiltered.colors()[64 + 1]);
            assertTrue(gpu.timing().gpu().brightPointsFiltered());
            assertFalse(cpu.timing().gpu().brightPointsFiltered());
            assertEquals(0, gpu.timing().cpuFilterNanos());
            assertTrue(cpu.timing().cpuFilterNanos() > 0);
            assertEquals(0, unfiltered.timing().cpuFilterNanos());
        }
    }

    private static Frame render(JuliaGpuRenderer renderer, long sequence, boolean filter) throws Exception {
        FractalScene scene = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE);
        RenderJob job = new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), 64, 48, 100);
        var result = new CompletableFuture<Frame>();
        renderer.submit(new JuliaGpuRenderer.Request(sequence, job, scene.coloring(), filter),
                new JuliaGpuRenderer.Listener() {
                    @Override public void rendered(JuliaGpuRenderer.Request request, int[] colors,
                                                   JuliaGpuRenderer.FrameTiming timing) {
                        int[] copy = colors.clone();
                        renderer.release(colors);
                        result.complete(new Frame(timing, copy));
                    }
                    @Override public void unavailable(JuliaGpuRenderer.Request request) {
                        result.completeExceptionally(new AssertionError("unavailable"));
                    }
                    @Override public void failed(JuliaGpuRenderer.Request request, Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
        return result.get(10, TimeUnit.SECONDS);
    }
}
