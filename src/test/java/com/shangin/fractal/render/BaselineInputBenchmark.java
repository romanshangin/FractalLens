package com.shangin.fractal.render;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.scene.*;
import com.shangin.fractal.ui.*;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.input.*;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.nio.IntBuffer;
import com.shangin.fractal.export.InteractiveAntialiasService;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.shangin.fractal.render.BaselineFxBenchmark.fx;

/** Synthetic JavaFX dispatch through the installed production input handlers, seeded by 9.1-v1. */
public final class BaselineInputBenchmark {
    static final String VERSION = "9.1-input-v1";
    static final String HEADER = "trial,fixture,step,seed_width,seed_height,gesture,requested_mode,phase,run,status,width,height,cap,aa,sample_hash,argb_hash,first_input_to_preview_ms,first_input_to_preview_post_layout_ms,last_input_to_render_ms,last_input_to_base_ms,last_input_to_aa_ms,last_input_to_complete_ms,last_input_to_post_layout_ms,last_input_to_first_target_ms,last_input_to_first_target_post_layout_ms,render_count";
    static final String EVENT_HEADER = "trial,epoch,input,render,stage,nanos,duration_nanos";
    static final List<String> GESTURES = List.of("wheel", "wheel-burst", "pinch", "trackpad", "drag");

    private BaselineInputBenchmark() {}

    static List<String> gestures(BaselineFixtures.Fixture fixture) {
        if (fixture.steps().size() > 1) return List.of("sequence");
        if (fixture.id().startsWith("cancel-")) return List.of("replacement");
        List<String> result = List.of(System.getProperty("baseline.input.gestures", "wheel,wheel-burst,pinch,trackpad,drag").split(","));
        if (new HashSet<>(result).size() != result.size() || !GESTURES.containsAll(result)) {
            throw new IllegalArgumentException("Unknown or duplicate input gesture");
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        int warmups = Integer.getInteger("baseline.warmups", 1), runs = Integer.getInteger("baseline.runs", 3);
        if (warmups < 0 || runs < 1) throw new IllegalArgumentException("Invalid sample counts");
        var fixtures = BaselineBenchmark.selectedFixtures();
        var modes = Arrays.stream(System.getProperty("baseline.fx.modes", "FAST,REFINED").split(","))
                .map(InteractiveRenderMode::valueOf).toList();
        if (new HashSet<>(modes).size() != modes.size()) throw new IllegalArgumentException("Duplicate mode");
        for (var fixture : fixtures) {
            gestures(fixture);
            for (var step : fixture.steps()) if (step.job().width() % 2 != 0 || step.job().height() % 2 != 0) {
                throw new IllegalArgumentException("Input targets require even physical dimensions");
            }
        }
        Path output = Path.of(System.getProperty("baseline.output", "target/baseline-input-" + System.currentTimeMillis()));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output);
        try (var manifest = writer(output, "manifest.csv")) {
            manifest.println(BaselineFixtures.HEADER);
            for (var fixture : fixtures) for (var step : fixture.steps()) manifest.println(step.csv(fixture.id()));
        }
        System.setProperty("fractal.gpu.enabled", "false");
        System.setProperty("fractal.gpu.mandelbrot.enabled", "false");
        var lockPath = Path.of(System.getProperty("java.io.tmpdir"), "fractallens-baseline-benchmark.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another baseline benchmark is running");
            var started = new CompletableFuture<Void>();
            Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
            started.get(15, TimeUnit.SECONDS);
            try (var ui = fx(Ui::new); var rows = writer(output, "samples.csv");
                 var events = writer(output, "events.csv"); var actual = writer(output, "actual-manifest.csv")) {
                Files.writeString(output.resolve("environment.txt"), "matrix=" + BaselineFixtures.VERSION + "\ninput_matrix=" + VERSION
                        + "\nstarted=" + java.time.Instant.now() + "\nrevision=" + System.getProperty("baseline.revision", "unspecified")
                        + "\njava=" + System.getProperty("java.runtime.version") + "\njavafx=" + System.getProperty("javafx.runtime.version")
                        + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\narch=" + System.getProperty("os.arch")
                        + "\nprocessors=" + Runtime.getRuntime().availableProcessors() + "\nmax_heap_bytes=" + Runtime.getRuntime().maxMemory()
                        + "\nvm_arguments=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                        + "\noutput_scale=" + fx(() -> ui.stage.getOutputScaleX() + "x" + ui.stage.getOutputScaleY())
                        + "\nmodes=" + String.join(",", modes.stream().map(Enum::name).toList())
                        + "\ngestures=" + System.getProperty("baseline.input.gestures", String.join(",", GESTURES))
                        + "\nwarmups=" + warmups + "\nsamples=" + runs
                        + "\ninput_origin=synthetic_javafx_dispatch\nphysical_scanout=unmeasured\nos_input=unmeasured"
                        + "\ncancel_trigger=queued_input_after_render_submit\nrender_diagnostics=" + RenderDiagnostics.enabled() + "\ncancel_worker_tail=" + (RenderDiagnostics.enabled() ? "base_workers_drained" : "unmeasured") + "\ngpu_calculation=false\n",
                        StandardOpenOption.CREATE_NEW);
                rows.println(HEADER); events.println(EVENT_HEADER); actual.println("trial,role," + BaselineFixtures.HEADER);
                long trial = 0;
                for (var fixture : fixtures) for (var mode : modes) for (String gesture : gestures(fixture)) {
                    for (int run = -warmups - 1; run < runs; run++) {
                        String phase = run == -warmups - 1 ? "first_sequence" : run < 0 ? "warmup" : "sample";
                        ui.seed(fixture.steps().getFirst(), mode);
                        var source = fx(() -> actualStep(ui, fixture.steps().getFirst(), "source"));
                        if (gesture.equals("sequence")) {
                            for (int s = 1; s < fixture.steps().size(); s++) {
                                var step = fixture.steps().get(s);
                                Result r = measure(ui, fixture, step, mode, "sequence", ++trial);
                                save(r, source, phase, run, rows, events, actual);
                                verify(r);
                                source = r.actual;
                            }
                        } else {
                            Result r = measure(ui, fixture, fixture.steps().getFirst(), mode, gesture, ++trial);
                            save(r, source, phase, run, rows, events, actual);
                            verify(r);
                        }
                        System.out.println("Verified " + fixture.id() + " " + mode + " " + gesture + " " + run);
                    }
                }
                if (rows.checkError() || events.checkError() || actual.checkError()) throw new IllegalStateException("CSV write failure");
                Files.writeString(output.resolve("SUCCESS"), "Every completed trial passed exact sample and ARGB CPU controls.\n", StandardOpenOption.CREATE_NEW);
            } finally { Platform.exit(); }
        }
    }

    private static PrintWriter writer(Path output, String name) throws Exception {
        return new PrintWriter(Files.newBufferedWriter(output.resolve(name), StandardOpenOption.CREATE_NEW));
    }

    static final class Ui implements AutoCloseable {
        final Stage stage = new Stage();
        final Pane root = new Pane();
        FractalView view;
        final java.util.concurrent.atomic.AtomicReference<Throwable> asynchronousFailure = new java.util.concurrent.atomic.AtomicReference<>();
        final Thread.UncaughtExceptionHandler previousHandler;
        final Map<String, InteractionLatency.Event> pending = new LinkedHashMap<>();
        boolean replace, replacementQueued;
        CompletableFuture<Void> replacement = new CompletableFuture<>();
        Ui() {
            previousHandler = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
                asynchronousFailure.compareAndSet(null, error);
                error.printStackTrace();
            });
            Scene scene = new Scene(root, 600, 400);
            scene.addPostLayoutPulseListener(() -> {
                if (view != null) for (var event : pending.values()) view.latency().observed(event, "_post_layout");
                pending.clear();
            });
            stage.setTitle("FractalLens — roadmap 9.1 production input baseline");
            stage.setScene(scene); stage.show();
        }
        void size(int width, int height) {
            double sx = stage.getOutputScaleX(), sy = stage.getOutputScaleY();
            if (width % sx != 0 || height % sy != 0) throw new IllegalArgumentException("Non-integral logical target");
            // Unmanaged child preserves the exact target even when larger than the visible window.
            view.resize(width / sx, height / sy);
            view.layout();
        }
        void seed(BaselineFixtures.Step step, InteractiveRenderMode mode) throws Exception {
            fx(() -> {
                pending.clear();
                if (view != null) view.close();
                view = new FractalView(step.job().formula().preset(), PalettePreset.ICE);
                view.setManaged(false); root.getChildren().setAll(view);
                size(step.job().width(), step.job().height());
                return null;
            });
            awaitIdle(this);
            fx(() -> {
                var colors = new ColoringSettings(PalettePreset.ICE);
                colors = new ColoringSettings(colors.palette(), colors.paletteStops(), colors.colorScale(), 0,
                        step.colors() == BaselineFixtures.Colors.HISTOGRAM, step.job().formula().orbitTrap());
                var iterations = step.iterationPolicy().equals("fixed")
                        ? new IterationSettings(step.job().maxIterations(), 0) : new IterationSettings();
                var scene = new FractalScene(step.job().formula().preset(), step.job().viewport(), iterations, colors,
                        new AntialiasSettings(step.pattern(), mode));
                BaselineInputAccess.surface(view).invalidateRefinement();
                BaselineInputAccess.seed(view, scene);
                return null;
            });
            awaitIdle(this);
            fx(() -> { view.setDeepAntialiasing(step.aa() != BaselineFixtures.Aa.NONE); return null; });
            awaitIdle(this);
            fx(() -> {
                var job = BaselineInputAccess.surface(view).completedRender().frame().job();
                if (!job.viewport().equals(step.job().viewport()) || job.width() != step.job().width()
                        || job.height() != step.job().height()) throw new IllegalStateException("Fixture seed changed");
                return null;
            });
            Result seed = fx(() -> {
                Result r = new Result(); r.mode = mode;
                r.actual = actualStep(this, step, "source");
                r.frame = BaselineInputAccess.surface(view).completedRender().frame();
                r.pixels = BaselineInputAccess.surface(view).refinedPixelSnapshot(r.frame).colors();
                return r;
            });
            verify(seed);
        }
        void published(InteractionLatency.Event event) {
            if (Set.of("preview_publish", "base_publish", "aa_publish", "frame_promoted", "complete").contains(event.stage())) {
                pending.put(event.stage(), event); Platform.requestNextPulse();
            }
            if (replace && !replacementQueued && event.stage().equals("render_submit")) {
                replacementQueued = true;
                Platform.runLater(() -> {
                    view.latency().boundary("replacement_dispatch");
                    scroll(view, ScrollEvent.SCROLL, 0, 40);
                    replacement.complete(null);
                });
            }
        }
        @Override public void close() throws Exception {
            fx(() -> { pending.clear(); if (view != null) view.close(); stage.close();
                Thread.currentThread().setUncaughtExceptionHandler(previousHandler); return null; });
        }
    }

    static final class Result {
        long trial;
        String fixture, gesture;
        int seedWidth, seedHeight;
        InteractiveRenderMode mode;
        BaselineFixtures.Step actual;
        RenderFrame frame, source;
        RefinedPixelSnapshot sourcePixels;
        int[] pixels;
        List<InteractionLatency.Event> events;
        boolean changed;
        long time(String stage, long input) {
            return events.stream().filter(e -> e.stage().equals(stage) && (input < 0 || e.input() == input))
                    .mapToLong(InteractionLatency.Event::nanos).min().orElse(-1);
        }
        long lastInput() { return events.stream().filter(e -> e.stage().startsWith("input_")).mapToLong(InteractionLatency.Event::input).max().orElseThrow(); }
        String csv(String phase, int run) {
            long last = lastInput();
            long first = events.stream().filter(e -> e.stage().startsWith("input_")).mapToLong(InteractionLatency.Event::nanos).min().orElseThrow();
            long lastTime = events.stream().filter(e -> e.stage().startsWith("input_") && e.input() == last).mapToLong(InteractionLatency.Event::nanos).min().orElseThrow();
            var fields = new ArrayList<>(List.of("" + trial, fixture, actual.name(), "" + seedWidth, "" + seedHeight, gesture,
                    mode.name(), phase, "" + run, changed ? "complete" : "no_change", "" + actual.job().width(), "" + actual.job().height(),
                    "" + actual.job().maxIterations(), actual.aa().name(), "" + BaselineBenchmark.sampleHash(frame), "" + Arrays.hashCode(pixels)));
            fields.add(ms(time("preview_publish", -1), first));
            fields.add(ms(time("preview_publish_post_layout", -1), first));
            for (String stage : List.of("render_start", "base_publish", "aa_publish", "complete", "complete_post_layout")) fields.add(ms(time(stage, last), lastTime));
            for (String suffix : List.of("", "_post_layout")) {
                long visible = java.util.stream.Stream.of("base_publish", "aa_publish", "frame_promoted")
                        .mapToLong(stage -> time(stage + suffix, last)).filter(t -> t >= 0).min().orElse(-1);
                fields.add(ms(visible, lastTime));
            }
            fields.add("" + events.stream().filter(e -> e.stage().equals("render_start")).count());
            return String.join(",", fields);
        }
    }

    static Result measure(Ui ui, BaselineFixtures.Fixture fixture, BaselineFixtures.Step step,
                          InteractiveRenderMode mode, String gesture, long trial) throws Exception {
        Result r = new Result(); r.trial = trial; r.fixture = fixture.id(); r.mode = mode; r.gesture = gesture;
        r.seedWidth = fixture.steps().getFirst().job().width(); r.seedHeight = fixture.steps().getFirst().job().height();
        var before = fx(() -> BaselineInputAccess.surface(ui.view).completedRender().frame());
        r.source = before;
        r.sourcePixels = fx(() -> BaselineInputAccess.surface(ui.view).refinedPixelSnapshot(before));
        fx(() -> {
            ui.pending.clear(); ui.replace = gesture.equals("replacement"); ui.replacementQueued = false;
            ui.replacement = new CompletableFuture<>(); ui.view.latency().arm(ui::published); return null;
        });
        if (gesture.equals("sequence")) navigate(ui, step);
        else perform(ui, gesture);
        if (gesture.equals("replacement")) ui.replacement.get(180, TimeUnit.SECONDS);
        awaitIdle(ui);
        fx(() -> ui.view.latency().diagnosticsDrained()).get(180, TimeUnit.SECONDS);
        // Flush the last publication's post-layout observation before stopping the epoch.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(() -> ui.pending.isEmpty())) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("No post-layout observation");
            Thread.sleep(5);
        }
        fx(() -> {
            r.events = ui.view.latency().stop();
            r.frame = BaselineInputAccess.surface(ui.view).completedRender().frame();
            r.pixels = BaselineInputAccess.surface(ui.view).refinedPixelSnapshot(r.frame).colors();
            r.actual = actualStep(ui, step, step.name());
            r.changed = r.frame != before;
            if (!r.frame.job().viewport().equals(BaselineInputAccess.viewport(ui.view))) {
                throw new IllegalStateException("Idle frame differs from the camera after input");
            }
            return null;
        });
        long last = r.lastInput();
        if (r.changed && (r.time("complete", last) < 0 || r.time("complete_post_layout", last) < 0)) {
            throw new IllegalStateException("Last input lacks generation-bound completion");
        }
        if (!r.changed && r.events.stream().anyMatch(e -> e.stage().equals("render_start"))) {
            throw new IllegalStateException("Started render did not replace source");
        }
        return r;
    }

    static BaselineFixtures.Step actualStep(Ui ui, BaselineFixtures.Step template, String name) throws Exception {
        var frame = BaselineInputAccess.surface(ui.view).completedRender().frame();
        boolean deep = BaselineInputAccess.deepZoom(ui.view);
        // The deep toggle is transient: entering deep from a direct seed leaves it disabled.
        var aa = !deep || BaselineInputAccess.deepAa(ui.view) ? BaselineFixtures.Aa.EMPTY : BaselineFixtures.Aa.NONE;
        return new BaselineFixtures.Step(name, BaselineFixtures.Action.REUSE, frame.job(), "fixed", aa, template.pattern(), template.colors());
    }

    static void verify(Result r) throws Exception {
        // Reproduce sample and AA retention from a previously verified source.
        // Recomputing retained deep samples around a new reference orbit is a
        // different floating-point path, not a bit-exact navigation control.
        try (var backend = BaselineBenchmark.newBackend(); var aa = new InteractiveAntialiasService()) {
            var reuse = r.source == null ? null : new FrameReusePlanner().plan(r.source, null, r.frame.job()).result();
            var control = reuse == null ? RenderFrame.create(r.frame.job(), r.frame.renderGrid()) : reuse.frame();
            backend.render(control, () -> false, ignored -> {}, null);
            BaselineFxBenchmark.assertSamples(control, r.frame);
            int width = control.job().width(), height = control.job().height();
            int[] expected = new int[width * height];
            var coloring = r.actual.coloring(control);
            new FractalColorizer().color(control.samplePlane(), IntBuffer.wrap(expected), coloring);
            var validity = new ValidityMask(width, height);
            if (r.source != null) {
                if (reuse.reused()) {
                    var shift = reuse.shift().orElseThrow();
                    validity.copyShiftedFrom(r.sourcePixels.validity(), shift);
                    for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                        if (validity.isReady(x, y)) expected[y * width + x] = r.sourcePixels.color(x - shift.dx(), y - shift.dy());
                    }
                }
            }
            if (r.actual.aa() != BaselineFixtures.Aa.NONE) {
                var retained = new RefinedPixelSnapshot(width, height, expected, validity);
                var done = new CompletableFuture<Void>();
                java.util.function.BiConsumer<RenderRegion, int[]> publish = (region, pixels) -> {
                    for (int y = 0; y < region.height(); y++) System.arraycopy(pixels, y * region.width(),
                            expected, (region.y() + y) * width + region.x(), region.width());
                };
                if (backend.select(control.job()) instanceof MandelbrotPerturbationRenderBackend) {
                    aa.refineDeep(control, coloring, r.actual.pattern(), retained, Runnable::run, publish,
                            () -> done.complete(null), done::completeExceptionally);
                } else aa.refine(control, coloring, r.actual.pattern(), retained, Runnable::run, publish,
                        () -> done.complete(null), done::completeExceptionally);
                done.get(180, TimeUnit.SECONDS);
            }
            int mismatch = Arrays.mismatch(expected, r.pixels);
            if (mismatch >= 0) throw new IllegalStateException("Input/control ARGB mismatch at " + mismatch + " for " + r.actual.name());
        }
    }

    static void save(Result r, BaselineFixtures.Step source, String phase, int run,
                             PrintWriter rows, PrintWriter events, PrintWriter actual) {
        rows.println(r.csv(phase, run)); rows.flush();
        actual.println(r.trial + ",source," + actualCsv(source, r.fixture, true));
        actual.println(r.trial + ",target," + actualCsv(r.actual, r.fixture, false)); actual.flush();
        for (var event : r.events) events.println(r.trial + "," + event.epoch() + "," + event.input() + "," + event.render()
                + "," + event.stage() + "," + event.nanos() + "," + event.durationNanos());
        events.flush();
    }

    private static String actualCsv(BaselineFixtures.Step step, String fixture, boolean source) {
        // Freeze the actual cap for same-grid replay, but never label UI navigation
        // as empty-cache AA. Its already verified source pixels can be retained.
        var fields = step.csv(fixture).split(",", -1);
        var names = List.of(BaselineFixtures.HEADER.split(","));
        fields[names.indexOf("base_cache")] = source ? "prepared_source" : "ui_active_then_retained";
        fields[names.indexOf("aa_cache")] = step.aa() == BaselineFixtures.Aa.NONE ? "disabled"
                : source ? "prepared_source" : "retained_pixels_if_compatible";
        return String.join(",", fields);
    }

    static void perform(Ui ui, String gesture) throws Exception {
        if (gesture.equals("wheel") || gesture.equals("replacement")) {
            fx(() -> { scroll(ui.view, ScrollEvent.SCROLL, 0, 40); return null; }); return;
        }
        fx(() -> {
            if (gesture.equals("pinch")) zoom(ui.view, ZoomEvent.ZOOM_STARTED, 1);
            if (gesture.equals("trackpad")) scroll(ui.view, ScrollEvent.SCROLL_STARTED, 0, 0);
            if (gesture.equals("drag")) mouse(ui.view, MouseEvent.MOUSE_PRESSED, 0, 0, true);
            return null;
        });
        for (int i = 1; i <= 6; i++) {
            int index = i;
            fx(() -> {
                switch (gesture) {
                    case "wheel-burst" -> scroll(ui.view, ScrollEvent.SCROLL, 0, 40);
                    case "pinch" -> zoom(ui.view, ZoomEvent.ZOOM, 1.03);
                    case "trackpad" -> scroll(ui.view, ScrollEvent.SCROLL, 8, 3);
                    case "drag" -> mouse(ui.view, MouseEvent.MOUSE_DRAGGED, index * 8, index * 3, true);
                    default -> throw new IllegalArgumentException(gesture);
                }
                return null;
            });
            if (i < 6) Thread.sleep(16);
        }
        fx(() -> {
            if (gesture.equals("pinch")) zoom(ui.view, ZoomEvent.ZOOM_FINISHED, 1);
            if (gesture.equals("trackpad")) scroll(ui.view, ScrollEvent.SCROLL_FINISHED, 0, 0);
            if (gesture.equals("drag")) mouse(ui.view, MouseEvent.MOUSE_RELEASED, 48, 18, false);
            return null;
        });
    }

    static void navigate(Ui ui, BaselineFixtures.Step step) throws Exception {
        fx(() -> {
            var source = BaselineInputAccess.surface(ui.view).completedRender().frame().job();
            var target = step.job();
            if (source.width() != target.width() || source.height() != target.height()) {
                ui.size(target.width(), target.height());
            } else if (source.viewport().scaleExact().compareTo(target.viewport().scaleExact()) != 0) {
                double factor = source.viewport().scaleExact().divide(target.viewport().scaleExact(), source.viewport().mathContext()).doubleValue();
                zoom(ui.view, ZoomEvent.ZOOM_STARTED, 1); zoom(ui.view, ZoomEvent.ZOOM, factor); zoom(ui.view, ZoomEvent.ZOOM_FINISHED, 1);
            } else {
                var unit = source.viewport().imaginaryUnitsPerPixelExact((int) ui.view.getHeight());
                double dx = source.viewport().center().real().subtract(target.viewport().center().real()).divide(unit, source.viewport().mathContext()).doubleValue();
                double dy = target.viewport().center().imaginary().subtract(source.viewport().center().imaginary()).divide(unit, source.viewport().mathContext()).doubleValue();
                mouse(ui.view, MouseEvent.MOUSE_PRESSED, 0, 0, true);
                mouse(ui.view, MouseEvent.MOUSE_DRAGGED, dx, dy, true);
                mouse(ui.view, MouseEvent.MOUSE_RELEASED, dx, dy, false);
            }
            return null;
        });
    }

    static void scroll(FractalView view, EventType<ScrollEvent> type, double dx, double dy) {
        Event.fireEvent(view, new ScrollEvent(type, (view.getWidth() - 1) / 2, (view.getHeight() - 1) / 2, 0, 0,
                false, false, false, false, false, false, dx, dy, dx, dy,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
    }
    static void zoom(FractalView view, EventType<ZoomEvent> type, double factor) {
        Event.fireEvent(view, new ZoomEvent(type, (view.getWidth() - 1) / 2, (view.getHeight() - 1) / 2, 0, 0,
                false, false, false, false, false, false, factor, factor, null));
    }
    static void mouse(FractalView view, EventType<MouseEvent> type, double dx, double dy, boolean down) {
        Event.fireEvent(view, new MouseEvent(type, (view.getWidth() - 1) / 2 + dx, (view.getHeight() - 1) / 2 + dy, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false, down, false, false, false, false, true, null));
    }
    static void awaitIdle(Ui ui) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (true) {
            if (ui.asynchronousFailure.get() != null) throw new IllegalStateException("Asynchronous JavaFX failure", ui.asynchronousFailure.get());
            if (fx(() -> BaselineInputAccess.idle(ui.view))) break;
            if (System.nanoTime() > deadline) throw new IllegalStateException("Input renderer did not settle");
            Thread.sleep(10);
        }
    }
    static String ms(long end, long start) {
        return end < 0 || start < 0 ? "-1" : String.format(Locale.ROOT, "%.6f", (end - start) / 1e6);
    }
}
