package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.FakeJuliaPreviewRuntime;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.FractalScene;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The renderer prepares the kernel once with a small discarded frame, never in place of a frame. */
class JuliaGpuRendererWarmUpTest {
    private static final FractalScene JULIA = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE);

    @Test
    void warmsTheKernelOnceWithASmallFilteredFrameOfTheJob() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            assertTrue(renderer.needsWarmUp());
            renderer.warmUp(job(JULIA, 640, 400, 300), JULIA.coloring());
            assertFalse(renderer.needsWarmUp());
            await(() -> runtime.warmUps() == 1);
            assertEquals(JuliaGpuRenderer.WARM_UP_SIZE, runtime.lastWarmUpWidth());
            assertEquals(JuliaGpuRenderer.WARM_UP_SIZE, runtime.lastWarmUpHeight());
            assertEquals(300, runtime.lastWarmUpMaxIterations());
            assertTrue(runtime.lastWarmUpFilterBrightPoints(), "the warm-up prepares both passes");

            renderer.warmUp(job(JULIA, 640, 400, 300), JULIA.coloring());
            render(renderer, 1);
            assertEquals(1, runtime.warmUps());
            assertEquals(1, runtime.calls(), "the warm-up is not a frame");
        }
    }

    @Test
    void aFrameThatRanLeavesNothingToWarm() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            render(renderer, 1);
            assertFalse(renderer.needsWarmUp());
            renderer.warmUp(job(JULIA, 640, 400, 300), JULIA.coloring());
            render(renderer, 2);
            assertEquals(0, runtime.warmUps());
        }
    }

    @Test
    void aFrameThatFinishesFirstMakesTheQueuedWarmUpANoOp() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        runtime.setGated(true);
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            var outcomes = new LinkedBlockingQueue<String>();
            renderer.submit(request(1), listener(renderer, outcomes));
            await(() -> runtime.started() == 1);
            renderer.warmUp(job(JULIA, 640, 400, 300), JULIA.coloring());
            runtime.release();
            assertEquals("rendered 1", outcomes.poll(10, TimeUnit.SECONDS));
            runtime.setGated(false);
            render(renderer, 2);
            assertEquals(0, runtime.warmUps());
        }
    }

    @Test
    void aWaitingFrameDoesNotSkipTheWarmUp() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        runtime.setGated(true);
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            var outcomes = new LinkedBlockingQueue<String>();
            renderer.submit(request(1), listener(renderer, outcomes));
            await(() -> runtime.started() == 1);
            renderer.warmUp(job(JULIA, 640, 400, 300), JULIA.coloring());
            // A waiting frame can still be cancelled before it runs, so it must not replace the warm-up.
            renderer.submit(request(2), listener(renderer, outcomes));
            runtime.setFailure(new IllegalStateException("frame failed"));
            runtime.release();
            assertEquals("failed 1", outcomes.poll(10, TimeUnit.SECONDS));
            await(() -> runtime.warmUps() == 1);
            runtime.release();
            assertEquals("failed 2", outcomes.poll(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void warmUpPixelsComeFromTheCenterOfTheFrameGrid() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        // A square grid of this portrait view would reach real 4.25, outside the FP32 envelope.
        FractalScene portrait = JULIA.withViewport(new Viewport(3.25, 0, 2));
        RenderJob job = job(portrait, 400, 800, 300);
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            assertTrue(renderer.supports(job, portrait.coloring()));
            renderer.warmUp(job, portrait.coloring());
            await(() -> runtime.warmUps() == 1);
            int size = JuliaGpuRenderer.WARM_UP_SIZE;
            RenderGrid frame = RenderGrid.from(job.viewport(), 400, 800);
            float[] axes = runtime.lastWarmUpAxes();
            for (int x = 0; x < size; x++) assertEquals((float) frame.realAt(192 + x), axes[x]);
            for (int y = 0; y < size; y++) assertEquals((float) frame.imaginaryAt(392 + y), axes[size + y]);
        }
    }

    @Test
    void framesSmallerThanTheWarmUpAreWarmedWhole() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            renderer.warmUp(job(JULIA, 10, 6, 300), JULIA.coloring());
            await(() -> runtime.warmUps() == 1);
            assertEquals(10, runtime.lastWarmUpWidth());
            assertEquals(6, runtime.lastWarmUpHeight());
        }
    }

    @Test
    void unsupportedJobsAndUnavailableRuntimesQueueNoWarmUp() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            FractalScene mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);
            renderer.warmUp(job(mandelbrot, 640, 400, 300), mandelbrot.coloring());
            ColoringSettings c = JULIA.coloring();
            ColoringSettings histogram = new ColoringSettings(c.palette(), c.paletteStops(), c.colorScale(), c.offset(),
                    true, c.orbitTrap());
            renderer.warmUp(job(JULIA, 640, 400, 300), histogram);
            // Neighboring pixels of a deep view collapse to one FP32 coordinate.
            FractalScene deep = JULIA.withViewport(new Viewport("0.3", "0.2", "1e-9"));
            renderer.warmUp(job(deep, 640, 400, 300), deep.coloring());
            runtime.setAvailable(false);
            renderer.warmUp(job(JULIA, 640, 400, 300), JULIA.coloring());
            assertTrue(renderer.needsWarmUp(), "nothing was queued");

            runtime.setAvailable(true);
            render(renderer, 1);
            assertEquals(0, runtime.warmUps());
        }
    }

    @Test
    void aClosedRendererQueuesNoWarmUp() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime)) {
            var renderer = new JuliaGpuRenderer(service, Runnable::run);
            renderer.close();
            assertFalse(renderer.needsWarmUp());
            renderer.warmUp(job(JULIA, 640, 400, 300), JULIA.coloring());
            assertEquals(0, runtime.warmUps());
        }
    }

    private static RenderJob job(FractalScene scene, int width, int height, int iterations) {
        return new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), width, height, iterations);
    }

    private static JuliaGpuRenderer.Request request(long sequence) {
        return new JuliaGpuRenderer.Request(sequence, job(JULIA, 64, 48, 100), JULIA.coloring(), true);
    }

    private static void render(JuliaGpuRenderer renderer, long sequence) throws InterruptedException {
        var outcomes = new LinkedBlockingQueue<String>();
        renderer.submit(request(sequence), listener(renderer, outcomes));
        assertEquals("rendered " + sequence, outcomes.poll(10, TimeUnit.SECONDS));
    }

    private static JuliaGpuRenderer.Listener listener(JuliaGpuRenderer renderer, BlockingQueue<String> outcomes) {
        return new JuliaGpuRenderer.Listener() {
            @Override public void rendered(JuliaGpuRenderer.Request request, int[] colors,
                                           JuliaGpuRenderer.FrameTiming timing) {
                renderer.release(colors);
                outcomes.add("rendered " + request.sequence());
            }
            @Override public void unavailable(JuliaGpuRenderer.Request request) {
                outcomes.add("unavailable " + request.sequence());
            }
            @Override public void failed(JuliaGpuRenderer.Request request, Throwable error) {
                outcomes.add("failed " + request.sequence());
            }
        };
    }

    /** Waits for state the worker sets; the warm-up has no listener to report through. */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("Condition not reached within 10 s");
            Thread.sleep(5);
        }
    }
}
