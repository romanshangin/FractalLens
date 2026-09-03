package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.formula.MandelbrotFormula;
import com.shangin.fractal.math.Viewport;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Offline numeric experiment, not a GPU benchmark or a production selector.
 * Separates input rounding from FP32 orbit error against the current CPU path.
 * Java float and one explicit FMA variant do not establish shader conformance.
 */
public final class MandelbrotFloatPrecisionBenchmark {
    static final double MAX_COORDINATE_ERROR_PIXELS = 1.0 / 16.0;
    static final double MAX_SMOOTH_ERROR = 0.01;

    enum Mode { COORDINATES_ONLY, FLOAT_PER_PIXEL, FLOAT_GRID, FLOAT_FMA }

    private record Scene(String name, Viewport viewport) {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty("floatPrecision.output", "target/mandelbrot-fp32.csv"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        var formula = new MandelbrotFormula();
        try (BufferedWriter writer = Files.newBufferedWriter(output)) {
            writer.write("# CPU numeric screening only; Java=" + System.getProperty("java.version")
                    + "; os=" + System.getProperty("os.name") + "; arch=" + System.getProperty("os.arch") + "\n");
            writer.write("# Limits: coordinate error <= 1/16 pixel; exact escaped flag; iteration error <= 1; smooth error <= 0.01; no adjacent-coordinate collapse\n");
            writer.write("scene,center_real,center_imaginary,scale,width,height,max_iterations,mode,points,escaped_mismatches,iteration_mismatches,iteration_over_one,smooth_over_limit,nonfinite,max_smooth_error,max_coordinate_error_pixels,collapsed_axis_neighbors,screen_pass,first_bad_x,first_bad_y\n");
            for (Scene scene : scenes()) {
                for (int[] dimensions : List.of(new int[]{384, 256}, new int[]{3024, 1964})) {
                    int width = dimensions[0], height = dimensions[1];
                    RenderGrid grid = RenderGrid.from(scene.viewport, width, height);
                    // Full small frames; a deterministic lattice spanning all four edges on Retina.
                    int columns = width == 384 ? width : 129;
                    int rows = width == 384 ? height : 97;
                    for (int limit : new int[]{32, 64, 300, 1000}) {
                        for (Mode mode : Mode.values()) {
                            Stats stats = new Stats();
                            for (int sy = 0; sy < rows; sy++) {
                                int y = sy * (height - 1) / (rows - 1);
                                for (int sx = 0; sx < columns; sx++) {
                                    int x = sx * (width - 1) / (columns - 1);
                                    double real = grid.realAt(x), imaginary = grid.imaginaryAt(y);
                                    float cr = realAt(grid, x, mode), ci = imaginaryAt(grid, y, mode);
                                    FractalSample reference = formula.calculate(real, imaginary, limit);
                                    FractalSample candidate = mode == Mode.COORDINATES_ONLY
                                            ? formula.calculate(cr, ci, limit)
                                            : calculate(cr, ci, limit, mode == Mode.FLOAT_FMA);
                                    double coordinateError = Math.max(Math.abs(cr - real) / grid.realStep(),
                                            Math.abs(ci - imaginary) / grid.imaginaryStep());
                                    stats.add(reference, candidate, coordinateError, x, y);
                                }
                            }
                            int collapsed = collapsedNeighbors(grid, width, height, mode);
                            boolean passes = stats.passes() && collapsed == 0;
                            writer.write(String.format(Locale.ROOT,
                                    "%s,%.17g,%.17g,%.17g,%d,%d,%d,%s,%d,%d,%d,%d,%d,%d,%.9g,%.9g,%d,%s,%d,%d%n",
                                    scene.name, scene.viewport.centerReal(), scene.viewport.centerImaginary(),
                                    scene.viewport.scale(), width, height, limit, mode, stats.points,
                                    stats.escapedMismatches, stats.iterationMismatches, stats.iterationOverOne,
                                    stats.smoothOverLimit, stats.nonfinite, stats.maxSmoothError,
                                    stats.maxCoordinateError, collapsed, passes, stats.firstBadX, stats.firstBadY));
                        }
                    }
                    System.out.printf("FP32 screening: %s %dx%d complete%n", scene.name, width, height);
                }
            }
        }
        System.out.println("Numeric screening saved: " + output.toAbsolutePath());
    }

    private static List<Scene> scenes() {
        return List.of(
                new Scene("exterior-control", new Viewport(1.0, 1.0, 0.2)),
                new Scene("interior-control", new Viewport(0.0, 0.0, 0.1)),
                new Scene("overview", new Viewport(-0.75, 0.0, 2.4)),
                new Scene("cardioid-cusp", new Viewport(0.25, 0.0, 0.01)),
                new Scene("seahorse-100x", new Viewport(-0.743643887037151, 0.13182590420533, 0.024)),
                new Scene("seahorse-10000x", new Viewport(-0.743643887037151, 0.13182590420533, 0.00024)),
                new Scene("coordinate-collapse", new Viewport(-0.743643887037151, 0.13182590420533, 1e-7)));
    }

    static FractalSample calculate(float real, float imaginary, int limit, boolean fused) {
        float zr = 0.0f, zi = 0.0f;
        int iteration = 0;
        // No FP32 cardioid/bulb shortcuts: isolate recurrence error first.
        while (zr * zr + zi * zi <= 4.0f && iteration < limit) {
            float nextReal = fused ? Math.fma(zr, zr, -(zi * zi)) + real
                    : zr * zr - zi * zi + real;
            float nextImaginary = fused ? Math.fma(2.0f * zr, zi, imaginary)
                    : 2.0f * zr * zi + imaginary;
            zr = nextReal;
            zi = nextImaginary;
            iteration++;
        }
        // Preserve the CPU convention even when the last iteration crosses radius 2.
        return new FractalSample(iteration, iteration < limit, zr, zi);
    }

    static float realAt(RenderGrid grid, int x, Mode mode) {
        if (mode != Mode.FLOAT_GRID) return (float) grid.realAt(x);
        return (float) grid.originReal() + (float) (x + grid.offsetX()) * (float) grid.realStep();
    }

    static float imaginaryAt(RenderGrid grid, int y, Mode mode) {
        if (mode != Mode.FLOAT_GRID) return (float) grid.imaginaryAt(y);
        long row = y + grid.offsetY();
        boolean mirror = grid.conjugateHeight() > 0 && row >= (grid.conjugateHeight() + 1L) / 2L
                && row < grid.conjugateHeight();
        if (mirror) row = grid.conjugateHeight() - 1L - row;
        float value = (float) grid.originImaginary() - (float) row * (float) grid.imaginaryStep();
        return mirror ? -value : value;
    }

    private static int collapsedNeighbors(RenderGrid grid, int width, int height, Mode mode) {
        int collapsed = 0;
        for (int x = 1; x < width; x++) {
            if (realAt(grid, x - 1, mode) == realAt(grid, x, mode)) collapsed++;
        }
        for (int y = 1; y < height; y++) {
            if (imaginaryAt(grid, y - 1, mode) == imaginaryAt(grid, y, mode)) collapsed++;
        }
        return collapsed;
    }

    private static final class Stats {
        int points, escapedMismatches, iterationMismatches, iterationOverOne, smoothOverLimit, nonfinite;
        int firstBadX = -1, firstBadY = -1;
        double maxSmoothError, maxCoordinateError;

        void add(FractalSample reference, FractalSample candidate, double coordinateError, int x, int y) {
            points++;
            maxCoordinateError = Math.max(maxCoordinateError, coordinateError);
            boolean bad = coordinateError > MAX_COORDINATE_ERROR_PIXELS;
            if (reference.escaped() != candidate.escaped()) {
                escapedMismatches++;
                bad = true;
            }
            if (reference.escaped() && candidate.escaped()) {
                int difference = Math.abs(reference.iterations() - candidate.iterations());
                if (difference != 0) iterationMismatches++;
                if (difference > 1) { iterationOverOne++; bad = true; }
                double error = Math.abs(reference.smoothIterations() - candidate.smoothIterations());
                if (!Double.isFinite(error)) { nonfinite++; bad = true; }
                else {
                    maxSmoothError = Math.max(maxSmoothError, error);
                    if (error > MAX_SMOOTH_ERROR) { smoothOverLimit++; bad = true; }
                }
            }
            if (bad && firstBadX < 0) { firstBadX = x; firstBadY = y; }
        }

        boolean passes() {
            return escapedMismatches == 0 && iterationOverOne == 0 && smoothOverLimit == 0
                    && nonfinite == 0 && maxCoordinateError <= MAX_COORDINATE_ERROR_PIXELS;
        }
    }
}
