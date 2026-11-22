package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.*;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.*;

import java.nio.IntBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Opt-in off-screen Julia comparison of the direct CPU pipeline and the
 * approximate GPU preview kernel. Each measurement ends with ARGB colors;
 * JavaFX publication is excluded. It never participates in application
 * backend selection.
 */
public final class GpuJuliaBenchmark {
    private static final String HEADER = "scene,width,height,max_iterations,c_real,c_imaginary,backend,phase,"
            + "iteration,total_ms,setup_ms,upload_ms,dispatch_ms,readback_ms,"
            + "native_bytes,differing_colors,outlier_colors,max_channel_error";

    private GpuJuliaBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty("fractal.juliaBenchmark.output", "target/gpu-julia-benchmark.csv"));
        int warmup = Integer.getInteger("fractal.juliaBenchmark.warmup", 3);
        int samples = Integer.getInteger("fractal.juliaBenchmark.samples", 10);
        int iterations = Integer.getInteger("fractal.juliaBenchmark.iterations", 300);
        if (warmup < 1 || samples < 1 || iterations < 1 || iterations > 1000) {
            throw new IllegalArgumentException("Positive warmup/samples and 1..1000 iterations required");
        }
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        var rows = new ArrayList<String>();
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault();
             DirectDoubleRenderBackend cpu = new DirectDoubleRenderBackend()) {
            if (!runtime.isUsableFor(GpuNumericCapability.FLOAT32)) {
                throw new IllegalStateException("Julia benchmark requires a working GPU: " + runtime.capabilityReport());
            }
            String metadata = "# " + runtime.capabilityReport() + "\n# Java " + System.getProperty("java.version")
                    + "; " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + "; processors=" + Runtime.getRuntime().availableProcessors()
                    + "; maxHeap=" + Runtime.getRuntime().maxMemory() + "; time=" + Instant.now() + "\n";
            System.out.print(metadata);
            for (String size : System.getProperty("fractal.juliaBenchmark.sizes", "1512x982,3024x1964").split(",")) {
                String[] dimensions = size.split("x");
                int width = Integer.parseInt(dimensions[0]), height = Integer.parseInt(dimensions[1]);
                if (width < 2 || height < 2 || (long) width * height > 8_000_000) {
                    throw new IllegalArgumentException("Size outside Julia benchmark bounds");
                }
                for (String scene : System.getProperty("fractal.juliaBenchmark.scenes",
                        "dendrite,spiral,rabbit,dust,sweep").split(",")) {
                    for (int i = -warmup - 1; i < samples; i++) {
                        String phase = i == -warmup - 1 ? "cold" : i < 0 ? "warmup" : "sample";
                        double[] c = constant(scene, i + warmup + 1);
                        RenderJob job = job(width, height, iterations, c);
                        Measurement[] pair = new Measurement[2];
                        // Alternate the backend order so neither backend always runs first.
                        for (int order = 0; order < 2; order++) {
                            int backend = Math.floorMod(i + order, 2);
                            pair[backend] = backend == 0
                                    ? measureCpu(cpu, job, coloring) : measurePreview(runtime, job, coloring);
                        }
                        Conformance previewColors = compare(pair[0].colors, pair[1].colors);
                        rows.add(pair[0].csv(scene, width, height, iterations, c, "CPU", phase, i,
                                new Conformance(0, 0, 0)));
                        rows.add(pair[1].csv(scene, width, height, iterations, c, "GPU_PREVIEW", phase, i,
                                previewColors));
                        Files.createDirectories(output.toAbsolutePath().getParent());
                        Files.writeString(output, metadata + HEADER + "\n" + String.join("\n", rows) + "\n");
                        System.out.printf(Locale.ROOT,
                                "%s %s %s %d: CPU %.2f ms; preview %.2f ms (outliers %d, max %d)%n",
                                size, scene, phase, i, pair[0].totalNanos / 1e6, pair[1].totalNanos / 1e6,
                                previewColors.outliers, previewColors.maximumChannelError);
                    }
                }
            }
            System.out.println("Saved " + output.toAbsolutePath());
        }
    }

    /** The sweep scene moves c around a circle through connected and disconnected sets, like a drag. */
    private static double[] constant(String scene, int step) {
        return switch (scene) {
            case "dendrite" -> new double[]{-0.8, 0.156};
            case "spiral" -> new double[]{-0.7269, 0.1889};
            case "rabbit" -> new double[]{-0.123, 0.745};
            case "dust" -> new double[]{0.285, 0.01};
            case "sweep" -> {
                double angle = 2 * Math.PI * step / 40.0;
                yield new double[]{0.7885 * Math.cos(angle), 0.7885 * Math.sin(angle)};
            }
            default -> throw new IllegalArgumentException("Unknown scene " + scene);
        };
    }

    private static RenderJob job(int width, int height, int iterations, double[] c) {
        var scene = new FractalScene(FractalPreset.JULIA, FractalPreset.JULIA.defaultViewport(),
                new IterationSettings(iterations, 0), new ColoringSettings(PalettePreset.ICE),
                new AntialiasSettings(), new JuliaParameters(c[0], c[1]));
        return new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), width, height, iterations,
                RenderPriority.center(), Optional.empty());
    }

    private static Measurement measureCpu(DirectDoubleRenderBackend backend, RenderJob job,
                                          SmoothPaletteColoring coloring) throws InterruptedException {
        long started = System.nanoTime();
        RenderFrame frame = RenderFrame.create(job);
        backend.render(frame, () -> false, ignored -> {}, null);
        int[] colors = new int[job.width() * job.height()];
        new FractalColorizer().color(frame.samplePlane(), IntBuffer.wrap(colors), coloring);
        return new Measurement(colors, System.nanoTime() - started);
    }

    private static Measurement measurePreview(GpuRuntime runtime, RenderJob job, SmoothPaletteColoring coloring)
            throws InterruptedException {
        long started = System.nanoTime();
        int[] colors = new int[job.width() * job.height()];
        GpuJuliaPreviewResult result = runtime.renderJuliaPreview(new GpuJuliaPreviewRequest(
                        job, RenderGrid.from(job.viewport(), job.width(), job.height()), coloring, colors))
                .orElseThrow(() -> new IllegalStateException("Julia preview fallback: " + runtime.capabilityReport()));
        var measurement = new Measurement(colors, System.nanoTime() - started);
        measurement.preview = result;
        return measurement;
    }

    private static Conformance compare(int[] expected, int[] actual) {
        int differing = 0, outliers = 0, maximum = 0;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] == actual[i]) continue;
            differing++;
            int pixelMaximum = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                pixelMaximum = Math.max(pixelMaximum,
                        Math.abs(((expected[i] >>> shift) & 0xff) - ((actual[i] >>> shift) & 0xff)));
            }
            maximum = Math.max(maximum, pixelMaximum);
            if (pixelMaximum > 1) outliers++;
        }
        return new Conformance(differing, outliers, maximum);
    }

    private record Conformance(int differing, int outliers, int maximumChannelError) {}

    private static final class Measurement {
        private final int[] colors;
        private final long totalNanos;
        private GpuJuliaPreviewResult preview;

        private Measurement(int[] colors, long totalNanos) {
            this.colors = colors;
            this.totalNanos = totalNanos;
        }

        private String csv(String scene, int width, int height, int iterations, double[] c, String backend,
                           String phase, int iteration, Conformance conformance) {
            var values = new ArrayList<String>(List.of(scene, "" + width, "" + height, "" + iterations,
                    "" + c[0], "" + c[1], backend, phase, "" + iteration, ms(totalNanos)));
            if (preview == null) {
                for (int i = 0; i < 4; i++) values.add("");
                values.add("");
            } else {
                for (long nanos : new long[]{preview.setupNanos(), preview.uploadNanos(), preview.dispatchNanos(),
                        preview.readbackNanos()}) values.add(ms(nanos));
                values.add("" + preview.nativeBytes());
            }
            values.add("" + conformance.differing);
            values.add("" + conformance.outliers);
            values.add("" + conformance.maximumChannelError);
            return String.join(",", values);
        }

        private static String ms(long nanos) {
            return String.format(Locale.ROOT, "%.6f", nanos / 1e6);
        }
    }
}
