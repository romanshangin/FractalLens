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
    void reportedDeepZoomMatchesHighPrecision() throws Exception {
        // User report: zoom ~9.19e19. Retain >40 guard digits beyond the pixel scale.
        Viewport viewport = new Viewport(
                "-1.7320499354826012102266901746994696566373363854765666418862854899626008",
                "-0.000000000000000000073960377089232188358962638032921973873012811454845762766",
                new BigDecimal("2.4").divide(new BigDecimal("91931147197833409032.468797100544316727867315464397353051"), new MathContext(80)).toString());
        int limit = new com.shangin.fractal.scene.IterationSettings().maxIterations(
                FractalPreset.MANDELBROT.defaultViewport().scaleExact(), viewport.scaleExact());
        assertTrue(limit > 3000);
        RenderFrame frame = assertBlaMatchesControls(viewport, limit);
        boolean needsMoreThanInitialBudget = false;
        for (int index = 0; index < frame.samplePlane().size(); index++) {
            assertTrue(frame.samplePlane().escaped(index),
                    "The reported view's filaments must not become false black interior");
            needsMoreThanInitialBudget |= frame.samplePlane().iterations(index) > 300;
        }
        assertTrue(needsMoreThanInitialBudget,
                "This fixture must expose the frozen 300-iteration budget regression");
    }

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
        // Directly above and below c=2 the linear term cancels and |z|^2 - 4 is the square of the
        // delta, far below the rounding bound of the stored reference: only those two pixels
        // need exact iteration, which escapes them on the first iteration.
        assertEquals(1, frame.samplePlane().iterations(2));
        assertEquals(1, frame.samplePlane().iterations(2 * job.width() + 2));
        assertEquals(2, diagnostics.get().highPrecisionFallbackPixelCount(),
                "scaled perturbation should decide every other pixel");
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
        long orbitBytes = 5L * (first.maxIterations() + 1) * Double.BYTES;

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
        for (int y : List.of(0, 6, 12, 18, 24)) {
            for (int x : List.of(0, 8, 16, 24, 32)) {
                int index = y * job.width() + x;
                HighPrecisionSample expected = highPrecisionSample(
                        grid.realAt(x), grid.imaginaryAt(y),
                        grid.mathContext(), job.maxIterations());
                assertEquals(expected.iterations(), frame.samplePlane().iterations(index),
                        "iteration mismatch at " + x + "," + y);
                assertEquals(expected.escaped(), frame.samplePlane().escaped(index),
                        "escape mismatch at " + x + "," + y);
                assertEquals(expected.smoothIterations(),
                        frame.samplePlane().smoothIterations(index), 1e-6,
                        "smooth iteration mismatch at " + x + "," + y);
            }
        }
        assertNotNull(diagnostics.get());
        assertTrue(diagnostics.get().modifiedRebaseCount() > 0);
        assertEquals(0, diagnostics.get().additionalReferenceOrbitCount());
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
            assertEquals(expected.smoothIterations(), actual.smoothIterations(), 1e-6,
                    "smooth iteration mismatch at subpixel " + point[0] + "," + point[1]);
        }
    }

    @Test
    void preciseSamplerDoesNotTreatAnUnderflowedSubpixelOffsetAsTheReference() {
        // A third of a pixel is below the smallest double, so the offset from the reference
        // converts to 0.0 although the sample is not the reference.
        FractalSample[] samples = subpixelSampleAgainstHighPrecision("1e-330", "8e-330", true);
        assertEquals(samples[0].escaped(), samples[1].escaped());
        assertEquals(samples[0].iterations(), samples[1].iterations());
    }

    @Test
    void preciseSamplerDoesNotDecideAnUncertainScaledEscapeAtTheAntennaTip() {
        // The sample is exactly c = -2, whose orbit 0, -2, 2, 2, ... stays on the radius; the
        // scaled-delta margin cancels to a rounding residue.
        for (String[] frame : List.of(
                new String[]{"3e-330", "18e-330"},
                // The offset is subnormal rather than zero, so the delta starts much larger than
                // its first error term.
                new String[]{"2e-323", "12e-323"})) {
            FractalSample[] samples = subpixelSampleAgainstHighPrecision(frame[0], frame[1], false);
            assertFalse(samples[0].escaped(), frame[1]);
            assertEquals(samples[0].escaped(), samples[1].escaped(), frame[1]);
            assertEquals(samples[0].iterations(), samples[1].iterations(), frame[1]);
        }
    }

    @Test
    void scaledErrorBoundStaysTrackedWhileItsAbsoluteErrorIsInformative() {
        // Beyond double range every pixel uses the scaled loop; on this orbit the bound relative
        // to the delta exceeds the double range long before the absolute error matters.
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-0.8317528516858322713653476366999",
                        "0.207813754242134522471317257011028", "1e-320"),
                12, 8, 2_700);
        for (int y = 0; y < job.height(); y += 3) {
            for (int x = 0; x < job.width(); x += 3) {
                double[] loss = MandelbrotPerturbationRenderBackend.scaledErrorBoundLoss(job, x, y);
                if (!Double.isNaN(loss[0])) {
                    assertTrue(Double.isFinite(loss[1]) && loss[1] > 1e-12,
                            "bound dropped at iteration " + loss[0] + " with absolute error "
                                    + loss[1] + " at " + x + "," + y);
                }
            }
        }
    }

    /**
     * Samples a third of a pixel right of the centre of a 3x3 frame at {@code -2 - offset}
     * and returns the exact sample and the sampler's.
     */
    private static FractalSample[] subpixelSampleAgainstHighPrecision(
            String offset, String scale, boolean offsetRoundsToZero) {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport(new BigDecimal("-2").subtract(new BigDecimal(offset)).toPlainString(),
                        "0", scale),
                3, 3, 16);
        PreciseRenderGrid grid = job.preciseGrid();
        MandelbrotPerturbationRenderBackend.PreciseSampler sampler =
                MandelbrotPerturbationRenderBackend.createPreciseSampler(
                        job, () -> false).orElseThrow();
        BigDecimal cReal = grid.realAt(1).add(grid.realStep().divide(
                BigDecimal.valueOf(3), grid.mathContext()), grid.mathContext());
        BigDecimal cImaginary = grid.imaginaryAt(1);
        double offsetFromCentre = cReal.subtract(grid.realAt(1), grid.mathContext()).doubleValue();
        assertTrue(Math.ulp(offsetFromCentre) * 16.0 > grid.realStep().doubleValue(),
                "the offset must lose pixel resolution as a double");
        if (offsetRoundsToZero) {
            assertEquals(0.0, offsetFromCentre);
        }

        FractalSample actual = sampler.sample(cReal, cImaginary, () -> false);
        HighPrecisionSample expected = highPrecisionSample(
                cReal, cImaginary, grid.mathContext(), job.maxIterations());

        assertNotNull(actual);
        return new FractalSample[]{
                new FractalSample(expected.iterations(), expected.escaped(), 0.0, 0.0), actual};
    }

    @Test
    void preciseSamplesDoNotDependOnSamplingOrderWhenGlitchesNeedRecoveryReferences() {
        // Along the column through the antenna tip c = -2, escape decisions at the first
        // iterations are too close to the radius for the primary reference, so those samples
        // try the recovery references of their lattice points. Rows avoid the real axis.
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-2", "0", "1e-30"), 97, 64, 2_688);
        PreciseRenderGrid grid = job.preciseGrid();
        List<BigDecimal[]> points = new ArrayList<>();
        for (int y = 0; y < job.height(); y++) {
            for (int x = 0; x < job.width(); x++) {
                points.add(new BigDecimal[]{grid.realAt(x), grid.imaginaryAt(y)});
            }
        }
        MandelbrotPerturbationRenderBackend.PreciseSampler forward =
                MandelbrotPerturbationRenderBackend.createPreciseSampler(job, () -> false).orElseThrow();
        MandelbrotPerturbationRenderBackend.PreciseSampler backward =
                MandelbrotPerturbationRenderBackend.createPreciseSampler(job, () -> false).orElseThrow();
        FractalSample[] forwardSamples = new FractalSample[points.size()];
        for (int index = 0; index < points.size(); index++) {
            forwardSamples[index] = forward.sample(points.get(index)[0], points.get(index)[1], () -> false);
        }
        for (int index = points.size() - 1; index >= 0; index--) {
            assertEquals(forwardSamples[index],
                    backward.sample(points.get(index)[0], points.get(index)[1], () -> false),
                    "sample " + index + " must not depend on which samples were taken first");
        }
        assertTrue(forward.recoveryReferenceBuildCount() > 1,
                "the fixture must exercise recovery references");

        for (int index = 0; index < points.size(); index += 397) {
            HighPrecisionSample expected = highPrecisionSample(
                    points.get(index)[0], points.get(index)[1], grid.mathContext(), job.maxIterations());
            assertEquals(expected.iterations(), forwardSamples[index].iterations(), "pixel " + index);
            assertEquals(expected.escaped(), forwardSamples[index].escaped(), "pixel " + index);
            assertEquals(expected.smoothIterations(), forwardSamples[index].smoothIterations(), 1e-6,
                    "pixel " + index);
        }
    }

    @Test
    void baseSamplesDoNotDependOnTileOrderWhenGlitchesNeedRecoveryReferences() throws Exception {
        // As in the precise sampler test, the column through c = -2 needs recovery references.
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-2", "0", "1e-30"), 161, 96, 2_688);
        RenderRegion top = new RenderRegion(0, 0, job.width(), job.height() / 2);
        RenderRegion bottom = new RenderRegion(0, job.height() / 2, job.width(), job.height() - job.height() / 2);
        RenderFrame forward = RenderFrame.create(job);
        RenderFrame reordered = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();

        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(1, diagnostics::set)) {
            backend.render(forward, () -> false, ignored -> {}, null);
            assertTrue(forward.isComplete());
            assertTrue(diagnostics.get().additionalReferenceOrbitCount() > 1,
                    "the fixture must exercise recovery references");

            // Calculate the bottom half first, then the top half, each in its own generation.
            reordered.validity().markReady(top);
            backend.render(reordered, () -> false, ignored -> {}, null);
            reordered.validity().clear();
            reordered.validity().markReady(bottom);
            backend.render(reordered, () -> false, ignored -> {}, null);
            assertTrue(reordered.isComplete());
        }
        try (MandelbrotPerturbationRenderBackend parallel = new MandelbrotPerturbationRenderBackend(4)) {
            RenderFrame concurrent = RenderFrame.create(job);
            parallel.render(concurrent, () -> false, ignored -> {}, null);
            assertSameSamples(forward, concurrent, "multi-worker");
        }
        assertSameSamples(forward, reordered, "reordered");

        assertMatchesHighPrecision(job, forward, "forward");
    }

    @Test
    void escapeDecisionsWithinRoundingOfTheRadiusMatchHighPrecision() throws Exception {
        // The escape radius crosses this frame: near x = 90, |z|^2 of the first iterations lies
        // within double rounding of 4, so recovered pixels must not trust a perturbed decision.
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-2.000000000000001", "0", "7e-15"), 161, 97, 2_688);
        for (int workers : new int[]{1, 4}) {
            RenderFrame frame = RenderFrame.create(job);
            try (MandelbrotPerturbationRenderBackend backend = new MandelbrotPerturbationRenderBackend(workers)) {
                backend.render(frame, () -> false, ignored -> {}, null);
            }
            assertTrue(frame.isComplete());
            assertMatchesHighPrecision(job, frame, workers + " workers");
        }
    }

    @Test
    void referenceEscapingWithinRoundingOfTheRadiusMatchesHighPrecision() throws Exception {
        // The reference pixel is c = -2 - 2e-59 i: |c|^2 exceeds 4 only by 4e-118, far below the
        // precision of |c|^2, so it escapes at the first iteration although a rounded |Z|^2 is 4.
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-2", "0", "1e-14"), 161, 97, 2_688);
        RenderFrame frame = RenderFrame.create(job);
        try (MandelbrotPerturbationRenderBackend backend = new MandelbrotPerturbationRenderBackend(4)) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }
        assertTrue(frame.isComplete());
        int reference = 48 * job.width() + 80;
        assertTrue(frame.samplePlane().escaped(reference));
        assertEquals(1, frame.samplePlane().iterations(reference));
        assertMatchesHighPrecisionOffTheChaoticAxis(job, frame, "reference tip");
    }

    @Test
    void escapeDecisionsNextToAReferenceOnTheRadiusMatchHighPrecision() throws Exception {
        // The reference is exactly c = -2, whose orbit 0, -2, 2, 2, ... stays on the radius. Above
        // it, c = -2 + k 1e-32 i escapes at the first iteration by k^2 1e-64, which a perturbed
        // |z|^2 of 4 cannot show, and BLA blocks, which skip the escape test, must not apply.
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-2", "0", "1e-30"), 41, 101, 2_688);
        RenderFrame frame = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(4, diagnostics::set)) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }
        assertTrue(frame.isComplete());
        assertEquals(0, diagnostics.get().blaStepCount());
        assertEquals(1, frame.samplePlane().iterations(10 * job.width() + 20));
        assertMatchesHighPrecisionOffTheChaoticAxis(job, frame, "reference on the radius");
    }

    @Test
    void blaEscapeDecisionsWithinRoundingOfTheRadiusMatchHighPrecision() throws Exception {
        // Each centre puts pixel (x, 5) where |z|^2 differs from 4 by 1e-20 at its last iteration,
        // above it in the first view and below it in the second: far below the rounding of a
        // perturbed |z|^2, far above that of exact iteration. Both views use BLA; their error
        // bounds at escape are several 1e-12.
        String[][] views = {
                {"0.0000000000000000000000000000001787798918960168729040299179319238425410726280037302416972418404709350",
                        "4", "83", "true"},
                {"0.0000000000000000000000000000003787798918960168729050803832839886066470982441966061203723867095301584",
                        "2", "84", "true"},
        };
        for (String[] view : views) {
            RenderJob job = new RenderJob(
                    FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                    new Viewport(view[0], "1", "1e-30"), 21, 11, 137);
            for (boolean blaEnabled : new boolean[]{true, false}) {
                RenderFrame frame = RenderFrame.create(job);
                AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
                try (MandelbrotPerturbationRenderBackend backend = new MandelbrotPerturbationRenderBackend(
                        2, diagnostics::set, MandelbrotPerturbationRenderBackend.DEFAULT_REFERENCE_CACHE_BYTES,
                        blaEnabled)) {
                    backend.render(frame, () -> false, ignored -> {}, null);
                }
                assertTrue(frame.isComplete());
                assertEquals(blaEnabled, diagnostics.get().blaStepCount() > 0);
                int pixel = 5 * job.width() + Integer.parseInt(view[1]);
                String label = (blaEnabled ? "BLA " : "scalar ") + view[1];
                assertEquals(Integer.parseInt(view[2]), frame.samplePlane().iterations(pixel), label);
                assertEquals(Boolean.parseBoolean(view[3]), frame.samplePlane().escaped(pixel), label);
                assertMatchesHighPrecision(job, frame, label);
            }
        }
    }

    @Test
    void recoveryReferencesSettleOnlyDecisionsWithinTheirErrorBound() throws Exception {
        // As above, pixel (9, 5) has |z|^2 = 4 + 1e-20 at iteration 90. After a rebase the primary
        // reference's bound (about 1.5e-8) cannot settle it, so the pixel goes to the recovery
        // reference of its lattice point (here the primary orbit itself), whose fresh pass loses
        // its bound before that iteration: a double decision from it would escape at 91, not 90.
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("-0.000000000000000000000000000000003047029504756957101588867111832821100495914100161372801614232897612160",
                        "1", "1e-30"), 21, 11, 137);
        RenderFrame frame = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(2, diagnostics::set)) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }
        assertTrue(frame.isComplete());
        int pixel = 5 * job.width() + 9;
        assertEquals(90, frame.samplePlane().iterations(pixel));
        assertTrue(frame.samplePlane().escaped(pixel));
        assertTrue(diagnostics.get().highPrecisionFallbackPixelCount() > 0);
        assertMatchesHighPrecision(job, frame, "recovery bound");
    }

    /**
     * Compares every pixel with exact iteration except the real axis right of -2 at the antenna
     * tip, where the orbit is chaotic for thousands of iterations and exact iteration at the grid
     * precision itself differs from iteration at three times that precision.
     */
    private static void assertMatchesHighPrecisionOffTheChaoticAxis(RenderJob job, RenderFrame frame, String label) {
        PreciseRenderGrid grid = job.preciseGrid();
        BigDecimal tip = BigDecimal.valueOf(-2);
        for (int index = 0; index < frame.samplePlane().size(); index++) {
            int x = index % job.width();
            int y = index / job.width();
            if (grid.imaginaryAt(y).abs().compareTo(grid.imaginaryStep().movePointLeft(20)) < 0
                    && grid.realAt(x).compareTo(tip) > 0) {
                continue;
            }
            HighPrecisionSample expected = highPrecisionSample(
                    grid.realAt(x), grid.imaginaryAt(y), grid.mathContext(), job.maxIterations());
            String pixel = label + " pixel (" + x + ", " + y + ")";
            assertEquals(expected.iterations(), frame.samplePlane().iterations(index), pixel);
            assertEquals(expected.escaped(), frame.samplePlane().escaped(index), pixel);
            assertEquals(expected.smoothIterations(), frame.samplePlane().smoothIterations(index), 1e-6, pixel);
        }
    }

    private static void assertMatchesHighPrecision(RenderJob job, RenderFrame frame, String label) {
        PreciseRenderGrid grid = job.preciseGrid();
        for (int index = 0; index < frame.samplePlane().size(); index++) {
            int x = index % job.width();
            int y = index / job.width();
            HighPrecisionSample expected = highPrecisionSample(
                    grid.realAt(x), grid.imaginaryAt(y), grid.mathContext(), job.maxIterations());
            String pixel = label + " pixel (" + x + ", " + y + ")";
            assertEquals(expected.iterations(), frame.samplePlane().iterations(index), pixel);
            assertEquals(expected.escaped(), frame.samplePlane().escaped(index), pixel);
            assertEquals(expected.smoothIterations(), frame.samplePlane().smoothIterations(index), 1e-6, pixel);
        }
    }

    private static void assertSameSamples(RenderFrame expected, RenderFrame actual, String label) {
        for (int index = 0; index < expected.samplePlane().size(); index++) {
            assertEquals(expected.samplePlane().iterations(index), actual.samplePlane().iterations(index),
                    label + " iteration mismatch at " + index);
            assertEquals(expected.samplePlane().escaped(index), actual.samplePlane().escaped(index),
                    label + " escape mismatch at " + index);
            assertEquals(Double.doubleToLongBits(expected.samplePlane().smoothIterations(index)),
                    Double.doubleToLongBits(actual.samplePlane().smoothIterations(index)),
                    label + " smooth mismatch at " + index);
        }
    }

    @Test
    void blaMatchesScalarAndHighPrecisionOnADeepBoundaryWithEscapingAndBoundedPixels() throws Exception {
        RenderFrame frame = assertBlaMatchesControls(new Viewport("0", "1", "1e-30"), 137);
        int escaped = 0;
        for (int index = 0; index < frame.samplePlane().size(); index++) {
            if (frame.samplePlane().escaped(index)) {
                escaped++;
            }
        }
        assertTrue(escaped > 0 && escaped < frame.samplePlane().size());
    }

    @Test
    void blaMatchesScalarAndHighPrecisionOnTheDeepProductionReference() throws Exception {
        assertBlaMatchesControls(new Viewport("-0.8317528516858322713653476366999",
                "0.207813754242134522471317257011028", "1e-30"), 2_700);
    }

    @Test
    void blaSamplerDoesNotApplyViewportRadiiToAnOutOfViewportPoint() {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("0", "1", "1e-30"), 17, 13, 137);
        MandelbrotPerturbationRenderBackend.PreciseSampler sampler =
                MandelbrotPerturbationRenderBackend.createPreciseSampler(
                        job, () -> false).orElseThrow();
        FractalSample sample = sampler.sample(new BigDecimal("0.5"), BigDecimal.ZERO, () -> false);

        assertNotNull(sample);
        assertTrue(sample.escaped());
        assertEquals(5, sample.iterations());
    }

    @Test
    void rebuildsBlaRadiiWhenZoomChangesWhileTheReferenceOrbitIsReused() throws Exception {
        FormulaDefinition formula = FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE);
        RenderJob deep = new RenderJob(formula,
                new Viewport("-0.8317528516858322713653476366999",
                        "0.207813754242134522471317257011028", "1e-30"), 17, 13, 2_700);
        // Zooming out keeps the navigated working precision, so the orbit key still matches.
        RenderJob wider = new RenderJob(formula,
                deep.viewport().zoom(new BigDecimal("1.6e17")), 17, 13, 2_700);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(1, diagnostics::set)) {
            backend.render(RenderFrame.create(deep), () -> false, ignored -> {}, null);
            assertTrue(diagnostics.get().blaStepCount() > 0);
            assertEquals(1, backend.cachedReferenceCount());

            backend.render(RenderFrame.create(wider), () -> false, ignored -> {}, null);
            assertEquals(1, backend.cachedReferenceCount());
            assertEquals(0, diagnostics.get().blaStepCount(),
                    "the wider frame must not use the previous, over-permissive delta bound");
        }
    }

    @Test
    void cancelsABlaAcceleratedGenerationAfterProgressIsPublished() throws Exception {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("0", "1", "1e-30"), 129, 129, 137);
        RenderFrame frame = RenderFrame.create(job);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        try (MandelbrotPerturbationRenderBackend backend =
                     new MandelbrotPerturbationRenderBackend(1, diagnostics::set)) {
            backend.render(frame, cancelled::get, ignored -> cancelled.set(true), null);
        }
        assertTrue(diagnostics.get().blaStepCount() > 0);
        assertTrue(diagnostics.get().cancelledTileCount() > 0);
        assertFalse(frame.isComplete());
    }

    @Test
    void blaSamplerMatchesHighPrecisionAtDeepSubpixelCoordinates() {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport("0", "1", "1e-30"), 17, 13, 137);
        PreciseRenderGrid grid = job.preciseGrid();
        MandelbrotPerturbationRenderBackend.PreciseSampler sampler =
                MandelbrotPerturbationRenderBackend.createPreciseSampler(job, () -> false).orElseThrow();
        BigDecimal cReal = grid.realAt(4).add(
                grid.realStep().multiply(new BigDecimal("0.25"), grid.mathContext()), grid.mathContext());
        BigDecimal cImaginary = grid.imaginaryAt(3).subtract(
                grid.imaginaryStep().multiply(new BigDecimal("0.25"), grid.mathContext()), grid.mathContext());
        FractalSample sample = sampler.sample(cReal, cImaginary, () -> false);
        HighPrecisionSample expected = highPrecisionSample(
                cReal, cImaginary, grid.mathContext(), job.maxIterations());

        assertNotNull(sample);
        assertEquals(expected.iterations(), sample.iterations());
        assertEquals(expected.escaped(), sample.escaped());
        assertEquals(expected.smoothIterations(), sample.smoothIterations(), 1e-6);
    }

    private static RenderFrame assertBlaMatchesControls(Viewport viewport, int maxIterations) throws Exception {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                viewport, 17, 13, maxIterations);
        RenderFrame accelerated = RenderFrame.create(job);
        RenderFrame scalar = RenderFrame.create(job);
        AtomicReference<DeepZoomTimingStats> diagnostics = new AtomicReference<>();
        try (MandelbrotPerturbationRenderBackend bla =
                     new MandelbrotPerturbationRenderBackend(2, diagnostics::set);
             MandelbrotPerturbationRenderBackend baseline =
                     new MandelbrotPerturbationRenderBackend(2, ignored -> {},
                             MandelbrotPerturbationRenderBackend.DEFAULT_REFERENCE_CACHE_BYTES, false)) {
            bla.render(accelerated, () -> false, ignored -> {}, null);
            baseline.render(scalar, () -> false, ignored -> {}, null);
        }
        assertTrue(accelerated.isComplete());
        assertTrue(diagnostics.get().blaStepCount() > 0);
        assertTrue(diagnostics.get().blaSkippedIterationCount() > diagnostics.get().blaStepCount());
        assertEquals(0, diagnostics.get().highPrecisionFallbackPixelCount());
        for (int index = 0; index < scalar.samplePlane().size(); index++) {
            assertEquals(scalar.samplePlane().iterations(index), accelerated.samplePlane().iterations(index),
                    "BLA/scalar iteration mismatch at " + index);
            assertEquals(scalar.samplePlane().escaped(index), accelerated.samplePlane().escaped(index),
                    "BLA/scalar escape mismatch at " + index);
            assertEquals(scalar.samplePlane().smoothIterations(index),
                    accelerated.samplePlane().smoothIterations(index), 1e-6,
                    "BLA/scalar smooth mismatch at " + index);
        }
        PreciseRenderGrid grid = job.preciseGrid();
        for (int y : List.of(0, 6, 12)) {
            for (int x : List.of(0, 8, 16)) {
                int index = y * job.width() + x;
                HighPrecisionSample expected = highPrecisionSample(
                        grid.realAt(x), grid.imaginaryAt(y), grid.mathContext(), maxIterations);
                assertEquals(expected.iterations(), accelerated.samplePlane().iterations(index),
                        "BLA/high-precision iteration mismatch at " + x + "," + y);
                assertEquals(expected.escaped(), accelerated.samplePlane().escaped(index));
                assertEquals(expected.smoothIterations(),
                        accelerated.samplePlane().smoothIterations(index), 1e-6,
                        "BLA/high-precision smooth mismatch at " + x + "," + y);
            }
        }
        return accelerated;
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
            // Normalize zeros, whose scale would otherwise grow every iteration on the real axis.
            zr = nextReal.signum() == 0 ? BigDecimal.ZERO : nextReal;
            zi = nextImaginary.signum() == 0 ? BigDecimal.ZERO : nextImaginary;
            if (zr.multiply(zr, context).add(zi.multiply(zi, context)).compareTo(four) > 0) {
                return new HighPrecisionSample(
                        iteration, true, zr.doubleValue(), zi.doubleValue());
            }
        }
        return new HighPrecisionSample(maxIterations, false, zr.doubleValue(), zi.doubleValue());
    }

    private record HighPrecisionSample(
            int iterations,
            boolean escaped,
            double zr,
            double zi
    ) {
        double smoothIterations() {
            return new FractalSample(iterations, escaped, zr, zi).smoothIterations();
        }
    }

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
