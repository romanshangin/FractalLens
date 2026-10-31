package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.ui.FractalCamera;

import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/**
 * Headless probe of viewport working precision for paired frozen builds. Every view is
 * derived by the build's own Viewport/FractalCamera code from identical decimal inputs, so
 * builds differ only in the precision rules under test. No JavaFX publication is timed.
 */
public final class ViewportPrecisionBenchmark {
    private static final int WIDTH = Integer.getInteger("vp.width", 1512);
    private static final int HEIGHT = Integer.getInteger("vp.height", 982);
    private static final int CAMERA_REPETITIONS = 25;
    private static final MathContext TARGET_DIGITS = new MathContext(400, RoundingMode.HALF_EVEN);
    // c = i is a Misiurewicz point: the Mandelbrot boundary stays structured at every depth.
    private static final PreciseComplex MANDELBROT_TARGET = new PreciseComplex("0", "1");
    // Repelling fixed point of z^2 + (-0.8 + 0.156i), which lies on the Julia set.
    private static final PreciseComplex JULIA_TARGET = new PreciseComplex(
            "1.527503118643534632274607931351916169475312417511732493905728256658568636525695786020421879731335118",
            "-0.0759121783522878653764568658687429427997344025257309526545062431394591595504218065971744205889118132");

    private ViewportPrecisionBenchmark() {}

    record Workload(String id, FractalPreset preset, Viewport viewport, List<Viewport> followUps) {}

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path directory = Path.of(System.getProperty("vp.output", "target/viewport-precision-" + Instant.now().toEpochMilli()));
        Files.createDirectories(directory.toAbsolutePath().getParent());
        Files.createDirectory(directory);
        if (System.getProperty("vp.generate") != null) {
            writeInflatedInputs(directory.resolve("inputs.properties"), Integer.getInteger("vp.generate"));
            return;
        }
        int warmups = Integer.getInteger("vp.warmups", 1);
        int runs = Integer.getInteger("vp.runs", 3);
        Properties inputs = new Properties();
        String inputPath = System.getProperty("vp.inputs");
        if (inputPath != null) try (var reader = Files.newBufferedReader(Path.of(inputPath))) { inputs.load(reader); }
        List<Workload> workloads = new ArrayList<>();
        for (String id : System.getProperty("vp.workloads", "mandelbrot-nav-1e30").split(",")) {
            workloads.add(workload(id.strip(), inputs));
        }
        try (var manifest = new PrintWriter(Files.newBufferedWriter(directory.resolve("workloads.csv"), StandardOpenOption.CREATE_NEW))) {
            manifest.println("workload,preset,width,height,cap,viewport_precision,grid_precision,center_digits,scale_digits,depth,follow_ups");
            for (Workload w : workloads) {
                RenderJob job = job(w.preset(), w.viewport());
                manifest.printf("%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n", w.id(), w.preset(), WIDTH, HEIGHT, job.maxIterations(),
                        w.viewport().mathContext().getPrecision(), job.preciseGrid().mathContext().getPrecision(),
                        Math.max(w.viewport().center().real().precision(), w.viewport().center().imaginary().precision()),
                        w.viewport().scaleExact().precision(), depth(w.viewport().scaleExact()), w.followUps().size());
            }
        }
        Files.writeString(directory.resolve("environment.txt"), "label=" + System.getProperty("vp.label", "current")
                + "\nrevision=" + System.getProperty("vp.revision", "unspecified")
                + "\njava=" + System.getProperty("java.runtime.version") + "\nos=" + System.getProperty("os.name")
                + " " + System.getProperty("os.version") + "\narch=" + System.getProperty("os.arch")
                + "\nprocessors=" + Runtime.getRuntime().availableProcessors() + "\nmax_heap_bytes=" + Runtime.getRuntime().maxMemory()
                + "\nwarmups=" + warmups + "\nsamples=" + runs + "\n", StandardOpenOption.CREATE_NEW);
        try (var csv = new PrintWriter(Files.newBufferedWriter(directory.resolve("samples.csv"), StandardOpenOption.CREATE_NEW))) {
            csv.println("workload,step,phase,run,backend,cap,reference_ms,additional_reference_ms,additional_references,first_region_ms,backend_ms,"
                    + "grid_ms,wheel_zoom_ms,pan_ms,copy_ms,go_to_ms,restore_ms,pan_reuse_plan_ms,pan_reused_pixels,complete,sample_hash");
            for (Workload w : workloads) {
                for (int run = -warmups - 1; run < runs; run++) {
                    String phase = run == -warmups - 1 ? "cold" : run < 0 ? "warmup" : "sample";
                    // A cached backend lives for one sequence: follow-up steps may reuse its orbit, a new sequence cannot.
                    try (var mandelbrot = new MandelbrotPerturbationRenderBackend(
                            Math.max(1, Runtime.getRuntime().availableProcessors() - 1), STATS::set);
                         var julia = new JuliaDeepZoomRenderBackend(); var direct = new DirectDoubleRenderBackend()) {
                        var backend = new PrecisionSelectingRenderBackend(direct,
                                new PrecisionSelectingRenderBackend(mandelbrot, julia));
                        List<Viewport> steps = new ArrayList<>();
                        steps.add(w.viewport());
                        steps.addAll(w.followUps());
                        for (int step = 0; step < steps.size(); step++) {
                            Viewport viewport = steps.get(step);
                            double[] camera = camera(w.preset(), viewport);
                            Measurement m = render(backend, job(w.preset(), viewport));
                            // Planning reuse for a small drag is per-gesture work on the source view's precision.
                            RenderJob dragged = job(w.preset(), viewport.shiftedByPixels(13, -7, WIDTH, HEIGHT));
                            long planStarted = System.nanoTime();
                            FrameReuseResult reuse = new FrameReusePlanner().plan(m.frame(), null, dragged).result();
                            double planMs = (System.nanoTime() - planStarted) / 1e6;
                            csv.printf(Locale.ROOT, "%s,%d,%s,%d,%s,%d,%.6f,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%s,%d%n",
                                    w.id(), step, phase, run, m.backend(), m.frame().job().maxIterations(), m.referenceMs(),
                                    m.additionalReferenceMs(), m.additionalReferences(), m.firstRegionMs(), m.backendMs(),
                                    camera[0], camera[1], camera[2], camera[3], camera[4], camera[5],
                                    planMs, reuse.reusedPixels(), m.frame().isComplete(), BaselineBenchmark.sampleHash(m.frame()));
                            csv.flush();
                            if (!m.frame().isComplete()) throw new IllegalStateException("Incomplete frame " + w.id());
                            if (run == 0 && Boolean.getBoolean("vp.dump")) dump(directory.resolve(w.id() + "-" + step + ".iterations.gz"), m.frame());
                        }
                    }
                }
                System.out.println("Completed " + w.id());
            }
            if (csv.checkError()) throw new IOException("Failed to write samples");
        }
    }

    private static final AtomicReference<DeepZoomTimingStats> STATS = new AtomicReference<>();

    record Measurement(RenderFrame frame, String backend, double referenceMs, double additionalReferenceMs,
                       int additionalReferences, double firstRegionMs, double backendMs) {}

    private static Measurement render(PrecisionSelectingRenderBackend backend, RenderJob job) throws InterruptedException {
        RenderBackend selected = backend.select(job);
        if (selected instanceof PrecisionSelectingRenderBackend nested) selected = nested.select(job);
        RenderFrame frame = RenderFrame.create(job);
        STATS.set(null);
        double referenceMs = -1;
        if (selected instanceof JuliaDeepZoomRenderBackend) {
            // The Julia backend reports no phases; time its exact-orbit construction separately.
            long started = System.nanoTime();
            if (JuliaReferenceSampler.create(job, job.preciseGrid(), () -> false) == null) throw new IllegalStateException("No Julia orbit");
            referenceMs = (System.nanoTime() - started) / 1e6;
        }
        AtomicLong first = new AtomicLong(-1);
        long start = System.nanoTime();
        selected.render(frame, () -> false, region -> first.compareAndSet(-1, System.nanoTime()), null);
        long end = System.nanoTime();
        DeepZoomTimingStats stats = STATS.get();
        if (stats != null) referenceMs = stats.referenceOrbitMs();
        return new Measurement(frame, selected.getClass().getSimpleName(), referenceMs,
                stats == null ? -1 : stats.additionalReferenceOrbitMs(), stats == null ? -1 : stats.additionalReferenceOrbitCount(),
                first.get() < 0 ? -1 : (first.get() - start) / 1e6, (end - start) / 1e6);
    }

    /** Median milliseconds of grid, wheel zoom, pan, Copy, Go to and restore arithmetic on this view. */
    private static double[] camera(FractalPreset preset, Viewport viewport) {
        FractalCamera camera = new FractalCamera(preset, viewport);
        Viewport home = camera.defaultViewport(WIDTH, HEIGHT);
        List<Supplier<Object>> operations = List.of(
                () -> PreciseRenderGrid.from(viewport, WIDTH, HEIGHT),
                () -> viewport.zoomAt(BigDecimal.valueOf(WIDTH * 0.37), BigDecimal.valueOf(HEIGHT * 0.61), WIDTH, HEIGHT, new BigDecimal("0.5")),
                () -> viewport.shiftedByPixels(13, -7, WIDTH, HEIGHT),
                () -> copyText(home, viewport),
                () -> camera.destination(viewport.center(), new BigDecimal(zoomText(home, viewport)), WIDTH, HEIGHT),
                () -> new Viewport(viewport.center().realText(), viewport.center().imaginaryText(), viewport.scaleExact().toPlainString()));
        double[] medians = new double[operations.size()];
        for (int i = 0; i < operations.size(); i++) {
            double[] times = new double[CAMERA_REPETITIONS];
            for (int r = 0; r < times.length; r++) {
                long started = System.nanoTime();
                if (operations.get(i).get() == null) throw new IllegalStateException();
                times[r] = (System.nanoTime() - started) / 1e6;
            }
            Arrays.sort(times);
            medians[i] = times[times.length / 2];
        }
        return medians;
    }

    /** Mirrors Copy Coordinates and Zoom before PR #52: full center digits and zoom at the view's precision. */
    private static String copyText(Viewport home, Viewport viewport) {
        return "Real: " + viewport.center().real().stripTrailingZeros().toPlainString()
                + "\nImaginary: " + viewport.center().imaginary().stripTrailingZeros().toPlainString()
                + "\nZoom: " + zoomText(home, viewport);
    }

    private static String zoomText(Viewport home, Viewport viewport) {
        return home.scaleExact().divide(viewport.scaleExact(), viewport.mathContext()).stripTrailingZeros().toPlainString();
    }

    private static Workload workload(String id, Properties inputs) throws IOException {
        String[] parts = id.split("-", 3);
        FractalPreset preset = FractalPreset.valueOf(parts[0].toUpperCase(Locale.ROOT));
        PreciseComplex target = preset == FractalPreset.JULIA ? JULIA_TARGET : MANDELBROT_TARGET;
        return switch (parts[1]) {
            // Wheel navigation at the requested depth, e.g. mandelbrot-nav-1e80.
            case "nav" -> new Workload(id, preset, navigated(preset, target, scale(parts[2])), List.of());
            // Two wheel zoom-outs at the window centre after the deep view, each rendered with the same backend.
            case "zoomout" -> {
                Viewport deep = navigated(preset, target, scale(parts[2]));
                Viewport once = deep.zoomAt(center(WIDTH), center(HEIGHT), WIDTH, HEIGHT, new BigDecimal("2"));
                yield new Workload(id, preset, deep, List.of(once, once.zoomAt(center(WIDTH), center(HEIGHT), WIDTH, HEIGHT, new BigDecimal("2"))));
            }
            // This build's own Copy/Go to/restore cycles, e.g. mandelbrot-cycled-30x1e30.
            case "cycled" -> {
                String[] spec = parts[2].split("x", 2);
                yield new Workload(id, preset, cycled(preset, navigated(preset, target, scale(spec[1])), Integer.parseInt(spec[0])), List.of());
            }
            // A shared, previously inflated view restored from decimal text, e.g. mandelbrot-restored-k30.
            case "restored" -> new Workload(id, preset, restored(inputs, parts[0] + "-" + parts[2]), List.of());
            // The exact view attached to the Julia pixelation report.
            case "reported" -> new Workload(id, preset, ReportedJuliaFixture.viewport(), List.of());
            default -> throw new IllegalArgumentException("Unknown workload " + id);
        };
    }

    /** Places the target off-centre at eight times the scale, then applies three wheel zoom-ins and one pinch. */
    private static Viewport navigated(FractalPreset preset, PreciseComplex target, BigDecimal scale) {
        Viewport home = preset.defaultViewport();
        BigDecimal start = scale.multiply(BigDecimal.valueOf(8));
        PreciseComplex offset = new PreciseComplex(
                target.real().add(start.multiply(new BigDecimal("0.0731")), TARGET_DIGITS),
                target.imaginary().subtract(start.multiply(new BigDecimal("0.0419")), TARGET_DIGITS));
        Viewport view = home.withCalculatedValues(offset, start);
        view = view.withCalculatedValues(new PreciseComplex(view.center().real().round(view.mathContext()),
                view.center().imaginary().round(view.mathContext())), start);
        for (int i = 0; i < 3; i++) {
            view = view.zoomAt(BigDecimal.valueOf(WIDTH * 0.43), BigDecimal.valueOf(HEIGHT * 0.57), WIDTH, HEIGHT, new BigDecimal("0.5"));
        }
        // A final pinch step gives the scale a non-terminating quotient against the fitted home view.
        return view.zoomAt(BigDecimal.valueOf(WIDTH * 0.43), BigDecimal.valueOf(HEIGHT * 0.57), WIDTH, HEIGHT, new BigDecimal("0.9"));
    }

    /** One cycle: a small drag, Copy, Go to with a re-entered zoom, then a session save/restore. */
    static Viewport cycled(FractalPreset preset, Viewport view, int cycles) {
        FractalCamera camera = new FractalCamera(preset, view);
        Viewport home = camera.defaultViewport(WIDTH, HEIGHT);
        for (int i = 0; i < cycles; i++) {
            // The drag fills the coordinates to the working precision, as navigation between saves does.
            Viewport dragged = view.shiftedByPixels(13, -7, WIDTH, HEIGHT);
            Viewport moved = camera.destination(dragged.center(), new BigDecimal(zoomText(home, dragged)), WIDTH, HEIGHT);
            view = new Viewport(moved.center().realText(), moved.center().imaginaryText(), moved.scaleExact().toPlainString());
        }
        return view;
    }

    private static void writeInflatedInputs(Path path, int cycles) throws IOException {
        Properties properties = new Properties();
        for (FractalPreset preset : List.of(FractalPreset.MANDELBROT, FractalPreset.JULIA)) {
            PreciseComplex target = preset == FractalPreset.JULIA ? JULIA_TARGET : MANDELBROT_TARGET;
            Viewport inflated = cycled(preset, navigated(preset, target, new BigDecimal("1e-30")), cycles);
            String key = preset.name().toLowerCase(Locale.ROOT) + "-k" + cycles;
            properties.setProperty(key + ".real", inflated.center().realText());
            properties.setProperty(key + ".imaginary", inflated.center().imaginaryText());
            properties.setProperty(key + ".scale", inflated.scaleExact().toPlainString());
        }
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            properties.store(writer, "Inflated views from this build's Copy/Go to/restore cycle");
        }
    }

    private static Viewport restored(Properties inputs, String key) {
        String real = inputs.getProperty(key + ".real");
        if (real == null) throw new IllegalArgumentException("Missing restored input " + key);
        return new Viewport(real, inputs.getProperty(key + ".imaginary"), inputs.getProperty(key + ".scale"));
    }

    private static RenderJob job(FractalPreset preset, Viewport viewport) {
        int cap = new IterationSettings().maxIterations(preset.defaultViewport().scaleExact(), viewport.scaleExact());
        return new RenderJob(FormulaDefinition.forPreset(preset, OrbitTrap.NONE), viewport, WIDTH, HEIGHT, cap);
    }

    private static void dump(Path path, RenderFrame frame) throws IOException {
        try (var output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))))) {
            SamplePlane plane = frame.samplePlane();
            for (int i = 0; i < plane.size(); i++) {
                output.writeInt(plane.escaped(i) ? plane.iterations(i) : -1);
                output.writeDouble(plane.smoothIterations(i));
            }
        }
    }

    private static BigDecimal center(int size) { return BigDecimal.valueOf(size - 1L).divide(BigDecimal.valueOf(2)); }
    private static BigDecimal scale(String text) { return new BigDecimal(text); }
    private static int depth(BigDecimal scale) { return Math.max(0, scale.scale() - scale.precision() + 1); }
}
