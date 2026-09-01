package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MandelbrotPerturbationRenderBackendTest {

    @Test
    void isAvailableOnlyForPlainMandelbrot() {
        MandelbrotPerturbationRenderBackend backend = new MandelbrotPerturbationRenderBackend(1);
        try {
            assertTrue(backend.supports(job(FractalPreset.MANDELBROT, OrbitTrap.NONE,
                    new Viewport(-0.75, 0.0, 2.4))));
            assertFalse(backend.supports(job(FractalPreset.JULIA, OrbitTrap.NONE,
                    new Viewport(0, 0, 2.4))));
            assertFalse(backend.supports(job(FractalPreset.MANDELBROT, OrbitTrap.CROSS,
                    new Viewport(-0.75, 0, 2.4))));
        } finally {
            backend.close();
        }
    }

    @Test
    void matchesDirectMandelbrotSamplesWhileSharingOneReferenceOrbit() throws Exception {
        RenderJob job = job(FractalPreset.MANDELBROT, OrbitTrap.NONE,
                new Viewport(0.5, 0.0, 0.001));
        RenderFrame perturbationFrame = RenderFrame.create(job);
        RenderFrame directFrame = RenderFrame.create(job);
        List<RenderRegion> completed = new ArrayList<>();

        try (MandelbrotPerturbationRenderBackend perturbation =
                     new MandelbrotPerturbationRenderBackend(1);
             DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend()) {
            perturbation.render(perturbationFrame, () -> false, completed::add, null);
            direct.render(directFrame, () -> false, ignored -> {}, null);
        }

        assertTrue(perturbationFrame.isComplete());
        assertFalse(completed.isEmpty());
        for (int index = 0; index < directFrame.samplePlane().size(); index++) {
            assertEquals(directFrame.samplePlane().iterations(index),
                    perturbationFrame.samplePlane().iterations(index));
            assertEquals(directFrame.samplePlane().escaped(index),
                    perturbationFrame.samplePlane().escaped(index));
            assertEquals(directFrame.samplePlane().smoothIterations(index),
                    perturbationFrame.samplePlane().smoothIterations(index), 1e-6);
        }
    }

    @Test
    void cancelsDuringReferenceOrbitConstructionWithoutPublishingPixels() throws Exception {
        RenderJob job = job(FractalPreset.MANDELBROT, OrbitTrap.NONE,
                new Viewport("-0.7436438870371510000000000000000000001",
                        "0.1318259042053300000000000000000000002", "1e-80"));
        RenderFrame frame = RenderFrame.create(job);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        try (MandelbrotPerturbationRenderBackend backend = new MandelbrotPerturbationRenderBackend(1)) {
            backend.render(frame, cancelled::get, ignored -> fail("must not publish after cancellation"), null);
        }

        assertFalse(frame.isComplete());
        assertEquals(0, frame.validity().readyPixelCount());
    }

    @Test
    void rendersAViewportWhosePixelCoordinatesCannotBeRepresentedAsDoubles() throws Exception {
        RenderJob job = job(FractalPreset.MANDELBROT, OrbitTrap.NONE,
                new Viewport("0.5", "0", "1e-80"));
        RenderFrame frame = RenderFrame.create(job);

        try (MandelbrotPerturbationRenderBackend backend = new MandelbrotPerturbationRenderBackend(1)) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }

        try (DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend()) {
            assertFalse(direct.supports(job));
        }
        assertTrue(frame.isComplete());
        for (int index = 0; index < frame.samplePlane().size(); index++) {
            assertTrue(frame.samplePlane().escaped(index));
            assertEquals(5, frame.samplePlane().iterations(index));
        }
    }

    @Test
    void boundsExecutorTasksWhileReportingTileAndIterationDiagnostics() throws Exception {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("0.5", "0", "1e-80"), 65, 65, 500);
        RenderFrame frame = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        AtomicBoolean publishedBeforeCompletion = new AtomicBoolean();

        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(3, diagnostics::set)) {
            backend.render(frame, () -> false, region -> {
                if (!frame.isComplete()) {
                    publishedBeforeCompletion.set(true);
                }
            }, null);
        }

        DeepZoomTimingStats stats = diagnostics.get();
        assertNotNull(stats);
        assertEquals(3, stats.workerTaskCount());
        assertEquals(9, stats.queuedTileCount());
        assertEquals(9, stats.completedTileCount());
        assertEquals(0, stats.cancelledTileCount());
        assertEquals(65L * 65L, stats.calculatedPixelCount());
        assertEquals(5.0, stats.averageIterationsPerPixel());
        assertTrue(stats.referenceOrbitMs() >= 0.0);
        assertTrue(stats.coordinatePreparationMs() >= 0.0);
        assertTrue(stats.timeToFirstRegionMs() >= 0.0);
        assertTrue(publishedBeforeCompletion.get());
    }

    @Test
    void cancellationDropsTheRemainingGenerationLocalTileQueue() throws Exception {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("0.5", "0", "1e-80"), 129, 129, 500);
        RenderFrame frame = RenderFrame.create(job);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();

        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(2, diagnostics::set)) {
            backend.render(frame, cancelled::get, ignored -> cancelled.set(true), null);
        }

        DeepZoomTimingStats stats = diagnostics.get();
        assertNotNull(stats);
        assertEquals(2, stats.workerTaskCount());
        assertEquals(25, stats.queuedTileCount());
        assertTrue(stats.cancelledTileCount() > 0);
        assertTrue(stats.completedTileCount() < stats.queuedTileCount());
        assertFalse(frame.isComplete());
    }

    private static RenderJob job(FractalPreset preset, OrbitTrap trap, Viewport viewport) {
        return new RenderJob(FormulaDefinition.forPreset(preset, trap), viewport,
                33, 25, 500);
    }
}
