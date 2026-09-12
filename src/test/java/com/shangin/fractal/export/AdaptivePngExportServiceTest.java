package com.shangin.fractal.export;

import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.DistanceEstimatingFormula;
import com.shangin.fractal.formula.DistanceSample;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalData;
import com.shangin.fractal.render.ParallelFractalCalculator;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderRequest;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.scene.SamplingPattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePngExportServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void detectsMembershipAndColorEdges() {
        FractalData data = new FractalData(3, 1, 100);
        data.set(0, new FractalSample(100, false, 0.0, 0.0));
        data.set(1, new FractalSample(100, false, 0.0, 0.0));
        data.set(2, new FractalSample(10, true, 3.0, 0.0));

        int[] colors = {0xFF000000, 0xFF000000, 0xFFFFFFFF};

        assertFalse(AdaptivePngExportService.isBaseEdge(data, colors, 0, 0));
        assertTrue(AdaptivePngExportService.isBaseEdge(data, colors, 1, 0));
        assertTrue(AdaptivePngExportService.isBaseEdge(data, colors, 2, 0));
    }

    @Test
    void combinesImageEdgesWithAnalyticDistanceCandidates() {
        FractalData data = new FractalData(1, 1, 100);
        data.set(0, new FractalSample(10, true, 3.0, 0.0));
        int[] colors = {0xFF202020};
        RenderGrid grid = new RenderGrid(0.0, 0.0, 0.2, 0.2, 0, 0, 0);
        FractalCalculator nearBoundary = new FractalCalculator(new FixedDistanceFormula(0.1));
        FractalCalculator farFromBoundary = new FractalCalculator(new FixedDistanceFormula(0.2));

        assertTrue(AdaptivePngExportService.isSupersamplingCandidate(
                nearBoundary, grid, 100, data, colors, 0, 0
        ));
        assertFalse(AdaptivePngExportService.isSupersamplingCandidate(
                farFromBoundary, grid, 100, data, colors, 0, 0
        ));
    }

    @Test
    void nonAnalyticFormulaUsesOnlyImageSpaceEdges() {
        FractalData data = new FractalData(1, 1, 100);
        data.set(0, new FractalSample(10, true, 3.0, 0.0));

        assertFalse(AdaptivePngExportService.isSupersamplingCandidate(
                new FractalCalculator((real, imaginary, iterations) ->
                        new FractalSample(10, true, 3.0, 0.0)),
                new RenderGrid(0.0, 0.0, 0.2, 0.2, 0, 0, 0),
                100,
                data,
                new int[]{0xFF202020},
                0,
                0
        ));
    }

    @Test
    void ditheringIsStableAndLimitedToOneChannelStep() {
        int source = 0xFF808080;
        Set<Integer> results = new HashSet<>();

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int first = AdaptivePngExportService.dither(source, x, y);
                int second = AdaptivePngExportService.dither(source, x, y);

                assertEquals(first, second);
                assertTrue(Math.abs(((first >>> 16) & 0xFF) - 128) <= 1);
                assertEquals((first >>> 16) & 0xFF, (first >>> 8) & 0xFF);
                assertEquals((first >>> 8) & 0xFF, first & 0xFF);
                results.add(first);
            }
        }

        assertTrue(results.size() > 1);
    }

    @Test
    void jitteredSamplingShouldBeDeterministic() {
        FractalPreset preset = FractalPreset.MANDELBROT;
        FractalCalculator calculator = new FractalCalculator(preset.createFormula());
        SmoothPaletteColoring coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        RenderGrid grid = RenderGrid.from(preset.defaultViewport(), 64, 48);

        int first = AdaptivePngExportService.sampleGridColor(
                calculator,
                coloring,
                grid,
                300,
                21,
                17,
                4,
                SamplingPattern.DETERMINISTIC_JITTER
        );
        int second = AdaptivePngExportService.sampleGridColor(
                calculator,
                coloring,
                grid,
                300,
                21,
                17,
                4,
                SamplingPattern.DETERMINISTIC_JITTER
        );

        assertEquals(first, second);
    }

    @Test
    void exportsCompletedFrameAtOriginalDimensions() throws Exception {
        int width = 24;
        int height = 16;
        FractalPreset preset = FractalPreset.JULIA;
        RenderFrame frame = RenderFrame.create(new RenderRequest(
                new FractalCalculator(preset.createFormula()),
                preset.defaultViewport(),
                width,
                height,
                120
        ));

        try (ParallelFractalCalculator calculator = new ParallelFractalCalculator(2)) {
            calculator.calculate(frame, () -> false, ignored -> {});
        }

        Path target = tempDirectory.resolve("adaptive.png");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();

        try (AdaptivePngExportService service = new AdaptivePngExportService()) {
            service.export(
                    frame,
                    new SmoothPaletteColoring(PalettePreset.ICE.palette()),
                    target,
                    ignored -> completed.countDown(),
                    exception -> {
                        error.set(exception);
                        completed.countDown();
                    }
            );

            assertTrue(completed.await(10, TimeUnit.SECONDS));
        }

        assertNull(error.get());

        BufferedImage image = ImageIO.read(target.toFile());
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }

    @Test
    void deepJuliaExportKeepsSubpixelCoordinatesBeyondDoublePrecision() throws Exception {
        var job = new com.shangin.fractal.render.RenderJob(
                com.shangin.fractal.render.FormulaDefinition.forPreset(FractalPreset.JULIA,
                        com.shangin.fractal.coloring.OrbitTrap.NONE),
                new com.shangin.fractal.math.Viewport("2", "0", "1e-80"), 5, 5, 20);
        RenderFrame frame;
        try (var backend = new com.shangin.fractal.render.JuliaDeepZoomRenderBackend()) {
            frame = backend.render(RenderFrame.create(job), () -> false, ignored -> {}, null);
        }
        Path target = tempDirectory.resolve("deep-julia.png");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (var service = new AdaptivePngExportService()) {
            service.export(frame, (iterations, smooth, escaped, maximum) ->
                            iterations == 0 ? 0xFF000000 : 0xFFFFFFFF,
                    target, ignored -> completed.countDown(), failure -> {
                        error.set(failure);
                        completed.countDown();
                    });
            assertTrue(completed.await(10, TimeUnit.SECONDS));
        }
        assertNull(error.get());
        var image = ImageIO.read(target.toFile());
        assertTrue((image.getRGB(0, 2) & 255) > 250);
        assertTrue((image.getRGB(4, 2) & 255) < 5);
        int edge = image.getRGB(2, 2) & 255;
        assertTrue(edge > 100 && edge < 240, "Boundary pixel must mix precise subsamples");
    }

    private record FixedDistanceFormula(double distance) implements DistanceEstimatingFormula {
        @Override
        public FractalSample calculate(double real, double imaginary, int maxIterations) {
            return new FractalSample(10, true, 3.0, 0.0);
        }

        @Override
        public DistanceSample calculateDistance(
                double real,
                double imaginary,
                int maxIterations
        ) {
            return new DistanceSample(calculate(real, imaginary, maxIterations), distance);
        }
    }
}
