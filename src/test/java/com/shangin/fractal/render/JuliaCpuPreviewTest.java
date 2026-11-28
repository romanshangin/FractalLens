package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.JuliaBrightPointFilter;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.JuliaParameters;
import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** CPU preview frames of Explore Julia Parameters against direct-double reference renders. */
class JuliaCpuPreviewTest {
    private static final FractalScene DENDRITE = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
            .withJuliaParameters(new JuliaParameters(-0.8, 0.156));

    @Test
    void scaleOneMatchesTheDirectDoubleFrameWithAndWithoutTheFilter() throws Exception {
        RenderJob job = job(DENDRITE, 97, 61, 300);
        ColoringStrategy coloring = DENDRITE.coloring().createStrategy();
        int[] reference = reference(job, coloring);
        int[] filtered = new int[reference.length];
        JuliaBrightPointFilter.apply(reference, filtered, job.width(), job.height());
        try (var preview = new JuliaCpuPreview()) {
            int[] colors = new int[reference.length];
            var frame = preview.render(job, 1, coloring, false, colors, () -> false);
            assertArrayEquals(reference, colors);
            assertEquals(new JuliaCpuPreview.Frame(1, 97, 61, frame.sampleNanos()), frame);
            preview.render(job, 1, coloring, true, colors, () -> false);
            assertArrayEquals(filtered, colors);
        }
    }

    @Test
    void coarseFramesKeepTheCenterAndMultiplyThePixelSpacing() {
        RenderJob job = job(DENDRITE, 101, 63, 300);
        RenderGrid full = RenderGrid.from(job.viewport(), job.width(), job.height());
        for (int scale = 2; scale <= JuliaCpuPreview.MAX_SCALE; scale++) {
            RenderJob coarse = JuliaCpuPreview.coarseJob(job, scale);
            assertEquals((101 + scale - 1) / scale, coarse.width());
            assertEquals((63 + scale - 1) / scale, coarse.height());
            assertEquals(job.maxIterations(), coarse.maxIterations());
            assertEquals(job.formula(), coarse.formula());
            assertEquals(0, job.viewport().center().real().compareTo(coarse.viewport().center().real()));
            assertEquals(0, job.viewport().center().imaginary().compareTo(coarse.viewport().center().imaginary()));
            RenderGrid grid = RenderGrid.from(coarse.viewport(), coarse.width(), coarse.height());
            assertEquals(full.realStep() * scale, grid.realStep(), Math.ulp(full.realStep() * scale) * 8);
            assertEquals(full.imaginaryStep() * scale, grid.imaginaryStep(), Math.ulp(full.imaginaryStep() * scale) * 8);
        }
        assertSame(job, JuliaCpuPreview.coarseJob(job, 1));
        assertThrows(IllegalArgumentException.class, () -> JuliaCpuPreview.coarseJob(job, 0));
        assertThrows(IllegalArgumentException.class, () -> JuliaCpuPreview.coarseJob(job, JuliaCpuPreview.MAX_SCALE + 1));
        // Never fewer than two samples per axis.
        RenderJob small = job(DENDRITE, 9, 3, 300);
        RenderJob coarse = JuliaCpuPreview.coarseJob(small, 8);
        assertEquals(2, coarse.width());
        assertEquals(2, coarse.height());
    }

    @Test
    void everyTargetPixelShowsTheCoarseSampleOfItsBlock() throws Exception {
        RenderJob job = job(DENDRITE, 101, 63, 300);
        RenderGrid full = RenderGrid.from(job.viewport(), job.width(), job.height());
        ColoringStrategy coloring = DENDRITE.coloring().createStrategy();
        try (var preview = new JuliaCpuPreview()) {
            for (int scale = 2; scale <= JuliaCpuPreview.MAX_SCALE; scale++) {
                RenderJob coarse = JuliaCpuPreview.coarseJob(job, scale);
                int[] samples = reference(coarse, coloring);
                int[] filtered = new int[samples.length];
                JuliaBrightPointFilter.apply(samples, filtered, coarse.width(), coarse.height());
                int[] colors = new int[job.width() * job.height()];
                var frame = preview.render(job, scale, coloring, true, colors, () -> false);
                assertEquals(scale, frame.scale());
                assertEquals(coarse.width(), frame.width());
                assertEquals(coarse.height(), frame.height());
                RenderGrid coarseGrid = RenderGrid.from(coarse.viewport(), coarse.width(), coarse.height());
                for (int y = 0; y < job.height(); y++) {
                    double row = (coarseGrid.imaginaryAt(0) - full.imaginaryAt(y)) / coarseGrid.imaginaryStep();
                    for (int x = 0; x < job.width(); x++) {
                        double column = (full.realAt(x) - coarseGrid.realAt(0)) / coarseGrid.realStep();
                        int color = colors[y * job.width() + x];
                        // An even scale puts some pixels halfway between two samples: either is nearest.
                        boolean nearest = false;
                        for (int sampleY : nearestIndices(row, coarse.height())) {
                            for (int sampleX : nearestIndices(column, coarse.width())) {
                                nearest |= filtered[sampleY * coarse.width() + sampleX] == color;
                            }
                        }
                        assertTrue(nearest, "scale " + scale + " pixel " + x + "," + y + " shows its nearest sample");
                    }
                }
            }
        }
    }

    @Test
    void enlargementCentersTheCoarseGridOnTheTarget() {
        // Three samples of scale 3 cover nine pixels; seven are shown, one cut on each side.
        int[] destination = new int[7 * 2];
        JuliaCpuPreview.enlarge(new int[] {1, 2, 3, 4, 5, 6}, 3, 2, 3, destination, 7, 2);
        assertArrayEquals(new int[] {1, 1, 2, 2, 2, 3, 3, 4, 4, 5, 5, 5, 6, 6}, destination);
    }

    @Test
    void supportsOnlyJuliaViewsWithinDirectPrecision() {
        try (var preview = new JuliaCpuPreview()) {
            assertTrue(preview.supports(job(DENDRITE, 80, 60, 300)));
            FractalScene mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);
            assertFalse(preview.supports(job(mandelbrot, 80, 60, 300)));
            FractalScene deep = DENDRITE.withViewport(new Viewport("0.1", "0.2", "1e-30"));
            assertFalse(preview.supports(job(deep, 80, 60, 300)));
            assertThrows(IllegalArgumentException.class, () -> preview.render(job(deep, 80, 60, 300), 2,
                    DENDRITE.coloring().createStrategy(), false, new int[80 * 60], () -> false));
        }
    }

    @Test
    void cancellationReturnsNoFrame() throws Exception {
        var calls = new AtomicInteger();
        var cancelling = new RenderBackend() {
            private final DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
            @Override public boolean supports(RenderJob job) { return direct.supports(job); }
            @Override public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                                                Consumer<RenderRegion> regionCompleted,
                                                Consumer<TileTimingStats> timingCompleted) {
                calls.incrementAndGet();
                return frame;
            }
            @Override public void close() { direct.close(); }
        };
        RenderJob job = job(DENDRITE, 80, 60, 300);
        try (var preview = new JuliaCpuPreview(cancelling)) {
            assertNull(preview.render(job, 2, DENDRITE.coloring().createStrategy(), true,
                    new int[80 * 60], () -> true));
            // An incomplete frame is not a result either.
            assertNull(preview.render(job, 2, DENDRITE.coloring().createStrategy(), true,
                    new int[80 * 60], () -> false));
        }
        assertEquals(2, calls.get());
        try (var preview = new JuliaCpuPreview()) {
            assertThrows(IllegalArgumentException.class, () -> preview.render(job, 2,
                    DENDRITE.coloring().createStrategy(), false, new int[10], () -> false));
        }
    }

    /** Sample indices within half a sample spacing of {@code position}, in sample units. */
    private static int[] nearestIndices(double position, int count) {
        int below = (int) Math.clamp(Math.floor(position), 0, count - 1);
        int above = (int) Math.clamp(Math.ceil(position), 0, count - 1);
        boolean belowNear = Math.abs(position - below) <= 0.5 + 1e-9;
        boolean aboveNear = Math.abs(position - above) <= 0.5 + 1e-9;
        if (belowNear && aboveNear && below != above) return new int[] {below, above};
        return new int[] {belowNear ? below : above};
    }

    private static RenderJob job(FractalScene scene, int width, int height, int iterations) {
        return new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), width, height, iterations);
    }

    private static int[] reference(RenderJob job, ColoringStrategy coloring) throws InterruptedException {
        try (var backend = new DirectDoubleRenderBackend()) {
            RenderFrame frame = backend.render(RenderFrame.create(job), () -> false, region -> {}, null);
            int[] colors = new int[job.width() * job.height()];
            new FractalColorizer().color(frame.samplePlane(), IntBuffer.wrap(colors), coloring);
            return colors;
        }
    }
}
