package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.math.BigDecimal;
import java.math.MathContext;

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
    void fallsBackToHighPrecisionWhenPerturbationDeltasUnderflow() throws Exception {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("2", "0", "1e-400"), 5, 3, 5);
        RenderFrame frame = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();

        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(1, diagnostics::set)) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }

        int centerRow = job.width();
        assertEquals(2, frame.samplePlane().iterations(centerRow + 2),
                "the exact c=2 boundary point escapes on the second iteration");
        assertEquals(1, frame.samplePlane().iterations(centerRow + 3),
                "a positive sub-double delta must not collapse back to c=2");
        assertEquals(1, frame.samplePlane().iterations(centerRow + 4),
                "the right edge must retain its precise coordinate");
        assertEquals(0, diagnostics.get().highPrecisionFallbackPixelCount(),
                "scaled perturbation should avoid per-pixel BigDecimal iteration");
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

    @Test
    void retainsOnlyBoundedExactCompatibleReferenceOrbits() throws Exception {
        FormulaDefinition formula = FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE);
        RenderJob first = new RenderJob(formula, new Viewport("0.5", "0", "1e-80"),
                33, 25, 500);
        RenderJob second = new RenderJob(formula, new Viewport("0.6", "0", "1e-80"),
                33, 25, 500);
        long orbitBytes = 4L * (first.maxIterations() + 1) * Double.BYTES;

        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(1, ignored -> {}, orbitBytes)) {
            backend.render(RenderFrame.create(first), () -> false, ignored -> {}, null);
            assertEquals(1, backend.cachedReferenceCount());
            assertEquals(orbitBytes, backend.cachedReferenceBytes());

            backend.render(RenderFrame.create(first), () -> false, ignored -> {}, null);
            assertEquals(1, backend.cachedReferenceCount(), "same precise job reuses its orbit");

            backend.render(RenderFrame.create(second), () -> false, ignored -> {}, null);
            assertEquals(1, backend.cachedReferenceCount(), "LRU evicts rather than exceeding capacity");
            assertTrue(backend.cachedReferenceBytes() <= orbitBytes);
        }
    }

    @Test
    void zoomOutSchedulesExposedTilesBeforeTheReprojectedCenter() {
        RenderRegion previewCenter = new RenderRegion(32, 32, 64, 64);
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("0.5", "0", "1e-80"), 128, 128, 20,
                RenderPriority.center(), java.util.Optional.of(previewCenter));

        List<RenderRegion> tiles = MandelbrotPerturbationRenderBackend.orderedTiles(job);
        boolean seenCoveredTile = false;
        for (RenderRegion tile : tiles) {
            boolean exposed = uncoveredArea(tile, previewCenter) > 0;
            if (!exposed) {
                seenCoveredTile = true;
            }
            assertFalse(exposed && seenCoveredTile,
                    "an exposed tile must not be delayed behind the preview center");
        }
        assertTrue(uncoveredArea(tiles.getFirst(), previewCenter) > 0);
    }

    @Test
    void matchesHighPrecisionControlPointsNearReportedDeepViewport() throws Exception {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-0.8317528516858322713653476366999",
                        "0.207813754242134522471317257011028", "1.6e-13"),
                33, 25, 2_700);
        RenderFrame frame = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();

        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(2, diagnostics::set)) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }

        PreciseRenderGrid grid = job.preciseGrid();
        for (int[] point : List.of(
                new int[]{0, 0}, new int[]{32, 0}, new int[]{16, 12},
                new int[]{0, 24}, new int[]{32, 24})) {
            int index = point[1] * job.width() + point[0];
            HighPrecisionSample expected = highPrecisionSample(
                    grid.realAt(point[0]), grid.imaginaryAt(point[1]),
                    grid.mathContext(), job.maxIterations());
            assertEquals(expected.iterations(), frame.samplePlane().iterations(index),
                    "iteration mismatch at " + point[0] + "," + point[1]);
            assertEquals(expected.escaped(), frame.samplePlane().escaped(index),
                    "escape mismatch at " + point[0] + "," + point[1]);
        }
        assertNotNull(diagnostics.get());
        assertTrue(diagnostics.get().additionalReferenceOrbitCount() > 0);
        assertEquals(0, diagnostics.get().highPrecisionFallbackPixelCount());
    }

    @Test
    void preciseSamplerMatchesHighPrecisionAtDeepSubpixelCoordinates() {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-0.8317528516858322713653476366999",
                        "0.207813754242134522471317257011028", "1.6e-13"),
                33, 25, 2_700);
        PreciseRenderGrid grid = job.preciseGrid();
        MandelbrotPerturbationRenderBackend.PreciseSampler sampler =
                MandelbrotPerturbationRenderBackend.createPreciseSampler(
                        job, () -> false).orElseThrow();

        for (int[] point : List.of(
                new int[]{8, 6}, new int[]{16, 12}, new int[]{24, 18})) {
            BigDecimal cReal = grid.realAt(point[0]).add(
                    grid.realStep().multiply(
                            new BigDecimal("0.25"), grid.mathContext()),
                    grid.mathContext());
            BigDecimal cImaginary = grid.imaginaryAt(point[1]).subtract(
                    grid.imaginaryStep().multiply(
                            new BigDecimal("0.25"), grid.mathContext()),
                    grid.mathContext());

            FractalSample actual = sampler.sample(cReal, cImaginary, () -> false);
            HighPrecisionSample expected = highPrecisionSample(
                    cReal, cImaginary, grid.mathContext(), job.maxIterations());

            assertNotNull(actual);
            assertEquals(expected.iterations(), actual.iterations(),
                    "iteration mismatch at subpixel " + point[0] + "," + point[1]);
            assertEquals(expected.escaped(), actual.escaped(),
                    "escape mismatch at subpixel " + point[0] + "," + point[1]);
        }
    }

    private static HighPrecisionSample highPrecisionSample(
            BigDecimal cReal,
            BigDecimal cImaginary,
            MathContext context,
            int maxIterations
    ) {
        BigDecimal zr = BigDecimal.ZERO;
        BigDecimal zi = BigDecimal.ZERO;
        BigDecimal four = BigDecimal.valueOf(4);
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            BigDecimal nextReal = zr.multiply(zr, context)
                    .subtract(zi.multiply(zi, context), context).add(cReal, context);
            BigDecimal nextImaginary = zr.multiply(zi, context)
                    .multiply(BigDecimal.valueOf(2), context).add(cImaginary, context);
            zr = nextReal;
            zi = nextImaginary;
            if (zr.multiply(zr, context).add(zi.multiply(zi, context)).compareTo(four) > 0) {
                return new HighPrecisionSample(iteration, true);
            }
        }
        return new HighPrecisionSample(maxIterations, false);
    }

    private record HighPrecisionSample(int iterations, boolean escaped) {}

    private static int uncoveredArea(RenderRegion tile, RenderRegion coverage) {
        int overlapWidth = Math.max(0, Math.min(tile.x() + tile.width(), coverage.x() + coverage.width())
                - Math.max(tile.x(), coverage.x()));
        int overlapHeight = Math.max(0, Math.min(tile.y() + tile.height(), coverage.y() + coverage.height())
                - Math.max(tile.y(), coverage.y()));
        return tile.width() * tile.height() - overlapWidth * overlapHeight;
    }

    private static RenderJob job(FractalPreset preset, OrbitTrap trap, Viewport viewport) {
        return new RenderJob(FormulaDefinition.forPreset(preset, trap), viewport,
                33, 25, 500);
    }
}
