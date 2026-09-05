package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.*;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;

import java.nio.IntBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Opt-in off-screen feasibility gate; it never participates in application backend selection. */
public final class GpuResidentRenderBenchmark {
    private static final String HEADER = "scene,width,height,backend,phase,iteration,total_ms,allocation_ms,axis_pack_ms,upload_ms,calculation_ms,rejection_readback_ms,recovery_ms,correction_upload_ms,coloring_ms,color_readback_ms,certified,recovered,uncertain_interval,bounded_mismatch,iteration_mismatch,invalid_interval,smooth_error,phase_mismatch,other,native_bytes,uploaded_bytes,readback_bytes,differing_colors,outlier_colors,max_channel_error";

    private GpuResidentRenderBenchmark() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(System.getProperty(
                "fractal.residentBenchmark.output", "target/gpu-resident-benchmark.csv"));
        int warmup = Integer.getInteger("fractal.residentBenchmark.warmup", 2);
        int samples = Integer.getInteger("fractal.residentBenchmark.samples", 7);
        if (warmup < 1 || samples < 1) throw new IllegalArgumentException("Positive warmup and samples required");
        var rows = new ArrayList<String>();
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault();
             DirectDoubleRenderBackend cpu = new DirectDoubleRenderBackend()) {
            if (!runtime.isUsableFor(GpuNumericCapability.FLOAT32)) {
                throw new IllegalStateException("Resident benchmark requires a working GPU: "
                        + runtime.capabilityReport());
            }
            String metadata = "# " + runtime.capabilityReport() + "\n# Java "
                    + System.getProperty("java.version") + "; " + System.getProperty("os.name") + " "
                    + System.getProperty("os.version") + "; processors="
                    + Runtime.getRuntime().availableProcessors() + "; maxHeap="
                    + Runtime.getRuntime().maxMemory() + "; time=" + Instant.now() + "\n";
            System.out.print(metadata);
            for (String size : System.getProperty(
                    "fractal.residentBenchmark.sizes", "1512x982,3024x1964").split(",")) {
                String[] dimensions = size.split("x");
                int width = Integer.parseInt(dimensions[0]), height = Integer.parseInt(dimensions[1]);
                if (width < 2 || height < 2 || (long) width * height > 8_000_000) {
                    throw new IllegalArgumentException("Size outside resident benchmark bounds");
                }
                for (String scene : System.getProperty(
                        "fractal.residentBenchmark.scenes", "overview,exterior,seahorse").split(",")) {
                    Viewport viewport = switch (scene) {
                        case "overview" -> new Viewport(-0.75, 0, 2.4);
                        case "exterior" -> new Viewport(1, 1, 0.2);
                        case "seahorse" -> new Viewport(-0.743643887037151, 0.13182590420533, 0.024);
                        default -> throw new IllegalArgumentException("Unknown scene " + scene);
                    };
                    RenderJob job = new RenderJob(
                            FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                            viewport, width, height, 300, RenderPriority.center(), Optional.empty());
                    var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
                    for (int iteration = -warmup - 1; iteration < samples; iteration++) {
                        String phase = iteration == -warmup - 1 ? "cold" : iteration < 0 ? "warmup" : "sample";
                        Measurement[] pair = new Measurement[2];
                        for (int order = 0; order < 2; order++) {
                            int backend = (iteration + order) & 1;
                            pair[backend] = backend == 0
                                    ? measureCpu(cpu, job, coloring)
                                    : measureGpu(runtime, job, coloring);
                        }
                        Conformance conformance = compare(pair[0].colors, pair[1].colors);
                        rows.add(pair[0].csv(scene, width, height, false, phase, iteration, conformance));
                        rows.add(pair[1].csv(scene, width, height, true, phase, iteration, conformance));
                        Files.createDirectories(output.toAbsolutePath().getParent());
                        Files.writeString(output, metadata + HEADER + "\n" + String.join("\n", rows) + "\n");
                        System.out.printf(Locale.ROOT,
                                "%s %s %s %d: CPU %.2f ms; resident GPU %.2f ms; recovered %d; colors %d/%d%n",
                                size, scene, phase, iteration, pair[0].totalNanos / 1e6,
                                pair[1].totalNanos / 1e6, pair[1].result.recoveredPixels(),
                                conformance.differing, conformance.maximumChannelError);
                    }
                }
            }
            System.out.println("Saved " + output.toAbsolutePath());
        }
    }

    private static Measurement measureCpu(
            DirectDoubleRenderBackend backend, RenderJob job, SmoothPaletteColoring coloring)
            throws InterruptedException {
        long started = System.nanoTime();
        RenderFrame frame = RenderFrame.create(job);
        backend.render(frame, () -> false, ignored -> {}, null);
        int[] colors = new int[job.width() * job.height()];
        new FractalColorizer().color(frame.samplePlane(), IntBuffer.wrap(colors), coloring);
        return new Measurement(colors, System.nanoTime() - started, null);
    }

    private static Measurement measureGpu(
            GpuRuntime runtime, RenderJob job, SmoothPaletteColoring coloring) throws InterruptedException {
        long started = System.nanoTime();
        int[] colors = new int[job.width() * job.height()];
        RenderGrid grid = RenderGrid.from(job.viewport(), job.width(), job.height());
        GpuResidentMandelbrotResult result = runtime.renderResidentMandelbrot(
                new GpuResidentMandelbrotRequest(job, grid, coloring, colors))
                .orElseThrow(() -> new IllegalStateException("Resident GPU fallback: "
                        + runtime.capabilityReport()));
        return new Measurement(colors, System.nanoTime() - started, result);
    }

    private static Conformance compare(int[] expected, int[] actual) {
        int differing = 0, outliers = 0, maximum = 0;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) differing++;
            int pixelMaximum = 0;
            for (int shift : new int[]{24, 16, 8, 0}) {
                pixelMaximum = Math.max(pixelMaximum, Math.abs(
                        ((expected[i] >>> shift) & 0xff) - ((actual[i] >>> shift) & 0xff)));
            }
            maximum = Math.max(maximum, pixelMaximum);
            if (pixelMaximum > 1) outliers++;
        }
        if ((maximum > 1 || differing >= expected.length / 100)
                && !Boolean.getBoolean("fractal.residentBenchmark.allowMismatch")) {
            throw new IllegalStateException("Resident color conformance failed: differing="
                    + differing + "; outliers=" + outliers + "; maximum channel error=" + maximum);
        }
        return new Conformance(differing, outliers, maximum);
    }

    private record Conformance(int differing, int outliers, int maximumChannelError) {}

    private record Measurement(int[] colors, long totalNanos, GpuResidentMandelbrotResult result) {
        private String csv(String scene, int width, int height, boolean gpu,
                           String phase, int iteration, Conformance conformance) {
            var values = new ArrayList<String>(List.of(scene, "" + width, "" + height,
                    gpu ? "GPU" : "CPU", phase, "" + iteration, ms(totalNanos)));
            if (!gpu) {
                for (int i = 0; i < 9; i++) values.add("0.000000");
                for (int i = 0; i < 12; i++) values.add("0");
            } else {
                GpuResidentMandelbrotTiming t = result.timing();
                for (long nanos : new long[]{t.allocationNanos(), t.axisPackNanos(), t.uploadNanos(),
                        t.calculationNanos(), t.rejectionReadbackNanos(), t.recoveryNanos(),
                        t.correctionUploadNanos(), t.coloringNanos(), t.colorReadbackNanos()}) {
                    values.add(ms(nanos));
                }
                values.add("" + result.certifiedPixels());
                values.add("" + result.recoveredPixels());
                GpuResidentRejectionStats rejections = result.rejections();
                for (int value : new int[]{rejections.uncertainInterval(), rejections.boundedMismatch(),
                        rejections.iterationMismatch(), rejections.invalidInterval(),
                        rejections.smoothError(), rejections.phaseMismatch(), rejections.other()}) {
                    values.add("" + value);
                }
                for (long value : new long[]{t.nativeBytes(), t.uploadedBytes(), t.readbackBytes()}) {
                    values.add("" + value);
                }
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
