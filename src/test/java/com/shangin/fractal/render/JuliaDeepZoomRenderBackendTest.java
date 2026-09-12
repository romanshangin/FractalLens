package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JuliaDeepZoomRenderBackendTest {
    private static RenderJob job(String real, String imaginary, String scale, OrbitTrap trap) {
        return new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, trap),
                new Viewport(real, imaginary, scale), 5, 5, 300);
    }

    @Test
    void resolvesAdjacentCoordinatesThatCollapseToTheSameDoubleEvenBelowUnderflow() throws Exception {
        for (String scale : new String[]{"1e-30", "1e-80", "1e-400"}) {
            RenderJob job = job("2", "0", scale, OrbitTrap.NONE);
            assertEquals(job.preciseGrid().realAt(0).doubleValue(), job.preciseGrid().realAt(4).doubleValue());
            try (var backend = new JuliaDeepZoomRenderBackend(2)) {
                RenderFrame frame = backend.render(RenderFrame.create(job), () -> false, ignored -> {}, null);
                assertTrue(frame.isComplete());
                assertEquals(1, frame.samplePlane().iterations(10));
                assertEquals(0, frame.samplePlane().iterations(14));
                assertEquals(0, frame.samplePlane().iterations(2),
                        "A tiny imaginary component places z = 2 + iy outside the radius");
            }
        }
    }

    @Test
    void matchesIndependentHigherPrecisionOracleIncludingTraps() throws Exception {
        for (OrbitTrap trap : OrbitTrap.values()) {
            RenderJob job = job("-0.1", "-0.45", "1e-35", trap);
            try (var backend = new JuliaDeepZoomRenderBackend(2)) {
                RenderFrame frame = backend.render(RenderFrame.create(job), () -> false, ignored -> {}, null);
                var grid = job.preciseGrid();
                for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) {
                    FractalSample expected = oracle(grid.realAt(x), grid.imaginaryAt(y), job, trap);
                    int index = y * 5 + x;
                    assertEquals(expected.iterations(), frame.samplePlane().iterations(index));
                    assertEquals(expected.escaped(), frame.samplePlane().escaped(index));
                    assertEquals(expected.smoothIterations(), frame.samplePlane().smoothIterations(index), 1e-10);
                    assertEquals(expected.orbitTrapDistance(), frame.samplePlane().orbitTrapDistance(index), 1e-12);
                }
            }
        }
    }

    @Test
    void preciseAaSamplerPreservesJuliaInitialStateAndIterationCap() {
        RenderJob job = job("2", "0", "1e-80", OrbitTrap.NONE);
        var sampler = PreciseFractalSampler.create(job, () -> false).orElseThrow();
        assertEquals(0, sampler.sample(new BigDecimal("2.0000000000000000000000000000000000000001"),
                BigDecimal.ZERO, () -> false).iterations());
        assertEquals(1, sampler.sample(new BigDecimal("1.9999999999999999999999999999999999999999"),
                BigDecimal.ZERO, () -> false).iterations());
        assertNull(sampler.sample(BigDecimal.ZERO, BigDecimal.ZERO, () -> true));
        RenderJob capped = new RenderJob(job.formula(), job.viewport(), 5, 5, 1);
        assertFalse(PreciseFractalSampler.create(capped, () -> false).orElseThrow()
                .sample(BigDecimal.valueOf(2), BigDecimal.ZERO, () -> false).escaped());
    }

    @Test
    void cancellationDoesNotPublishAnIncompleteSpanAndReuseSkipsReadyPixels() throws Exception {
        RenderJob job = job("2", "0", "1e-80", OrbitTrap.NONE);
        try (var backend = new JuliaDeepZoomRenderBackend(1)) {
            RenderFrame frame = RenderFrame.create(job);
            AtomicInteger checks = new AtomicInteger();
            backend.render(frame, () -> checks.incrementAndGet() > 6,
                    ignored -> fail("Cancelled first span must not be published"), null);
            assertFalse(frame.isComplete());
            AtomicInteger published = new AtomicInteger();
            backend.render(frame, () -> false, ignored -> published.incrementAndGet(), null);
            assertTrue(frame.isComplete());
            assertTrue(published.get() > 0);
            backend.render(frame, () -> false, ignored -> fail("Ready pixels must be reused"), null);
        }
    }

    @Test
    void selectorRoutesJuliaAndKeepsUnsupportedFormulasRejected() {
        try (var julia = new JuliaDeepZoomRenderBackend(1);
             var selector = new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(),
                     new PrecisionSelectingRenderBackend(new MandelbrotPerturbationRenderBackend(1), julia))) {
            RenderJob deep = job("0.1", "0.2", "1e-80", OrbitTrap.NONE);
            assertTrue(selector.supports(deep));
            assertThrows(IllegalArgumentException.class, () -> selector.select(new RenderJob(
                    FormulaDefinition.forPreset(FractalPreset.TRICORN, OrbitTrap.NONE),
                    deep.viewport(), 5, 5, 300)));
        }
    }

    @Test
    void defaultServiceRendersJuliaAndHonorsShiftedFrameGrid() throws Exception {
        RenderJob job = job("2", "0", "1e-80", OrbitTrap.NONE);
        RenderGrid shifted = RenderGrid.from(job.viewport(), 5, 5).shifted(new PixelShift(1, 0));
        RenderFrame frame = RenderFrame.create(job, shifted);
        var completed = new java.util.concurrent.CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var service = new FractalRenderService()) {
            service.render(frame, Runnable::run, ignored -> {}, ignored -> completed.countDown(),
                    failure -> { error.set(failure); completed.countDown(); });
            assertTrue(completed.await(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertNull(error.get());
        assertTrue(frame.isComplete());
        for (int x = 0; x < 5; x++) {
            assertEquals(oracle(shifted.preciseGrid().realAt(x), shifted.preciseGrid().imaginaryAt(2),
                    job, OrbitTrap.NONE).iterations(), frame.samplePlane().iterations(10 + x));
        }
    }

    @Test
    void sharesReferenceWorkAcrossThousandsOfDeepPixels() {
        RenderJob job = new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE),
                new Viewport("-0.035", "-0.493", "1e-16"), 96, 64, 3000);
        var grid = job.preciseGrid();
        var sampler = JuliaReferenceSampler.create(job, grid, () -> false);
        assertNotNull(sampler);
        for (int y = 0; y < job.height(); y++) for (int x = 0; x < job.width(); x++) {
            assertNotNull(sampler.sample(grid.realAt(x), grid.imaginaryAt(y), () -> false));
        }
        assertEquals(1, sampler.referenceBuildCount(), "A coherent deep frame needs one exact orbit");
        assertEquals(0, sampler.fallbackPixelCount(), "Do not restore BigDecimal iteration per pixel");
    }

    @Test
    void perturbationMatchesOracleAcrossRepellingFixedPointAndTraps() {
        for (String scale : new String[]{"1e-15", "1e-30", "1e-80"}) {
            for (OrbitTrap trap : OrbitTrap.values()) {
                var job = new RenderJob(FormulaDefinition.forPreset(FractalPreset.JULIA, trap),
                        new Viewport(
                                "1.527503118643534632274607931351916169475312417511732493905728256658568636525695786020421879731335118",
                                "-0.0759121783522878653764568658687429427997344025257309526545062431394591595504218065971744205889118132",
                                scale), 12, 8, 1000);
                var grid = job.preciseGrid();
                var sampler = JuliaReferenceSampler.create(job, grid, () -> false);
                assertNotNull(sampler);
                for (int y = 0; y < 8; y++) for (int x = 0; x < 12; x++) {
                    var expected = oracle(grid.realAt(x), grid.imaginaryAt(y), job, trap);
                    var actual = sampler.sample(grid.realAt(x), grid.imaginaryAt(y), () -> false);
                    assertNotNull(actual);
                    assertEquals(expected.iterations(), actual.iterations(), scale + " at " + x + "," + y);
                    assertEquals(expected.escaped(), actual.escaped());
                    assertEquals(expected.smoothIterations(), actual.smoothIterations(), 1e-10);
                    assertEquals(expected.orbitTrapDistance(), actual.orbitTrapDistance(), 1e-12);
                }
            }
        }
    }

    @Test
    void reportedCriticalPointViewSelectsDeepRenderingAndBoundsArithmeticPrecision() throws Exception {
        var job = ReportedJuliaFixture.job(2600, 1675);
        assertTrue(job.viewport().center().real().precision() > 9000);
        assertTrue(job.viewport().hasSufficientPrecision(job.width(), job.height(),
                FractalPreset.JULIA.minimumUlpsPerPixel()), "The old coordinate-only gate missed this case");
        try (var direct = new DirectDoubleRenderBackend()) {
            assertFalse(direct.supports(job), "Critical-point orbit precision requires Julia deep zoom");
        }
        var grid = job.preciseGrid();
        assertTrue(grid.mathContext().getPrecision() < 64,
                "9,000 copied digits must not turn every orbit step into 9,000-digit arithmetic");
        var sampler = JuliaReferenceSampler.create(job, grid, () -> false);
        assertNotNull(sampler);
        for (int y = 0; y < job.height(); y += 137) for (int x = 0; x < job.width(); x += 173) {
            var expected = oracle(grid.realAt(x), grid.imaginaryAt(y), job, OrbitTrap.NONE);
            var actual = sampler.sample(grid.realAt(x), grid.imaginaryAt(y), () -> false);
            assertNotNull(actual);
            assertEquals(expected.iterations(), actual.iterations());
            assertEquals(expected.escaped(), actual.escaped());
            assertEquals(expected.smoothIterations(), actual.smoothIterations(), 1e-10);
        }
        assertEquals(1, sampler.referenceBuildCount(), "The reported scene must share its reference orbit");
        assertEquals(0, sampler.fallbackPixelCount(), "Avoid per-pixel BigDecimal recovery in the reported view");
    }

    private static FractalSample oracle(BigDecimal r, BigDecimal i, RenderJob job, OrbitTrap trap) {
        MathContext mc = new MathContext(job.preciseGrid().mathContext().getPrecision() * 2);
        BigDecimal cr = new BigDecimal(-0.8), ci = new BigDecimal(0.156);
        int n = 0;
        double distance = Double.POSITIVE_INFINITY;
        while (r.pow(2, mc).add(i.pow(2, mc), mc).compareTo(BigDecimal.valueOf(4)) <= 0
                && n < job.maxIterations()) {
            BigDecimal nextR = r.pow(2, mc).subtract(i.pow(2, mc), mc).add(cr, mc);
            i = r.multiply(i, mc).multiply(BigDecimal.valueOf(2), mc).add(ci, mc);
            r = nextR;
            if (trap != OrbitTrap.NONE) distance = Math.min(distance, trap.distance(r.doubleValue(), i.doubleValue()));
            n++;
        }
        return new FractalSample(n, n < job.maxIterations(), r.doubleValue(), i.doubleValue(), 2,
                Double.isFinite(distance) ? distance : Double.NaN);
    }
}
