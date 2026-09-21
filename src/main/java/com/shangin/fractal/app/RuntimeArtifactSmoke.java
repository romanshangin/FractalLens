package com.shangin.fractal.app;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.GpuRuntimeFactory;
import com.shangin.fractal.gpu.GpuRuntimeState;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.ui.RuntimeArtifactChecks;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** End-to-end launch probe used only by packaged-runtime validation. */
final class RuntimeArtifactSmoke {

    private RuntimeArtifactSmoke() {
    }

    static void start(Stage stage, Path report) {
        ApplicationIcon.install(stage);
        stage.setScene(new Scene(new Label("FractalUI runtime smoke test"), 360, 100));
        stage.setTitle("FractalUI runtime smoke test");
        stage.show();

        Thread.startVirtualThread(() -> {
            try {
                Map<String, String> checks = runChecks();
                checks.put("javafx", "available");
                writeReport(report, checks);
                System.out.println("FRACTALUI_PACKAGE_SMOKE_OK");
                Platform.runLater(() -> {
                    stage.close();
                    Platform.exit();
                });
            } catch (Throwable failure) {
                try {
                    writeFailure(report, failure);
                } catch (IOException reportFailure) {
                    failure.addSuppressed(reportFailure);
                }
                failure.printStackTrace(System.err);
                System.exit(1);
            }
        });
    }

    static Map<String, String> runChecks() throws InterruptedException {
        var checks = new LinkedHashMap<String, String>();
        try (var runtime = GpuRuntimeFactory.createDefault()) {
            if (runtime.capabilityReport().state() != GpuRuntimeState.UNAVAILABLE) {
                throw new IllegalStateException("Packaged runtime must start with GPU disabled");
            }
            checks.put("cpu_fallback", "available");
        }

        var formula = FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE);
        var job = new RenderJob(formula, new Viewport(-0.75, 0.0, 2.4), 32, 24, 100);
        var frame = RenderFrame.create(job);
        try (var backend = new DirectDoubleRenderBackend()) {
            backend.render(frame, () -> false, ignored -> { }, null);
        }
        if (!frame.isComplete()) {
            throw new IllegalStateException("Packaged CPU smoke frame is incomplete");
        }
        checks.put("cpu_render", "complete");

        boolean mac = System.getProperty("os.name", "").startsWith("Mac");
        boolean nativeMenu = RuntimeArtifactChecks.nativeMenuAvailable();
        if (mac && !nativeMenu) {
            throw new IllegalStateException("Packaged AppKit menu bridge is unavailable");
        }
        checks.put("appkit_bridge", mac ? "available" : "not_applicable");
        checks.put("status", "ok");
        return checks;
    }

    private static void writeFailure(Path report, Throwable failure) throws IOException {
        var trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        var values = new LinkedHashMap<String, String>();
        values.put("status", "failed");
        values.put("error", trace.toString().replace('\n', ' ').replace('\r', ' '));
        writeReport(report, values);
    }

    private static void writeReport(Path report, Map<String, String> values) throws IOException {
        Path absolute = report.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Smoke report requires a parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        try {
            var lines = values.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList();
            Files.write(temporary, lines);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
