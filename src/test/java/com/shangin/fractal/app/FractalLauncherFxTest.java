package com.shangin.fractal.app;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the production entry point in its own graphical test fork. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalLauncherFxTest {
    @TempDir
    Path directory;

    @Test
    void entryPointPreservesPipelineOptionsAndForwardsApplicationArguments() throws Exception {
        Path report = directory.resolve("smoke report.properties");
        String requestedPipeline = System.getProperty("prism.order");
        String expectedPipeline = requestedPipeline == null
                && System.getProperty("os.name", "").startsWith("Mac")
                ? "mtl,es2,sw" : requestedPipeline;
        var completed = new CompletableFuture<Void>();
        Thread launcher = new Thread(() -> {
            try {
                launch(new String[]{"--package-smoke-test=" + report});
                completed.complete(null);
            } catch (Throwable failure) {
                completed.completeExceptionally(failure);
            }
        }, "production-launcher-test");
        launcher.setDaemon(true);
        launcher.start();
        try {
            completed.get(30, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
            launcher.join(15_000);
        }
        assertFalse(launcher.isAlive(), "The production launcher must finish its shutdown");

        assertEquals(expectedPipeline, System.getProperty("prism.order"),
                "Bootstrap must select the macOS default and preserve user overrides");
        var checks = new Properties();
        try (var reader = Files.newBufferedReader(report)) {
            checks.load(reader);
        }
        assertEquals("ok", checks.getProperty("status"));
        assertEquals("available", checks.getProperty("javafx"));
        assertEquals("complete", checks.getProperty("cpu_render"));
        assertEquals("available", checks.getProperty("cpu_fallback"));
    }

    void launch(String[] args) {
        FractalLauncher.main(args);
    }
}
