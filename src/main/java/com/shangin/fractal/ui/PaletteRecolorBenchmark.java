package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.formula.MandelbrotFormula;
import com.shangin.fractal.gpu.*;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * Opt-in interactive CPU/GPU comparison. Calculated Mandelbrot fixtures and a
 * deterministic sparse AA mask are prepared outside the palette measurements.
 * Every frame crosses the same SurfaceBuffer publication boundary as the app.
 */
public final class PaletteRecolorBenchmark extends Application {
    private static volatile Throwable failure;
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private Thread worker;
    private SurfaceBuffer surface;
    private Stage stage;
    private ImageView view;
    private AnimationTimer pulseTimer;
    private double outputScale;
    private final List<Row> rows = new ArrayList<>();

    public static void main(String[] args) {
        launch(args);
        if (failure != null) throw new IllegalStateException("Palette benchmark failed", failure);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        view = new ImageView();
        view.setPreserveRatio(true);
        stage.setScene(new Scene(new StackPane(view), 960, 620));
        stage.setTitle("FractalUI — Palette benchmark");
        stage.show();
        outputScale = stage.getOutputScaleX();
        pulseTimer = new AnimationTimer() {
            @Override public void handle(long now) {
                Pending ready = pending.getAndSet(null);
                if (ready != null) ready.future.complete(new Publication(ready.timing,
                        System.nanoTime() - ready.updatedAt));
            }
        };
        pulseTimer.start();
        worker = new Thread(this::runBenchmark, "palette-benchmark");
        worker.start();
    }

    private void runBenchmark() {
        Path output = Path.of(System.getProperty("fractal.benchmark.output", "target/palette-benchmark.csv"));
        try (GpuRuntime runtime = GpuRuntimeFactory.createDefault()) {
            int warmup = Integer.getInteger("fractal.benchmark.warmup", 12);
            int samples = Integer.getInteger("fractal.benchmark.samples", 40);
            boolean cpuOnly = Boolean.getBoolean("fractal.benchmark.cpuOnly");
            if (warmup < 1 || samples < 1) throw new IllegalArgumentException("Warmup and samples must be positive");
            if (!cpuOnly && !runtime.isUsableFor(GpuNumericCapability.FLOAT32)) {
                throw new IllegalStateException("GPU comparison requires a working GPU: " + runtime.capabilityReport());
            }
            System.out.println("Palette benchmark: " + runtime.capabilityReport());
            System.out.println("Java " + System.getProperty("java.version") + "; " + System.getProperty("os.name")
                    + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
                    + "; processors=" + Runtime.getRuntime().availableProcessors() + "; outputScale=" + outputScale);
            System.out.println("Dispatch = host encode/submit/fence; publication = array copy + PixelBuffer update.");
            System.out.println("Next-pulse wait is reported separately; it is not display scanout latency.");
            var cpu = new PaletteRecolorBackend(runtime, false);
            var gpu = new PaletteRecolorBackend(runtime, true);
            for (String dimensions : System.getProperty("fractal.benchmark.sizes", "1512x982,3024x1964").split(",")) {
                String[] parts = dimensions.strip().split("x");
                if (parts.length != 2) throw new IllegalArgumentException("Expected widthxheight");
                int width = Integer.parseInt(parts[0]), height = Integer.parseInt(parts[1]);
                if (width < 1 || height < 1 || (long) width * height > 16_777_216) {
                    throw new IllegalArgumentException("Fixture exceeds 16M-pixel benchmark limit");
                }
                System.out.println("Preparing Mandelbrot fixture " + width + "x" + height);
                Fixture fixture = fixture(width, height);
                configureSurface(width, height);
                for (boolean antialias : new boolean[]{false, true}) {
                    AntialiasSampleCache.Snapshot aa = antialias ? fixture.aa : AntialiasSampleCache.Snapshot.EMPTY;
                    String label = width + "x" + height + (antialias ? "/AA" : "/base");
                    int[] cpuColors = new int[fixture.base.size()], gpuColors = new int[fixture.base.size()];
                    for (int iteration = -warmup - 1; iteration < samples; iteration++) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        boolean cold = iteration == -warmup - 1;
                        String phase = cold ? "cold" : iteration < 0 ? "warmup" : "sample";
                        double offset = cold ? 0.0 : (iteration + warmup + 1) * 0.03713 - 0.25;
                        SmoothPaletteColoring coloring = new SmoothPaletteColoring(
                                PalettePreset.ICE.palette(), SmoothPaletteColoring.DEFAULT_COLOR_SCALE, offset);
                        // Alternate which backend goes first, keeping the exact same offset and samples.
                        if (!cpuOnly && (iteration & 1) != 0) {
                            measure(label, phase, iteration, gpu, fixture.base, aa, coloring, gpuColors, true);
                            measure(label, phase, iteration, cpu, fixture.base, aa, coloring, cpuColors, false);
                        } else {
                            measure(label, phase, iteration, cpu, fixture.base, aa, coloring, cpuColors, false);
                            if (!cpuOnly) measure(label, phase, iteration, gpu, fixture.base, aa, coloring, gpuColors, true);
                        }
                        if (!cpuOnly) verifyColors(cpuColors, gpuColors, antialias);
                    }
                    summarize(label, "CPU");
                    if (!cpuOnly) summarize(label, "GPU");
                    writeCsv(output, runtime.capabilityReport().toString());
                }
            }
            System.out.println("Benchmark complete: " + output.toAbsolutePath());
        } catch (Throwable error) {
            failure = error;
            error.printStackTrace();
        } finally {
            Platform.runLater(Platform::exit);
        }
    }

    private void configureSurface(int width, int height) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                surface = new SurfaceBuffer(width, height);
                view.setImage(surface.image());
                var bounds = Screen.getPrimary().getVisualBounds();
                double logicalWidth = Math.min(width / outputScale, bounds.getWidth() - 80);
                double logicalHeight = Math.min(height / outputScale, bounds.getHeight() - 100);
                view.setFitWidth(logicalWidth);
                view.setFitHeight(logicalHeight);
                stage.getScene().getWindow().setWidth(logicalWidth + 20);
                stage.getScene().getWindow().setHeight(logicalHeight + 50);
                System.out.printf(Locale.ROOT, "Buffer %dx%d; view %.0fx%.0f logical; scale %.1f%n",
                        width, height, logicalWidth, logicalHeight, stage.getOutputScaleX());
                ready.complete(null);
            } catch (Throwable error) { ready.completeExceptionally(error); }
        });
        ready.get(15, TimeUnit.SECONDS);
    }

    private void measure(String label, String phase, int iteration, PaletteRecolorBackend backend,
                         BaseColorPhaseCache base, AntialiasSampleCache.Snapshot aa,
                         SmoothPaletteColoring coloring, int[] colors, boolean requireGpu) throws Exception {
        PaletteRecolorTiming timing = backend.recolor(base, aa, coloring, colors);
        if (requireGpu && !timing.gpuUsed()) throw new IllegalStateException("GPU silently fell back in " + label);
        long queued = System.nanoTime();
        CompletableFuture<Publication> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                long started = System.nanoTime();
                surface.publish(colors);
                long updated = System.nanoTime();
                PaletteRecolorTiming published = timing.withPublication(started - queued, updated - started);
                if (!pending.compareAndSet(null, new Pending(future, published, updated))) {
                    throw new IllegalStateException("More than one publication in flight");
                }
                Platform.requestNextPulse();
            } catch (Throwable error) { future.completeExceptionally(error); }
        });
        Publication publication = future.get(15, TimeUnit.SECONDS);
        if (!phase.equals("warmup")) {
            rows.add(new Row(label, requireGpu ? "GPU" : "CPU", phase, iteration,
                    coloring.offset(), publication.timing, publication.nextPulseNanos));
        }
    }

    private static Fixture fixture(int width, int height) {
        int maxIterations = 300;
        var data = new FractalData(width, height, maxIterations);
        var grid = RenderGrid.from(new Viewport(-0.75, 0.0, 2.4), width, height);
        var formula = new MandelbrotFormula();
        IntStream.range(0, data.size()).parallel().forEach(i ->
                data.set(i, formula.calculate(grid.realAt(i % width), grid.imaginaryAt(i / width), maxIterations)));
        var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
        BaseColorPhaseCache base = BaseColorPhaseCache.create(data, coloring);
        var aa = new AntialiasSampleCache();
        // Fixed 6.25% mask, alternating 2x2 and 4x4 sample grids, independent of palette.
        IntStream.range(0, (data.size() + 15) / 16).forEach(candidate -> {
            int i = candidate * 16;
            int side = (candidate & 1) == 0 ? 2 : 4;
            FractalSample[] samples = new FractalSample[side * side];
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    double re = grid.realAt(i % width) + ((x + 0.5) / side - 0.5) * grid.realStep();
                    double im = grid.imaginaryAt(i / width) - ((y + 0.5) / side - 0.5) * grid.imaginaryStep();
                    samples[y * side + x] = formula.calculate(re, im, maxIterations);
                }
            }
            aa.put(i, samples, coloring);
        });
        System.out.println("Fixture ready: " + base.size() + " base pixels, " + aa.size() + " AA pixels");
        return new Fixture(base, aa.snapshotFor(coloring));
    }

    private static void verifyColors(int[] cpu, int[] gpu, boolean antialias) {
        for (int i = 0; i < cpu.length; i++) {
            if (cpu[i] == gpu[i]) continue;
            int tolerance = antialias && i % 16 == 0 ? 1 : 0;
            if ((cpu[i] >>> 24) != (gpu[i] >>> 24)) throw new IllegalStateException("Alpha mismatch at " + i);
            for (int shift = 0; shift < 24; shift += 8) {
                if (Math.abs((cpu[i] >>> shift & 255) - (gpu[i] >>> shift & 255)) > tolerance) {
                    throw new IllegalStateException("Color mismatch at " + i + ": CPU="
                            + Integer.toHexString(cpu[i]) + " GPU=" + Integer.toHexString(gpu[i]));
                }
            }
        }
    }

    private void summarize(String label, String backend) {
        List<Row> selected = rows.stream().filter(r -> r.fixture.equals(label)
                && r.backend.equals(backend) && r.phase.equals("sample")).toList();
        long[] total = selected.stream().mapToLong(r -> r.timing.totalNanos()).sorted().toArray();
        long[] endToEnd = selected.stream().mapToLong(r -> r.timing.endToEndNanos()).sorted().toArray();
        long[] publish = selected.stream().mapToLong(r -> r.timing.presentationNanos()).sorted().toArray();
        System.out.printf(Locale.ROOT, "%-20s %s recolor median=%.3fms p95=%.3fms; publish=%.3fms; to-buffer=%.3fms%n",
                label, backend, percentile(total, .5), percentile(total, .95),
                percentile(publish, .5), percentile(endToEnd, .5));
    }

    private static double percentile(long[] sorted, double p) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * p) - 1)] / 1_000_000.0;
    }

    private void writeCsv(Path output, String gpu) throws Exception {
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("# " + gpu + "\n");
            writer.write("# outputScale=" + outputScale + "; warmup=" + Integer.getInteger("fractal.benchmark.warmup", 12)
                    + "; samples=" + Integer.getInteger("fractal.benchmark.samples", 40) + "\n");
            writer.write("fixture,backend,phase,iteration,offset,prepare_ns,upload_ns,dispatch_ns,readback_ns,total_ns,fx_queue_ns,publish_ns,to_buffer_ns,next_pulse_ns,uploaded_bytes\n");
            for (Row row : rows) {
                PaletteRecolorTiming t = row.timing;
                writer.write(String.format(Locale.ROOT, "%s,%s,%s,%d,%.8f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                        row.fixture, row.backend, row.phase, row.iteration, row.offset,
                        t.preparationNanos(), t.uploadNanos(), t.dispatchNanos(), t.readbackNanos(), t.totalNanos(),
                        t.javafxQueueNanos(), t.presentationNanos(), t.endToEndNanos(), row.nextPulseNanos,
                        t.uploadedBytes()));
            }
        }
    }

    @Override
    public void stop() {
        if (pulseTimer != null) pulseTimer.stop();
        if (worker != null && worker.isAlive()) worker.interrupt();
    }

    private record Fixture(BaseColorPhaseCache base, AntialiasSampleCache.Snapshot aa) {}
    private record Pending(CompletableFuture<Publication> future, PaletteRecolorTiming timing, long updatedAt) {}
    private record Publication(PaletteRecolorTiming timing, long nextPulseNanos) {}
    private record Row(String fixture, String backend, String phase, int iteration, double offset,
                       PaletteRecolorTiming timing, long nextPulseNanos) {}
}
