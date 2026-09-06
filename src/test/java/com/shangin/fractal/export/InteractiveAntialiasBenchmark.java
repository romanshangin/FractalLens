package com.shangin.fractal.export;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.SamplingPattern;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Headless production AA measurement; base rendering and exact color checks are untimed. */
public final class InteractiveAntialiasBenchmark {
    private InteractiveAntialiasBenchmark() {}

    public static void main(String[] args) throws Exception {
        int warmups = Integer.getInteger("aaBenchmark.warmups", 3);
        int runs = Integer.getInteger("aaBenchmark.runs", 10);
        boolean profile = Boolean.getBoolean("fractal.aa.profile");
        boolean resume = Boolean.getBoolean("aaBenchmark.resume");
        String label = System.getProperty("aaBenchmark.label", "current");
        SamplingPattern pattern = SamplingPattern.valueOf(System.getProperty("aaBenchmark.pattern", "REGULAR"));
        Path output = Path.of(System.getProperty("aaBenchmark.output", "target/aa-benchmark.csv"));
        if (warmups < 1 || runs < 1) throw new IllegalArgumentException("Positive warmups/runs required");
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(output));
             var backend = new PrecisionSelectingRenderBackend(
                     new DirectDoubleRenderBackend(), new MandelbrotPerturbationRenderBackend());
             var service = new InteractiveAntialiasService()) {
            csv.printf("# Java %s; %s %s; processors=%d; maxHeap=%d; time=%s%n",
                    System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.version"),
                    Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(), java.time.Instant.now());
            csv.println("label,scene,width,height,pattern,resume,cache_bytes,profile,phase,run,aa_ms,first_tile_ms,tiles,candidates,samples,checksum");
            var coloring = new SmoothPaletteColoring(PalettePreset.ICE.palette());
            for (String size : System.getProperty("aaBenchmark.sizes", "1512x982,3024x1964").split(",")) {
                String[] dimensions = size.split("x");
                int width = Integer.parseInt(dimensions[0]), height = Integer.parseInt(dimensions[1]);
                if (width < 2 || height < 2 || (long) width * height > 8_000_000) {
                    throw new IllegalArgumentException("Size outside benchmark bounds");
                }
                for (String scene : System.getProperty("aaBenchmark.scenes", "overview,seahorse,julia").split(",")) {
                    FractalPreset preset = scene.equals("julia") ? FractalPreset.JULIA : FractalPreset.MANDELBROT;
                    Viewport viewport = switch (scene) {
                        case "overview" -> new Viewport(-0.75, 0, 2.4);
                        case "exterior" -> new Viewport(1, 1, 0.2);
                        case "seahorse" -> new Viewport(-0.743643887037151, 0.13182590420533, 0.024);
                        case "julia" -> preset.defaultViewport();
                        case "deep" -> new Viewport("-0.8317528516858322713653476366999",
                                "0.207813754242134522471317257011028", "1.6e-13");
                        default -> throw new IllegalArgumentException("Unknown scene " + scene);
                    };
                    int cap = scene.equals("deep") ? 2488 : 300;
                    for (int run = -warmups - 1; run < runs; run++) {
                        String phase = run == -warmups - 1 ? "cold" : run < 0 ? "warmup" : "sample";
                        // Deep dispatch requires the preset identity; keep the existing direct fixtures unchanged.
                        RenderJob job = scene.equals("deep")
                                ? new RenderJob(FormulaDefinition.forPreset(preset, OrbitTrap.NONE),
                                viewport, width, height, cap)
                                : new RenderRequest(new FractalCalculator(preset.createFormula()),
                                viewport, width, height, cap);
                        RenderFrame frame = RenderFrame.create(job);
                        backend.render(frame, () -> false, ignored -> {}, null);
                        if (resume) measure(service, frame, coloring, pattern, scene.equals("deep"));
                        Measurement result = measure(service, frame, coloring, pattern, scene.equals("deep"));
                        // The scalar baseline can write full ARGB fixtures; other builds compare every pixel.
                        verifyPixels(scene + "-" + size + "-" + pattern + "-" + resume + "-"
                                + AntialiasSampleCache.DEFAULT_MAX_BYTES, result.pixels);
                        var details = service.lastProfile();
                        csv.printf(Locale.ROOT, "%s,%s,%d,%d,%s,%s,%d,%s,%s,%d,%.6f,%.6f,%d,%d,%d,%d%n",
                                label, scene, width, height, pattern, resume, AntialiasSampleCache.DEFAULT_MAX_BYTES,
                                profile, phase, run, result.total / 1e6, result.first / 1e6,
                                result.tiles, details.candidates(), details.samples(), Arrays.hashCode(result.pixels));
                        csv.flush();
                        System.out.printf(Locale.ROOT, "%s %s %s %s %d: AA %.2f ms; first %.2f ms%n",
                                label, size, scene, phase, run, result.total / 1e6, result.first / 1e6);
                        if (profile) System.out.println(details);
                    }
                }
            }
        }
    }

    private static Measurement measure(InteractiveAntialiasService service, RenderFrame frame,
                                       SmoothPaletteColoring coloring, SamplingPattern pattern, boolean deep)
            throws Exception {
        int width = frame.samplePlane().width(), height = frame.samplePlane().height();
        int[] pixels = new int[width * height];
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicLong first = new AtomicLong();
        java.util.concurrent.atomic.AtomicInteger tiles = new java.util.concurrent.atomic.AtomicInteger();
        long start = System.nanoTime();
        java.util.function.BiConsumer<RenderRegion, int[]> publish = (region, colors) -> {
            first.compareAndSet(0, System.nanoTime() - start);
            tiles.incrementAndGet();
            for (int y = 0; y < region.height(); y++) {
                System.arraycopy(colors, y * region.width(), pixels,
                        (region.y() + y) * width + region.x(), region.width());
            }
        };
        if (deep) {
            service.refineDeep(frame, coloring, pattern, RefinedPixelSnapshot.empty(width, height),
                    Runnable::run, publish, () -> done.complete(null), done::completeExceptionally);
        } else {
            service.refine(frame, coloring, pattern, RefinedPixelSnapshot.empty(width, height),
                    Runnable::run, publish, () -> done.complete(null), done::completeExceptionally);
        }
        done.get(180, TimeUnit.SECONDS);
        return new Measurement(pixels, System.nanoTime() - start, first.get(), tiles.get());
    }

    private static void verifyPixels(String key, int[] pixels) throws IOException {
        String directory = System.getProperty("aaBenchmark.reference");
        if (directory == null) return;
        Path path = Path.of(directory, key + ".argb");
        if (!Files.exists(path) && Boolean.getBoolean("aaBenchmark.writeReference")) {
            Files.createDirectories(path.getParent());
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                for (int pixel : pixels) output.writeInt(pixel);
            }
        } else {
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                for (int i = 0; i < pixels.length; i++) {
                    if (input.readInt() != pixels[i]) throw new IllegalStateException("AA color mismatch " + key + " at " + i);
                }
                if (input.read() != -1) throw new IllegalStateException("Incorrect reference size " + path);
            }
        }
    }

    private record Measurement(int[] pixels, long total, long first, int tiles) {}
}
