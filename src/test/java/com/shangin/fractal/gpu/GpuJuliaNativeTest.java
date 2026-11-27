package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fractal.gpu.nativeSmoke", matches = "true")
class GpuJuliaNativeTest {
    /** Dendrite, two attracting-basin sets, a near-parabolic set and a Cantor dust. */
    private static final double[][] CONSTANTS = {
            {-0.8, 0.156}, {-0.123, 0.745}, {-1, 0}, {0.285, 0.01}, {0.3, 0.5}};

    @Test
    void previewColorsTrackCpuReferenceAndSurviveReallocationAndDeviceLoss() throws Exception {
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault(); var cpu = new DirectDoubleRenderBackend()) {
            var probe = previewJob(193, 129, 300, -0.8, 0.156);
            var probeRequest = new GpuJuliaPreviewRequest(probe, RenderGrid.from(probe.viewport(), 193, 129),
                    ice, new int[193 * 129]);
            if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) {
                assertTrue(runtime.renderJuliaPreview(probeRequest).isEmpty());
                return;
            }
            int[][] sizes = {{193, 129}, {320, 200}, {193, 129}};
            for (int[] size : sizes) {
                for (double[] c : CONSTANTS) {
                    for (var coloring : new SmoothPaletteColoring[]{ice,
                            new SmoothPaletteColoring(PalettePreset.values()[1].palette(), 0.02, 0.35)}) {
                        RenderJob job = previewJob(size[0], size[1], 300, c[0], c[1]);
                        int[] colors = new int[size[0] * size[1]];
                        var result = runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(
                                job, RenderGrid.from(job.viewport(), size[0], size[1]), coloring, colors))
                                .orElseThrow();
                        assertSame(colors, result.colors());
                        assertTrue(result.totalNanos() > 0 && result.nativeBytes() > 0);
                        int[] expected = cpuColors(cpu, job, coloring);
                        int close = 0;
                        for (int i = 0; i < colors.length; i++) if (maximumChannelError(expected[i], colors[i]) <= 1) close++;
                        // Approximate preview: most pixels match, boundary pixels may differ.
                        assertTrue(close >= colors.length * 0.95, "Only " + close + "/" + colors.length
                                + " preview pixels within one level for c=(" + c[0] + "," + c[1] + ")");
                    }
                }
            }
            runtime.handleDeviceLoss(new GpuException("simulated after preview dispatch", true));
            assertTrue(runtime.renderJuliaPreview(probeRequest).isEmpty());
        }
        try (GpuRuntime reopened = GpuRuntimeFactory.createDefault()) {
            var job = previewJob(64, 48, 100, -1, 0);
            var colors = new int[64 * 48];
            assertTrue(reopened.renderJuliaPreview(new GpuJuliaPreviewRequest(
                    job, RenderGrid.from(job.viewport(), 64, 48), ice, colors)).isPresent());
        }
    }

    @Test
    void resizeBetweenFramesThatEachFitTheBudgetKeepsTheRuntimeAvailable() throws Exception {
        if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) return;
        String previous = System.getProperty("fractal.gpu.juliaPreview.maxBytes");
        // Both frames fit 8 MiB alone; the old color buffer and the new axes together do not.
        System.setProperty("fractal.gpu.juliaPreview.maxBytes", Long.toString(8L * 1024 * 1024));
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
            for (int[] size : new int[][]{{2074, 978}, {2480, 800}, {2074, 978}}) {
                RenderJob job = previewJob(size[0], size[1], 100, -0.8, 0.156);
                int[] colors = new int[size[0] * size[1]];
                assertTrue(runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(
                                job, RenderGrid.from(job.viewport(), size[0], size[1]), ice, colors)).isPresent(),
                        size[0] + "x" + size[1] + ": " + runtime.capabilityReport());
            }
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
        } finally {
            if (previous == null) System.clearProperty("fractal.gpu.juliaPreview.maxBytes");
            else System.setProperty("fractal.gpu.juliaPreview.maxBytes", previous);
        }
    }

    @Test
    void frameThatFitsOnlyWithoutTheFilteredBufferIsReturnedUnfilteredAndKeepsTheRuntime() throws Exception {
        if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) return;
        String previous = System.getProperty("fractal.gpu.juliaPreview.maxBytes");
        // 2074x978 colors take about 8.1 MB: one color buffer fits 12 MiB, two do not.
        System.setProperty("fractal.gpu.juliaPreview.maxBytes", Long.toString(12L * 1024 * 1024));
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
            for (int round = 0; round < 2; round++) {
                RenderJob large = previewJob(2074, 978, 300, -0.8, 0.156);
                RenderGrid largeGrid = RenderGrid.from(large.viewport(), 2074, 978);
                int[] raw = new int[2074 * 978];
                int[] requested = new int[raw.length];
                runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(large, largeGrid, ice, raw, false)).orElseThrow();
                var result = runtime.renderJuliaPreview(
                        new GpuJuliaPreviewRequest(large, largeGrid, ice, requested, true)).orElseThrow();
                assertFalse(result.brightPointsFiltered());
                assertArrayEquals(raw, requested, "An unfiltered result must be the raw frame");
                assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());

                // A smaller frame still gets the GPU pass.
                RenderJob small = previewJob(641, 401, 300, -0.8, 0.156);
                var smallResult = runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(small,
                        RenderGrid.from(small.viewport(), 641, 401), ice, new int[641 * 401], true)).orElseThrow();
                assertTrue(smallResult.brightPointsFiltered());
            }
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
        } finally {
            if (previous == null) System.clearProperty("fractal.gpu.juliaPreview.maxBytes");
            else System.setProperty("fractal.gpu.juliaPreview.maxBytes", previous);
        }
    }

    @Test
    void brightPointPassMatchesTheCpuReferenceExactly() throws Exception {
        if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) return;
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var fine = new SmoothPaletteColoring(PalettePreset.ICE.palette(), 0.2, 0.1);
        int dimmed = 0;
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
            // Odd and degenerate sizes, a reallocation and filtering switched off and on again.
            int[][] sizes = {{193, 129}, {2, 2}, {2, 3}, {3, 3}, {641, 401}, {193, 129}};
            for (int[] size : sizes) {
                for (double[] c : CONSTANTS) {
                    for (var coloring : new SmoothPaletteColoring[]{ice, fine}) {
                        for (int limit : new int[]{300, 1000}) {
                            RenderJob job = previewJob(size[0], size[1], limit, c[0], c[1]);
                            RenderGrid grid = RenderGrid.from(job.viewport(), size[0], size[1]);
                            int[] raw = new int[size[0] * size[1]];
                            int[] filtered = new int[raw.length];
                            runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(job, grid, coloring, raw, false))
                                    .orElseThrow();
                            assertTrue(runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(
                                    job, grid, coloring, filtered, true)).orElseThrow().brightPointsFiltered());
                            int[] expected = new int[raw.length];
                            JuliaBrightPointFilter.apply(raw, expected, size[0], size[1]);
                            String frame = size[0] + "x" + size[1] + " c=(" + c[0] + "," + c[1] + ") limit=" + limit;
                            assertArrayEquals(expected, filtered, frame);
                            for (int i = 0; i < raw.length; i++) if (raw[i] != expected[i]) dimmed++;
                        }
                    }
                }
            }
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
        }
        // An all-copy pass would also match; the frames must exercise the dimming branch.
        assertTrue(dimmed > 0, "No tested frame contained an isolated bright point");
    }

    @Test
    void warmUpPreparesTheKernelWithoutChangingLaterFrames() throws Exception {
        var ice = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        RenderJob small = previewJob(16, 16, 300, -0.8, 0.156);
        var warmUp = new GpuJuliaPreviewRequest(small, RenderGrid.from(small.viewport(), 16, 16), ice,
                new int[16 * 16], true);
        RenderJob job = previewJob(641, 401, 300, -0.8, 0.156);
        RenderGrid grid = RenderGrid.from(job.viewport(), 641, 401);
        int[][] reference = new int[2][job.width() * job.height()];
        try (GpuRuntime cold = GpuRuntimeFactory.createDefault()) {
            if (Boolean.getBoolean("fractal.gpu.expectUnavailable")) {
                assertFalse(cold.warmUpJuliaPreview(warmUp));
                return;
            }
            for (int filter = 0; filter < 2; filter++) {
                cold.renderJuliaPreview(new GpuJuliaPreviewRequest(job, grid, ice, reference[filter], filter == 1))
                        .orElseThrow();
            }
        }
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
            assertTrue(runtime.warmUpJuliaPreview(warmUp), runtime.capabilityReport().toString());
            assertEquals(GpuRuntimeState.AVAILABLE, runtime.capabilityReport().state());
            // Both frames reallocate the warm-up's small buffers.
            for (int filter = 1; filter >= 0; filter--) {
                int[] colors = new int[reference[filter].length];
                runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(job, grid, ice, colors, filter == 1))
                        .orElseThrow();
                assertArrayEquals(reference[filter], colors, "filter=" + (filter == 1));
            }
        }
    }

    private static RenderJob previewJob(int width, int height, int limit, double real, double imaginary) {
        return GpuJuliaPreviewRequestTest.job(new Viewport(0, 0, 2.4), width, height, limit, real, imaginary);
    }

    private static int[] cpuColors(DirectDoubleRenderBackend cpu, RenderJob job, SmoothPaletteColoring coloring)
            throws InterruptedException {
        RenderFrame frame = RenderFrame.create(job);
        cpu.render(frame, () -> false, ignored -> {}, null);
        int[] colors = new int[job.width() * job.height()];
        new FractalColorizer().color(frame.samplePlane(), IntBuffer.wrap(colors), coloring);
        return colors;
    }

    private static int maximumChannelError(int expected, int actual) {
        int maximum = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            maximum = Math.max(maximum, Math.abs(((expected >>> shift) & 0xff) - ((actual >>> shift) & 0xff)));
        }
        return maximum;
    }
}
