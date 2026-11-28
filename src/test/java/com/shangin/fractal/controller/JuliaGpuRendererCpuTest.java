package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.FakeJuliaPreviewRuntime;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.JuliaCpuPreview;
import com.shangin.fractal.render.RenderBackend;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.TileTimingStats;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.FractalScene;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** CPU preview frames of Explore Julia Parameters: resolution policy, timing and cancellation. */
class JuliaGpuRendererCpuTest {
    private static final FractalScene JULIA = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE);

    @Test
    void scaleIsTheFinestThatFitsTheFrameBudget() {
        // 3360x2000 at 20 ns per sample: 6.7 M samples cost 134 ms, scale 3 (1120x667) 15 ms.
        assertEquals(3, JuliaGpuRenderer.cpuScale(3360, 2000, 20));
        // Cheap samples render at full resolution.
        assertEquals(1, JuliaGpuRenderer.cpuScale(80, 60, 20));
        double budgetPerSample = (double) JuliaGpuRenderer.CPU_FRAME_BUDGET_NANOS / (3360 * 2000);
        assertEquals(1, JuliaGpuRenderer.cpuScale(3360, 2000, budgetPerSample));
        assertEquals(2, JuliaGpuRenderer.cpuScale(3360, 2000, budgetPerSample * 1.01));
        // Never coarser than the largest scale, however slow the samples.
        assertEquals(JuliaCpuPreview.MAX_SCALE, JuliaGpuRenderer.cpuScale(3360, 2000, 1e9));
        for (double cost = 1; cost < 1e4; cost *= 1.7) {
            int scale = JuliaGpuRenderer.cpuScale(3360, 2000, cost);
            double samples = (double) JuliaCpuPreview.coarseLength(3360, scale) * JuliaCpuPreview.coarseLength(2000, scale);
            if (scale < JuliaCpuPreview.MAX_SCALE) {
                assertTrue(samples * cost <= JuliaGpuRenderer.CPU_FRAME_BUDGET_NANOS, "scale " + scale + " fits");
            }
            if (scale > 1) {
                double finer = (double) JuliaCpuPreview.coarseLength(3360, scale - 1)
                        * JuliaCpuPreview.coarseLength(2000, scale - 1);
                assertTrue(finer * cost > JuliaGpuRenderer.CPU_FRAME_BUDGET_NANOS, "scale " + (scale - 1) + " would not");
            }
        }
    }

    @Test
    void cpuFramesRenderWithoutTheGpuAndReportTheirScale() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        runtime.setAvailable(false);
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run)) {
            RenderJob job = job(64, 48);
            assertFalse(renderer.supports(job, JULIA.coloring()));
            assertTrue(renderer.supportsCpu(job, JULIA.coloring()));
            ColoringSettings histogram = new ColoringSettings(PalettePreset.ICE, PalettePreset.ICE.stops(),
                    JULIA.coloring().colorScale(), JULIA.coloring().offset(), true, OrbitTrap.NONE);
            assertFalse(renderer.supportsCpu(job, histogram));
            ColoringSettings trap = new ColoringSettings(PalettePreset.ICE, PalettePreset.ICE.stops(),
                    JULIA.coloring().colorScale(), JULIA.coloring().offset(), false, OrbitTrap.POINT);
            assertFalse(renderer.supportsCpu(job, trap));

            var frame = render(renderer, job, 1);
            assertNull(frame.timing().gpu());
            assertEquals(1, frame.timing().cpuScale(), "A small frame fits the budget at full resolution");
            assertTrue(frame.timing().cpuSampleNanos() > 0);
            assertTrue(frame.timing().callNanos() >= frame.timing().cpuSampleNanos());
            int[] expected = new int[64 * 48];
            try (var preview = new JuliaCpuPreview()) {
                preview.render(job, 1, JULIA.coloring().createStrategy(), true, expected, () -> false);
            }
            assertArrayEquals(expected, frame.colors());
            assertEquals(0, runtime.calls(), "CPU frames never reach the GPU runtime");
        }
    }

    @Test
    void cancellingStopsARunningCpuFrameAndClosingClosesThePreview() throws Exception {
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var closed = new AtomicBoolean();
        RenderBackend gated = new RenderBackend() {
            private final DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
            @Override public boolean supports(RenderJob job) { return direct.supports(job); }
            @Override public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                                                Consumer<RenderRegion> regionCompleted,
                                                Consumer<TileTimingStats> timingCompleted) throws InterruptedException {
                started.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!cancelled.getAsBoolean()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("The frame was not cancelled");
                    Thread.onSpinWait();
                }
                stopped.countDown();
                return frame;
            }
            @Override public void close() { closed.set(true); direct.close(); }
        };
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime)) {
            var renderer = new JuliaGpuRenderer(service, Runnable::run, new JuliaCpuPreview(gated));
            var outcome = new CompletableFuture<String>();
            renderer.submit(new JuliaGpuRenderer.Request(1, job(64, 48), JULIA.coloring(), true, true), listener(outcome));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            renderer.cancelPending();
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
            // Close waits for the stopped frame, then closes the preview's backend.
            var afterClose = new CountDownLatch(1);
            renderer.close(afterClose::countDown);
            assertTrue(afterClose.await(10, TimeUnit.SECONDS));
            assertTrue(closed.get());
            assertFalse(outcome.isDone(), "A cancelled CPU frame reports nothing");
        }
    }

    @Test
    void aFrameSubmittedAfterCancellationRendersWhileTheCancelledFrameStops() throws Exception {
        var first = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        RenderBackend gated = new RenderBackend() {
            private final DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
            @Override public boolean supports(RenderJob job) { return direct.supports(job); }
            @Override public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                                                Consumer<RenderRegion> regionCompleted,
                                                Consumer<TileTimingStats> timingCompleted) throws InterruptedException {
                if (calls.incrementAndGet() == 1) {
                    // The first frame keeps the worker busy until the test has cancelled and resubmitted.
                    first.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    assertTrue(cancelled.getAsBoolean(), "The first frame was cancelled");
                    return frame;
                }
                assertFalse(cancelled.getAsBoolean(), "A frame submitted after cancelPending is not cancelled by it");
                return direct.render(frame, cancelled, regionCompleted, timingCompleted);
            }
            @Override public void close() { direct.close(); }
        };
        var runtime = new FakeJuliaPreviewRuntime();
        try (var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
             var renderer = new JuliaGpuRenderer(service, Runnable::run, new JuliaCpuPreview(gated))) {
            var cancelled = new CompletableFuture<String>();
            renderer.submit(new JuliaGpuRenderer.Request(1, job(64, 48), JULIA.coloring(), true, true), listener(cancelled));
            assertTrue(first.await(10, TimeUnit.SECONDS));
            // As the controller does when a newer map point replaces the frame in flight.
            renderer.cancelPending();
            var next = new CompletableFuture<String>();
            renderer.submit(new JuliaGpuRenderer.Request(2, job(64, 48), JULIA.coloring(), true, true), listener(next));
            release.countDown();
            assertEquals("rendered", next.get(10, TimeUnit.SECONDS));
            assertFalse(cancelled.isDone(), "The cancelled frame reports nothing");
        }
    }

    private record Frame(JuliaGpuRenderer.FrameTiming timing, int[] colors) {}

    private static RenderJob job(int width, int height) {
        return new RenderJob(FormulaDefinition.forScene(JULIA), JULIA.viewport(), width, height, 100);
    }

    private static Frame render(JuliaGpuRenderer renderer, RenderJob job, long sequence) throws Exception {
        var result = new CompletableFuture<Frame>();
        renderer.submit(new JuliaGpuRenderer.Request(sequence, job, JULIA.coloring(), true, true),
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

    private static JuliaGpuRenderer.Listener listener(CompletableFuture<String> outcome) {
        return new JuliaGpuRenderer.Listener() {
            @Override public void rendered(JuliaGpuRenderer.Request request, int[] colors,
                                           JuliaGpuRenderer.FrameTiming timing) { outcome.complete("rendered"); }
            @Override public void unavailable(JuliaGpuRenderer.Request request) { outcome.complete("unavailable"); }
            @Override public void failed(JuliaGpuRenderer.Request request, Throwable error) { outcome.complete("failed"); }
        };
    }
}
