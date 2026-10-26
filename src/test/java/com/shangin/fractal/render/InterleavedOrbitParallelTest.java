package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.MandelbrotFormula;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Scope, exactness and cancellation of the experimental interleaved-orbit switch. */
class InterleavedOrbitParallelTest {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 90;

    @Test
    void acceptsOnlyMandelbrotAndJuliaPresetsWithoutOrbitTraps() {
        assertNotNull(InterleavedOrbitKernel.forDefinition(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE)));
        assertNotNull(InterleavedOrbitKernel.forDefinition(
                FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE)));

        for (FractalPreset preset : FractalPreset.values()) {
            for (OrbitTrap trap : OrbitTrap.values()) {
                boolean supported = trap == OrbitTrap.NONE
                        && (preset == FractalPreset.MANDELBROT || preset == FractalPreset.JULIA);
                assertEquals(supported, InterleavedOrbitKernel.forDefinition(
                                FormulaDefinition.forPreset(preset, trap)) != null,
                        preset + " with " + trap);
            }
        }
        assertNull(InterleavedOrbitKernel.forDefinition(
                FormulaDefinition.custom(new MandelbrotFormula(), OrbitTrap.NONE)));
    }

    @Test
    void switchIsOffByDefault() {
        assertNull(System.getProperty(ParallelFractalCalculator.INTERLEAVED_ORBITS_PROPERTY));
        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(1)) {
            assertNull(calculator.interleavedKernel(
                    FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE)));
        }
    }

    @ParameterizedTest
    @EnumSource(FractalPreset.class)
    void switchedOnFramesMatchScalarFramesForEveryPreset(FractalPreset preset) throws InterruptedException {
        for (Viewport viewport : List.of(
                preset.defaultViewport(),
                new Viewport(-0.743643887037151, 0.13182590420533, 0.024))) {
            RenderJob job = job(preset, viewport, 400);
            FractalData expected = scalarFrame(job);

            try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(4, 32, true)) {
                assertEquals(preset == FractalPreset.MANDELBROT || preset == FractalPreset.JULIA,
                        calculator.interleavedKernel(job.formula()) != null);
                RenderFrame frame = RenderFrame.create(job);
                calculator.calculate(frame, () -> false, ignored -> {});

                assertTrue(frame.isComplete());
                InterleavedOrbitBenchmark.requireIdentical(
                        expected, (FractalData) frame.samplePlane(), preset + " " + viewport);
            }
        }
    }

    @Test
    void switchedOnFrameFillsOnlyMissingSpansOfPartiallyReadyFrame() throws InterruptedException {
        RenderJob job = job(FractalPreset.JULIA, FractalPreset.JULIA.defaultViewport(), 300);
        FractalData expected = scalarFrame(job);
        RenderFrame frame = RenderFrame.create(job);
        RenderRegion reused = new RenderRegion(17, 11, 70, 40);
        frame.samplePlane().copyRegionFrom(expected, 17, 11, 17, 11, 70, 40);
        frame.validity().markReady(reused);

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(3, 32, true)) {
            calculator.calculate(frame, () -> false, ignored -> {});
        }

        assertTrue(frame.isComplete());
        InterleavedOrbitBenchmark.requireIdentical(
                expected, (FractalData) frame.samplePlane(), "partially ready Julia frame");
    }

    @ParameterizedTest
    @EnumSource(value = FractalPreset.class, names = {"MANDELBROT", "JULIA"})
    void switchedOnCancellationMarksOnlyExactPixelsReady(FractalPreset preset) throws InterruptedException {
        RenderJob job = job(preset, new Viewport(-0.743643887037151, 0.13182590420533, 0.024), 2_000);
        FractalData expected = scalarFrame(job);
        RenderFrame frame = RenderFrame.create(job);
        AtomicBoolean cancelled = new AtomicBoolean();

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(4, 32, true)) {
            calculator.calculate(frame, cancelled::get, ignored -> cancelled.set(true));
        }

        assertTrue(cancelled.get(), "At least one region must complete before cancellation");
        assertFalse(frame.isComplete(), "Cancellation must stop the frame early");
        int ready = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (!frame.validity().isReady(x, y)) {
                    continue;
                }
                ready++;
                int index = y * WIDTH + x;
                assertEquals(expected.iterations(index), frame.samplePlane().iterations(index));
                assertEquals(expected.escaped(index), frame.samplePlane().escaped(index));
                assertEquals(Double.doubleToRawLongBits(expected.smoothIterations(index)),
                        Double.doubleToRawLongBits(frame.samplePlane().smoothIterations(index)));
            }
        }
        assertTrue(ready > 0);
    }

    private static RenderJob job(FractalPreset preset, Viewport viewport, int maxIterations) {
        return new RenderJob(FormulaDefinition.forPreset(preset, OrbitTrap.NONE),
                viewport, WIDTH, HEIGHT, maxIterations);
    }

    private static FractalData scalarFrame(RenderJob job) {
        return job.formula().createDirectCalculator().calculate(
                WIDTH, HEIGHT, RenderGrid.from(job.viewport(), WIDTH, HEIGHT), job.maxIterations());
    }
}
