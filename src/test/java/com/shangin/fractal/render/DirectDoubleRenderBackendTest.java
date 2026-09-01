package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DirectDoubleRenderBackendTest {

    @Test
    void matchesThePreviousDirectDoubleOutput() throws Exception {
        Viewport viewport = new Viewport(-0.75, 0.0, 2.4);
        FormulaDefinition formula = FormulaDefinition.forPreset(
                FractalPreset.MANDELBROT, OrbitTrap.NONE);
        RenderJob job = new RenderJob(formula, viewport, 48, 32, 300);
        RenderFrame frame = RenderFrame.create(job);
        List<RenderRegion> progress = new ArrayList<>();

        try (DirectDoubleRenderBackend backend = new DirectDoubleRenderBackend()) {
            backend.render(frame, () -> false, progress::add, null);
        }

        FractalData expected = formula.createDirectCalculator().calculate(
                job.width(), job.height(), frame.renderGrid(), job.maxIterations());
        assertTrue(frame.isComplete());
        assertFalse(progress.isEmpty());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.iterations(index), frame.samplePlane().iterations(index));
            assertEquals(expected.smoothIterations(index),
                    frame.samplePlane().smoothIterations(index));
            assertEquals(expected.escaped(index), frame.samplePlane().escaped(index));
            assertEquals(expected.orbitTrapDistance(index),
                    frame.samplePlane().orbitTrapDistance(index));
        }
    }

    @Test
    void rendersIntoBackendProvidedSamplePlane() throws Exception {
        RenderJob job = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                new Viewport(-0.75, 0.0, 2.4), 16, 12, 100);
        TestSamplePlane samples = new TestSamplePlane(16, 12, 100);
        RenderFrame frame = RenderFrame.create(
                job, RenderGrid.from(job.viewport(), job.width(), job.height()), samples);

        try (DirectDoubleRenderBackend backend = new DirectDoubleRenderBackend()) {
            backend.render(frame, () -> false, ignored -> {}, null);
        }

        assertTrue(frame.isComplete());
        assertSame(samples, frame.samplePlane());
        assertThrows(IllegalStateException.class, frame::fractalData);
    }

    private static final class TestSamplePlane implements SamplePlane {
        private final int width;
        private final int height;
        private final int maxIterations;
        private final int[] iterations;
        private final double[] smoothIterations;
        private final boolean[] escaped;
        private final double[] orbitTrapDistances;

        private TestSamplePlane(int width, int height, int maxIterations) {
            this.width = width;
            this.height = height;
            this.maxIterations = maxIterations;
            this.iterations = new int[width * height];
            this.smoothIterations = new double[width * height];
            this.escaped = new boolean[width * height];
            this.orbitTrapDistances = new double[width * height];
        }

        public int width() { return width; }
        public int height() { return height; }
        public int maxIterations() { return maxIterations; }
        public int size() { return iterations.length; }
        public int iterations(int index) { return iterations[index]; }
        public double smoothIterations(int index) { return smoothIterations[index]; }
        public boolean escaped(int index) { return escaped[index]; }
        public double orbitTrapDistance(int index) { return orbitTrapDistances[index]; }
        public void set(int index, FractalSample sample) {
            setValues(index, sample.iterations(), sample.smoothIterations(),
                    sample.escaped(), sample.orbitTrapDistance());
        }
        public void set(int x, int y, FractalSample sample) { set(y * width + x, sample); }

        public void setValues(
                int index, int iterations, double smoothIterations,
                boolean escaped, double orbitTrapDistance
        ) {
            this.iterations[index] = iterations;
            this.smoothIterations[index] = smoothIterations;
            this.escaped[index] = escaped;
            this.orbitTrapDistances[index] = orbitTrapDistance;
        }

        public void copyPixelFrom(
                SamplePlane source, int sourceX, int sourceY, int targetX, int targetY
        ) {
            SamplePlane.super.copyPixelFrom(source, sourceX, sourceY, targetX, targetY);
        }

        public void copyRowFrom(
                SamplePlane source, int sourceY, int targetY, int xFrom, int xTo
        ) {
            SamplePlane.super.copyRowFrom(source, sourceY, targetY, xFrom, xTo);
        }

        public void copyRegionFrom(
                SamplePlane source, int sourceX, int sourceY,
                int targetX, int targetY, int regionWidth, int regionHeight
        ) {
            SamplePlane.super.copyRegionFrom(source, sourceX, sourceY,
                    targetX, targetY, regionWidth, regionHeight);
        }
    }
}
