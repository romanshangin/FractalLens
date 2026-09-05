package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GpuResidentMandelbrotRequestTest {
    @Test
    void packsAxesAndExactCpuCorrectionsWithoutAllocatingAReplacementFrame() {
        RenderJob job = job(8, 6, 40);
        RenderGrid grid = RenderGrid.from(job.viewport(), job.width(), job.height());
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var request = new GpuResidentMandelbrotRequest(job, grid, coloring, new int[48]);
        FloatBuffer axes = FloatBuffer.allocate(3 * (job.width() + job.height()));
        request.writeAxes(axes);
        assertEquals(axes.capacity(), axes.position());
        assertEquals((float) grid.realAt(0), axes.get(0));
        assertTrue(axes.get(1) <= grid.realAt(0));
        assertTrue(axes.get(2) >= grid.realAt(0));

        int[] rejected = {0, 17, 47};
        GpuResidentCorrections corrections = request.recover(rejected);
        assertEquals(rejected.length, corrections.count());
        for (int i = 0; i < rejected.length; i++) {
            int pixel = rejected[i], o = i * GpuResidentMandelbrotRequest.CORRECTION_WORDS;
            var sample = job.formula().createDirectCalculator().calculateSample(
                    grid.realAt(pixel % job.width()), grid.imaginaryAt(pixel / job.width()), job.maxIterations());
            assertEquals(pixel, corrections.words()[o]);
            assertEquals(sample.iterations(), corrections.words()[o + 1]);
            assertEquals(sample.escaped() ? 1 : 0, corrections.words()[o + 3]);
            int phase = sample.escaped()
                    ? Short.toUnsignedInt(SmoothColorLookup.encode(coloring.basePhase(sample.smoothIterations()))) : 0;
            assertEquals(phase, corrections.words()[o + 4]);
        }
    }

    @Test
    void rejectsUnsupportedJobsAndMismatchedOutput() {
        RenderJob job = job(8, 6, 40);
        RenderGrid grid = RenderGrid.from(job.viewport(), job.width(), job.height());
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        assertThrows(IllegalArgumentException.class,
                () -> new GpuResidentMandelbrotRequest(job, grid, coloring, new int[47]));
        RenderJob tooManyIterations = new RenderJob(job.formula(), job.viewport(), 8, 6, 1001,
                RenderPriority.center(), Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new GpuResidentMandelbrotRequest(
                        tooManyIterations, grid, coloring, new int[48]));
    }

    private static RenderJob job(int width, int height, int iterations) {
        return new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                FractalPreset.MANDELBROT.defaultViewport(), width, height, iterations,
                RenderPriority.center(), Optional.empty());
    }
}
