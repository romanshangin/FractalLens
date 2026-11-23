package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.render.RenderJob;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Objects;

/**
 * Whole-frame Julia preview request. The kernel iterates in uncertified FP32
 * and returns ARGB colors only, so the result is approximate near the set
 * boundary. Callers must treat it as a transient preview: never cache, export
 * or record it as a CPU-reference or certified frame. With
 * {@code filterBrightPoints} the kernel also dims isolated near-white pixels
 * on the GPU, exactly as {@link JuliaBrightPointFilter} does on the CPU.
 */
public final class GpuJuliaPreviewRequest {
    static final int MAX_ITERATIONS = 10_000;
    private final RenderJob job;
    private final RenderGrid grid;
    private final SmoothPaletteColoring coloring;
    private final GradientPalette palette;
    private final PaletteOffset offset;
    private final float cReal;
    private final float cImaginary;
    private final int[] colors;
    private final boolean filterBrightPoints;

    public GpuJuliaPreviewRequest(RenderJob job, RenderGrid grid, SmoothPaletteColoring coloring, int[] colors) {
        this(job, grid, coloring, colors, false);
    }

    public GpuJuliaPreviewRequest(RenderJob job, RenderGrid grid, SmoothPaletteColoring coloring, int[] colors,
                                  boolean filterBrightPoints) {
        this.filterBrightPoints = filterBrightPoints;
        this.job = Objects.requireNonNull(job);
        this.grid = Objects.requireNonNull(grid);
        this.coloring = Objects.requireNonNull(coloring);
        this.colors = Objects.requireNonNull(colors);
        if (!supportsJob(job, grid)) {
            throw new IllegalArgumentException("Julia preview supports direct FP32-representable Julia grids only");
        }
        if (!supportsColoring(coloring)) {
            throw new IllegalArgumentException("Julia preview requires a finite smooth gradient palette");
        }
        if (colors.length != Math.multiplyExact(job.width(), job.height())) {
            throw new IllegalArgumentException("Julia preview color buffer dimensions do not match frame");
        }
        this.palette = (GradientPalette) coloring.palette();
        this.offset = PaletteOffset.from(coloring);
        this.cReal = (float) (double) job.formula().parameters().get("cReal");
        this.cImaginary = (float) (double) job.formula().parameters().get("cImaginary");
    }

    /** Whether a request for this job, grid and coloring would be accepted; allocates no color buffer. */
    public static boolean supports(RenderJob job, RenderGrid grid, SmoothPaletteColoring coloring) {
        return supportsJob(Objects.requireNonNull(job), Objects.requireNonNull(grid))
                && supportsColoring(Objects.requireNonNull(coloring));
    }

    private static boolean supportsJob(RenderJob job, RenderGrid grid) {
        Map<String, Double> parameters = job.formula().parameters();
        Double real = parameters.get("cReal"), imaginary = parameters.get("cImaginary");
        return job.formula().preset() == FractalPreset.JULIA && job.formula().orbitTrap() == OrbitTrap.NONE
                && real != null && imaginary != null && Double.isFinite(real) && Double.isFinite(imaginary)
                && Math.abs(real) <= 4 && Math.abs(imaginary) <= 4
                && job.maxIterations() >= 1 && job.maxIterations() <= MAX_ITERATIONS
                && MandelbrotPrecisionGate.supportsGrid(grid, job.width(), job.height());
    }

    private static boolean supportsColoring(SmoothPaletteColoring coloring) {
        if (!(coloring.palette() instanceof GradientPalette)
                || !Double.isFinite(coloring.colorScale()) || coloring.colorScale() <= 0
                || coloring.colorScale() >= 1) {
            return false;
        }
        try {
            PaletteOffset.from(coloring);
            return true;
        } catch (IllegalArgumentException outsidePrecisionEnvelope) {
            return false;
        }
    }

    int width() { return job.width(); }
    int height() { return job.height(); }
    int pixelCount() { return colors.length; }
    int maxIterations() { return job.maxIterations(); }
    float colorScale() { return (float) coloring.colorScale(); }
    float cReal() { return cReal; }
    float cImaginary() { return cImaginary; }
    GradientPalette palette() { return palette; }
    PaletteOffset offset() { return offset; }
    int[] colors() { return colors; }
    boolean filterBrightPoints() { return filterBrightPoints; }

    void writeAxes(FloatBuffer target) {
        for (int x = 0; x < width(); x++) target.put((float) grid.realAt(x));
        for (int y = 0; y < height(); y++) target.put((float) grid.imaginaryAt(y));
    }
}
