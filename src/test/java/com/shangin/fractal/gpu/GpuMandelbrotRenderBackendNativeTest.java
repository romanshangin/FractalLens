package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static com.shangin.fractal.gpu.GpuMandelbrotRenderBackendTest.*;

@EnabledIfSystemProperty(named = "fractal.gpu.nativeSmoke", matches = "true")
class GpuMandelbrotRenderBackendNativeTest {
    @Test
    void crossesIterationAndActualGridSelectionBoundaries() throws Exception {
        if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) return;
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault(); var backend = backend(runtime)) {
            assertTrue(runtime.isUsableFor(GpuNumericCapability.FLOAT32));
            var template = job(new Viewport(1, 1, 0.2), 129, 65);
            for (int limit : new int[]{999, 1000, 1001, 1000}) {
                var request = new RenderJob(template.formula(), template.viewport(), 129, 65, limit,
                        template.priority(), template.approximateCoverage(), template.sampleAccuracy());
                var frame = RenderFrame.create(request);
                backend.render(frame, () -> false, ignored -> {}, null);
                assertEquals(limit > 1000, backend.lastStats().cpuFallback());
                if (limit <= 1000) assertEquals(129 * 65, backend.lastStats().dispatchedPixels());
                assertMatchesCpu(frame, limit > 1000 ? 0 : 0.01);
            }
            // Keep an observed rejected/allowed bracket: float-grid acceptance need not be globally monotonic.
            double rejected = 1e-8, allowed = 0.01;
            assertFalse(allowedGrid(rejected));
            assertTrue(allowedGrid(allowed));
            for (int i = 0; i < 48; i++) {
                double middle = (rejected + allowed) / 2;
                if (allowedGrid(middle)) allowed = middle;
                else rejected = middle;
            }
            for (double scale : new double[]{allowed, rejected, allowed}) {
                var frame = RenderFrame.create(job(new Viewport(-0.743643887037151, 0.13182590420533, scale), 129, 65));
                backend.render(frame, () -> false, ignored -> {}, null);
                boolean fallback = scale == rejected;
                assertEquals(fallback, backend.lastStats().cpuFallback());
                if (!fallback) assertEquals(129 * 65, backend.lastStats().dispatchedPixels());
                assertMatchesCpu(frame, fallback ? 0 : 0.01);
            }
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
            System.out.println("Native selection boundary: rejected=" + rejected + "; allowed=" + allowed);
        }
    }

    private static boolean allowedGrid(double scale) {
        return MandelbrotPrecisionGate.supportsGrid(RenderGrid.from(
                new Viewport(-0.743643887037151, 0.13182590420533, scale), 129, 65), 129, 65);
    }

    @Test
    void rendersCertifiedAndRecoveredSamplesThroughSharedRuntime() throws Exception {
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault(); var backend = backend(runtime)) {
            var frame = RenderFrame.create(job(new Viewport(-0.75, 0, 2.4), 384, 256));
            AtomicInteger progress = new AtomicInteger();
            backend.render(frame, () -> false, region -> {
                assertTrue(frame.validity().isRegionReady(region));
                progress.incrementAndGet();
            }, null);
            assertTrue(frame.isComplete());
            if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) {
                assertTrue(backend.lastStats().cpuFallback());
                assertFalse(runtime.isUsableFor(GpuNumericCapability.FLOAT32));
                assertMatchesCpu(frame, 0);
                return;
            }
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
            assertFalse(backend.lastStats().cpuFallback());
            assertTrue(backend.lastStats().certifiedPixels() > frame.samplePlane().size() * 0.8);
            assertTrue(backend.lastStats().recoveredPixels() > 0);
            assertTrue(progress.get() > 1);
            assertMatchesCpu(frame, 0.01);
            System.out.println("Mandelbrot integrated overview: " + backend.lastStats());
            if (Boolean.getBoolean("fractal.gpu.mandelbrot.profile")) {
                var profile = backend.lastProfile();
                assertEquals(6, profile.batches());
                assertTrue(profile.uploadNanos() > 0);
                assertTrue(profile.readbackNanos() > 0);
                assertTrue(profile.dispatchNanos() > 0);
                assertTrue(profile.kernelNanos() == -1 || profile.kernelNanos() > 0);
                assertTrue(profile.nativeBytes() >= 1_310_720 && profile.nativeBytes() <= 4 * 1024 * 1024);
            }

            // The existing CPU coloring boundary and optional palette kernel consume identical phases.
            var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
            var base = BaseColorPhaseCache.create(frame.samplePlane(), coloring);
            int[] cpuColors = new int[base.size()], gpuColors = new int[base.size()];
            new PaletteRecolorBackend(runtime, false).recolor(base, AntialiasSampleCache.Snapshot.EMPTY, coloring, cpuColors);
            assertTrue(new PaletteRecolorBackend(runtime, true).recolor(base, AntialiasSampleCache.Snapshot.EMPTY, coloring, gpuColors).gpuUsed());
            assertArrayEquals(cpuColors, gpuColors);

            var cache = new RenderFrameCache();
            cache.put(frame);
            assertSame(frame, cache.findExact(frame.job()).orElseThrow());
            var panJob = job(frame.job().viewport().shiftedByPixels(-7, 5, 384, 256), 384, 256);
            var panned = new FrameReusePlanner().createFrame(frame, panJob);
            int missing = panned.samplePlane().size() - panned.validity().readyPixelCount();
            backend.render(panned, () -> false, ignored -> {}, null);
            assertEquals(missing, backend.lastStats().dispatchedPixels());
            assertMatchesCpu(panned, 0.01);

            var boundary = RenderFrame.create(job(new Viewport(-0.743643887037151, 0.13182590420533, 0.024), 128, 96));
            backend.render(boundary, () -> false, ignored -> {}, null);
            assertEquals(boundary.samplePlane().size(), backend.lastStats().recoveredPixels());
            assertMatchesCpu(boundary, 0);

            runtime.handleDeviceLoss(new GpuException("simulated after integrated dispatch", true));
            var fallback = RenderFrame.create(frame.job());
            backend.render(fallback, () -> false, ignored -> {}, null);
            assertTrue(backend.lastStats().cpuFallback());
            assertMatchesCpu(fallback, 0);
        }
        try (GpuRuntime reopened = GpuRuntimeFactory.createDefault(); var backend = backend(reopened)) {
            var frame = RenderFrame.create(job(new Viewport(1, 1, 0.2), 19, 7));
            backend.render(frame, () -> false, ignored -> {}, null);
            assertEquals(frame.samplePlane().size(), backend.lastStats().certifiedPixels());
            assertMatchesCpu(frame, 0.01);
        }
        String previous = System.getProperty("fractal.gpu.mandelbrot.enabled");
        System.setProperty("fractal.gpu.mandelbrot.enabled", "true");
        try (var service = new FractalRenderService()) {
            var frame = RenderFrame.create(job(new Viewport(1, 1, 0.2), 19, 7));
            CompletableFuture<RenderFrame> completed = new CompletableFuture<>();
            service.render(frame, Runnable::run, ignored -> {}, completed::complete, completed::completeExceptionally);
            assertSame(frame, completed.get(5, TimeUnit.SECONDS));
            assertMatchesCpu(frame, 0.01);
            // The default service must really select the opted-in backend, not silently render on CPU.
            var calculator = frame.job().formula().createDirectCalculator();
            boolean differs = false;
            for (int i = 0; i < frame.samplePlane().size(); i++) {
                double expected = calculator.calculateSample(frame.renderGrid().realAt(i % 19),
                        frame.renderGrid().imaginaryAt(i / 19), 300).smoothIterations();
                differs |= expected != frame.samplePlane().smoothIterations(i);
            }
            assertTrue(differs, "Opted-in default service produced only exact CPU samples");
        } finally {
            if (previous == null) System.clearProperty("fractal.gpu.mandelbrot.enabled");
            else System.setProperty("fractal.gpu.mandelbrot.enabled", previous);
        }
    }
}
