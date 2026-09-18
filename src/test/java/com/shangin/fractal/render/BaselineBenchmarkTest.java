package com.shangin.fractal.render;

import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.formula.FractalPreset;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BaselineBenchmarkTest {
    @Test
    void canonicalManifestPinsCoordinatesIterationCapsAndFeatures() throws Exception {
        StringBuilder expected = new StringBuilder(BaselineFixtures.HEADER).append('\n');
        try (var backend = BaselineBenchmark.newBackend()) {
            for (int[] size : List.of(new int[]{480, 270}, new int[]{1512, 982}, new int[]{3024, 1964})) {
                var matrix = BaselineFixtures.matrix(size[0], size[1]);
                assertEquals(matrix.size(), matrix.stream().map(BaselineFixtures.Fixture::id).distinct().count());
                assertEquals(Set.of(FractalPreset.values()), matrix.stream().flatMap(f -> f.steps().stream())
                        .map(s -> s.job().formula().preset()).collect(java.util.stream.Collectors.toSet()));
                for (var fixture : matrix) for (var step : fixture.steps()) {
                    expected.append(step.csv(fixture.id())).append('\n');
                    assertTrue(backend.supports(step.job()), fixture.id());
                    assertEquals(SampleAccuracy.CPU_REFERENCE, step.job().sampleAccuracy());
                }
                var transition = fixture(matrix, "direct-deep-reverse");
                assertInstanceOf(DirectDoubleRenderBackend.class, backend.select(transition.steps().get(0).job()));
                assertInstanceOf(MandelbrotPerturbationRenderBackend.class, backend.select(transition.steps().get(1).job()));
                assertInstanceOf(DirectDoubleRenderBackend.class, backend.select(transition.steps().get(2).job()));
                assertEquals(2488, fixture(matrix, "deep-glitch").steps().getFirst().job().maxIterations());
                assertEquals(632, fixture(matrix, "seahorse-adaptive").steps().getFirst().job().maxIterations());
            }
        }
        String manifest = Files.readString(Path.of("BASELINE_FIXTURES.csv"))
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        assertEquals(expected.toString(), manifest,
                "An intentional fixture change requires a new matrix version and reviewed manifest");
    }

    @Test
    void navigationReusesExpectedOverlapAndMatchesFreshCalculationOnTheSameGrid() throws Exception {
        var matrix = BaselineFixtures.matrix(96, 66);
        try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
            for (String id : List.of("pan-5", "pan-25", "pan-90", "pan-reverse", "resize-then-drag")) {
                RenderFrame active = null, retained = null;
                int cap = fixture(matrix, id).steps().getFirst().job().maxIterations();
                for (var step : fixture(matrix, id).steps()) {
                    var m = BaselineBenchmark.measure(step, backend, aa, active, retained);
                    assertTrue(m.frame().isComplete());
                    assertEquals(cap, m.frame().job().maxIterations());
                    assertTrue(m.argbTime() >= m.backendTime());
                    if (step.action() == BaselineFixtures.Action.REUSE) {
                        assertTrue(m.reusedPixels() > 0, id + "/" + step.name());
                        assertEquals(m.plan(), m.firstUseful());
                        if (id.startsWith("pan-") && !id.equals("pan-reverse")) {
                            int percent = Integer.parseInt(id.substring(4));
                            assertEquals((96 - Math.max(1, 96 * percent / 100)) * 66, m.reusedPixels());
                        }
                        RenderFrame fresh = RenderFrame.create(step.job(), m.frame().renderGrid());
                        backend.render(fresh, () -> false, ignored -> {}, null);
                        assertEquals(BaselineBenchmark.sampleHash(fresh), BaselineBenchmark.sampleHash(m.frame()), id);
                    }
                    retained = active;
                    active = m.frame();
                }
            }
        }
    }

    @Test
    void reverseZoomRecalculatesCapAndFallsBackToRetainedFrame() throws Exception {
        var sequence = fixture(BaselineFixtures.matrix(96, 66), "direct-deep-reverse").steps();
        try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
            var direct = BaselineBenchmark.measure(sequence.get(0), backend, aa, null, null);
            var deep = BaselineBenchmark.measure(sequence.get(1), backend, aa, direct.frame(), null);
            var reverse = BaselineBenchmark.measure(sequence.get(2), backend, aa, deep.frame(), direct.frame());
            assertNotEquals(deep.frame().job().maxIterations(), reverse.frame().job().maxIterations());
            assertEquals(direct.frame().job().maxIterations(), reverse.frame().job().maxIterations());
            assertEquals("retained", reverse.reuseSource());
            assertEquals(96 * 66, reverse.reusedPixels());
            assertEquals(-1, reverse.firstRegion());
            assertEquals(BaselineBenchmark.sampleHash(direct.frame()), BaselineBenchmark.sampleHash(reverse.frame()));
        }
    }

    @Test
    void cancellationHasAnObservedTriggerAndNeverReportsReturnedArgb() throws Exception {
        var matrix = BaselineFixtures.matrix(96, 66);
        try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
            for (String id : List.of("cancel-direct", "cancel-deep")) {
                var m = BaselineBenchmark.measure(fixture(matrix, id).steps().getFirst(), backend, aa, null, null);
                assertTrue(m.cancelRequest() >= 0);
                assertTrue(m.cancelTail() >= 0);
                assertTrue(m.regions() >= 1);
                assertEquals(-1, m.argbTime());
                assertEquals(-1, m.aaTime());
                assertNull(m.pixels());
            }
        }
    }

    @Test
    void scaledExponentFixturePreservesTheBoundaryBelowDoubleRange() throws Exception {
        var step = fixture(BaselineFixtures.matrix(65, 65), "scaled-exponent").steps().getFirst();
        try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
            var m = BaselineBenchmark.measure(step, backend, aa, null, null);
            assertTrue(m.frame().isComplete());
            int middle = 32 * 65 + 32;
            assertEquals(2, m.frame().samplePlane().iterations(middle));
            assertEquals(1, m.frame().samplePlane().iterations(middle + 1));
        }
    }

    @Test
    void retainedAaMatchesEmptyCacheAndReportsItsPreparationSeparately() throws Exception {
        var matrix = BaselineFixtures.matrix(96, 66);
        try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
            var empty = BaselineBenchmark.measure(fixture(matrix, "julia-aa-regular").steps().getFirst(), backend, aa, null, null);
            var retained = BaselineBenchmark.measure(fixture(matrix, "julia-aa-retained").steps().getFirst(), backend, aa, null, null);
            assertArrayEquals(empty.pixels(), retained.pixels());
            assertTrue(retained.aaPreparation() > 0);
            assertTrue(retained.aaTime() > 0);
            assertTrue(retained.firstAa() >= 0 && retained.firstAa() <= retained.aaTime());
            assertEquals(-1, empty.aaPreparation());
        }
    }

    private static BaselineFixtures.Fixture fixture(List<BaselineFixtures.Fixture> matrix, String id) {
        return matrix.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }
}
