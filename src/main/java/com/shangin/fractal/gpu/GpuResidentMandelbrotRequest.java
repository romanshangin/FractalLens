package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderJob;

import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.stream.IntStream;

/** Experimental whole-frame request; not part of production backend selection. */
public final class GpuResidentMandelbrotRequest {
    static final int CORRECTION_WORDS = 5;
    private final RenderJob job;
    private final RenderGrid grid;
    private final SmoothPaletteColoring coloring;
    private final GradientPalette palette;
    private final PaletteOffset offset;
    private final int[] colors;

    public GpuResidentMandelbrotRequest(RenderFrame frame, SmoothPaletteColoring coloring, int[] colors) {
        this(Objects.requireNonNull(frame).job(), frame.renderGrid(), coloring, colors);
    }

    public GpuResidentMandelbrotRequest(
            RenderJob job, RenderGrid grid, SmoothPaletteColoring coloring, int[] colors) {
        this.job = Objects.requireNonNull(job);
        this.grid = Objects.requireNonNull(grid);
        this.coloring = Objects.requireNonNull(coloring);
        this.colors = Objects.requireNonNull(colors);
        if (job.formula().preset() != FractalPreset.MANDELBROT
                || job.formula().orbitTrap() != OrbitTrap.NONE
                || !job.formula().parameters().isEmpty()
                || job.maxIterations() > 1000
                || !MandelbrotPrecisionGate.supportsGrid(
                grid, job.width(), job.height())) {
            throw new IllegalArgumentException("Resident spike supports certified direct Mandelbrot grids only");
        }
        if (!(coloring.palette() instanceof GradientPalette gradient)
                || !Double.isFinite(coloring.colorScale()) || coloring.colorScale() <= 0
                || coloring.colorScale() >= 1) {
            throw new IllegalArgumentException("Resident spike requires a finite smooth gradient palette");
        }
        if (colors.length != Math.multiplyExact(job.width(), job.height())) {
            throw new IllegalArgumentException("Resident color buffer dimensions do not match frame");
        }
        this.palette = gradient;
        this.offset = PaletteOffset.from(coloring);
    }

    int width() { return job.width(); }
    int height() { return job.height(); }
    int pixelCount() { return colors.length; }
    int maxIterations() { return job.maxIterations(); }
    float colorScale() { return (float) coloring.colorScale(); }
    GradientPalette palette() { return palette; }
    PaletteOffset offset() { return offset; }
    int[] colors() { return colors; }

    void writeAxes(FloatBuffer target) {
        for (int x = 0; x < width(); x++) writeCoordinate(target, grid.realAt(x));
        for (int y = 0; y < height(); y++) writeCoordinate(target, grid.imaginaryAt(y));
    }

    private static void writeCoordinate(FloatBuffer target, double value) {
        target.put((float) value).put(MandelbrotPrecisionGate.lower(value)).put(MandelbrotPrecisionGate.upper(value));
    }

    GpuResidentCorrections recover(int[] rejected) {
        int[] words = new int[Math.multiplyExact(rejected.length, CORRECTION_WORDS)];
        var calculator = job.formula().createDirectCalculator();
        IntStream.range(0, rejected.length).parallel().forEach(i -> {
            if (Thread.currentThread().isInterrupted()) return;
            int pixel = rejected[i];
            var sample = calculator.calculateSample(
                    grid.realAt(pixel % width()), grid.imaginaryAt(pixel / width()), maxIterations());
            int o = i * CORRECTION_WORDS;
            words[o] = pixel;
            words[o + 1] = sample.iterations();
            words[o + 2] = Float.floatToRawIntBits((float) sample.smoothIterations());
            words[o + 3] = sample.escaped() ? 1 : 0;
            words[o + 4] = sample.escaped()
                    ? Short.toUnsignedInt(SmoothColorLookup.encode(coloring.basePhase(sample.smoothIterations()))) : 0;
        });
        return new GpuResidentCorrections(words, rejected.length);
    }
}
