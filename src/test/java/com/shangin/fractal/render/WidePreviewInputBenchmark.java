package com.shangin.fractal.render;

import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.ui.BaselineInputAccess;
import javafx.application.Platform;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.shangin.fractal.render.BaselineFxBenchmark.fx;

/**
 * Same production-handler/conformance harness for both frozen builds; no preview API dependency.
 *
 * <p>{@code wide.preview.settings} selects the {@code WidePreviewSettings} constant applied before each
 * source think time: {@code CANDIDATE} (default, the historical behavior) or {@code DEFAULT}. Both values
 * go through the same setter at the same point, so one frozen build can serve as its own control. A
 * build without the preview API ignores the setting and records it as absent.
 */
public final class WidePreviewInputBenchmark {
    static final String SETTINGS_PROPERTY = "wide.preview.settings";

    private WidePreviewInputBenchmark() {}

    public static void main(String[] args) throws Exception {
        int warmups = Integer.getInteger("baseline.warmups", 3);
        int runs = Integer.getInteger("baseline.runs", 5);
        var modes = java.util.Arrays.stream(System.getProperty("baseline.fx.modes", "FAST,REFINED").split(","))
                .map(InteractiveRenderMode::valueOf).toList();
        var fixtures = new ArrayList<BaselineFixtures.Fixture>();
        for (String size : System.getProperty("baseline.sizes", "1512x982,3024x1964").split(",")) {
            String[] dimensions = size.split("x");
            var matrix = BaselineFixtures.matrix(Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]));
            var pan = matrix.stream().filter(f -> f.id().equals("pan-25")).findFirst().orElseThrow();
            fixtures.add(pan);
            var source = pan.steps().getFirst();
            var job = source.job();
            var out = new RenderJob(job.formula(), job.viewport().zoom(1.5), job.width(), job.height(), job.maxIterations());
            fixtures.add(new BaselineFixtures.Fixture("wide-zoom-out", List.of(source,
                    new BaselineFixtures.Step("zoom-out", BaselineFixtures.Action.REUSE, out,
                            source.iterationPolicy(), source.aa(), source.colors()))));
        }
        String settings = widePreviewSettings(System.getProperty(SETTINGS_PROPERTY, "CANDIDATE"));
        boolean previewApi = previewApiAvailable();
        String selected = System.getProperty("baseline.fixtures");
        if (selected != null) fixtures.removeIf(f -> !List.of(selected.split(",")).contains(f.id()));
        Path output = Path.of(System.getProperty("baseline.output"));
        Files.createDirectory(output);
        try (var manifest = new PrintWriter(Files.newBufferedWriter(output.resolve("manifest.csv")))) {
            manifest.println(BaselineFixtures.HEADER);
            for (var fixture : fixtures) for (var step : fixture.steps()) manifest.println(step.csv(fixture.id()));
        }
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
        started.get(15, TimeUnit.SECONDS);
        try (var ui = fx(BaselineInputBenchmark.Ui::new);
             var samples = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")));
             var events = new PrintWriter(Files.newBufferedWriter(output.resolve("events.csv")));
             var actual = new PrintWriter(Files.newBufferedWriter(output.resolve("actual-manifest.csv")));
             var stock = new PrintWriter(Files.newBufferedWriter(output.resolve("stock.csv")))) {
            Files.writeString(output.resolve("environment.txt"), "input_matrix=9.1-input-v1\nmodes="
                    + String.join(",", modes.stream().map(Enum::name).toList()) + "\ngestures=sequence\n"
                    + "samples=" + runs + "\nwarmups=" + warmups + "\nsource_idle_ms=250\n"
                    + "java=" + System.getProperty("java.runtime.version") + "\njavafx=" + System.getProperty("javafx.runtime.version")
                    + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.version")
                    + "\narch=" + System.getProperty("os.arch") + "\nphysical_scanout=unmeasured\n"
                    + "wide_preview_settings=" + settings + "\nwide_preview_api=" + (previewApi ? "present" : "absent") + "\n");
            samples.println(BaselineInputBenchmark.HEADER);
            events.println(BaselineInputBenchmark.EVENT_HEADER);
            actual.println("trial,role," + BaselineFixtures.HEADER);
            stock.println("trial,fixture,width,height,mode,phase,run,ready_samples,total_samples");
            long trial = 0;
            for (var fixture : fixtures) for (var mode : modes) {
                for (int run = -warmups - 1; run < runs; run++) {
                    String phase = run == -warmups - 1 ? "first_sequence" : run < 0 ? "warmup" : "sample";
                    ui.seed(fixture.steps().getFirst(), mode);
                    fx(() -> { applySettingsIfAvailable(ui, settings); return null; });
                    // Identical, declared think time in both builds; reserve preparation is outside gesture timing.
                    Thread.sleep(250);
                    int[] ready = fx(() -> stock(ui));
                    if (settings.equals("DEFAULT") && (ready[0] != 0 || ready[1] != 0))
                        throw new IllegalStateException("DEFAULT wide preview settings left a reserve snapshot");
                    var source = fx(() -> BaselineInputBenchmark.actualStep(ui, fixture.steps().getFirst(), "source"));
                    BaselineInputBenchmark.Result result;
                    try {
                        result = BaselineInputBenchmark.measure(ui, fixture, fixture.steps().get(1), mode, "sequence", ++trial);
                    } catch (Exception error) {
                        // Preserve the failing epoch before the UI is closed; never turn a missing milestone into PASS.
                        var trace = fx(() -> ui.view.latency().stop());
                        try (var failure = new PrintWriter(Files.newBufferedWriter(output.resolve("failed-events.csv")))) {
                            failure.println(BaselineInputBenchmark.EVENT_HEADER);
                            for (var event : trace) failure.println(String.join(",", "" + trial, "" + event.epoch(),
                                    "" + event.input(), "" + event.render(), event.stage(), "" + event.nanos(), "" + event.durationNanos()));
                        }
                        throw error;
                    }
                    BaselineInputBenchmark.save(result, source, phase, run, samples, events, actual);
                    BaselineInputBenchmark.verify(result);
                    stock.println(String.join(",", "" + trial, fixture.id(), "" + result.seedWidth, "" + result.seedHeight,
                            mode.name(), phase, "" + run, "" + ready[0], "" + ready[1]));
                    System.out.println("Verified " + fixture.id() + " " + result.seedWidth + " " + mode.name() + " " + run);
                }
            }
            if (samples.checkError() || events.checkError() || actual.checkError() || stock.checkError())
                throw new IllegalStateException("CSV write failure");
            Files.writeString(output.resolve("SUCCESS"), "All samples and final ARGB pixels passed the existing exact CPU controls.\n");
        } finally { Platform.exit(); }
    }

    private static int[] stock(BaselineInputBenchmark.Ui ui) throws Exception {
        var surface = BaselineInputAccess.surface(ui.view);
        try {
            var field = surface.getClass().getDeclaredField("widePreviewSnapshot");
            field.setAccessible(true);
            Object snapshot = field.get(surface);
            if (snapshot == null) return new int[]{0, 0};
            int ready = (int) snapshot.getClass().getMethod("readySamples").invoke(snapshot);
            Object plan = snapshot.getClass().getMethod("plan").invoke(snapshot);
            RenderJob job = (RenderJob) plan.getClass().getMethod("job").invoke(plan);
            return new int[]{ready, job.width() * job.height()};
        } catch (NoSuchFieldException absentInControl) { return new int[]{0, 0}; }
    }

    /** Validates the declared settings constant name; anything else is a configuration error. */
    static String widePreviewSettings(String value) {
        if (!value.equals("CANDIDATE") && !value.equals("DEFAULT"))
            throw new IllegalArgumentException(SETTINGS_PROPERTY + " must be CANDIDATE or DEFAULT: " + value);
        return value;
    }

    private static boolean previewApiAvailable() {
        try {
            Class.forName("com.shangin.fractal.scene.WidePreviewSettings");
            return true;
        } catch (ClassNotFoundException absentInControl) {
            return false;
        }
    }

    /** Evaluation is explicit after a rejected policy is disabled by default. Old controls have no such API. */
    private static void applySettingsIfAvailable(BaselineInputBenchmark.Ui ui, String name) throws Exception {
        try {
            Class<?> settings = Class.forName("com.shangin.fractal.scene.WidePreviewSettings");
            Object selected = settings.getField(name).get(null);
            var field = ui.view.getClass().getDeclaredField("renderController");
            field.setAccessible(true);
            Object controller = field.get(ui.view);
            controller.getClass().getMethod("setWidePreviewSettings", settings).invoke(controller, selected);
        } catch (ClassNotFoundException absentInControl) {
            // The control predates the reserve; it still uses the same driver and source think time.
        }
    }
}
