package com.shangin.fractal.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Closing during GPU work must not let the JVM exit before the service is closed. */
class JuliaGpuRendererExitTest {
    @Test
    void deferredCloseFinishesBeforeTheJvmExits(@TempDir Path directory) throws Exception {
        Path marker = directory.resolve("closed.txt");
        Path log = directory.resolve("probe.log");
        String classPath = String.join(File.pathSeparator,
                System.getProperty("jdk.module.path", ""), System.getProperty("java.class.path", ""));
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath, JuliaGpuRendererExitProbe.class.getName(), marker.toString())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Probe JVM did not exit: " + Files.readString(log));
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.exists(marker), "The JVM exited before the deferred close ran");
            assertEquals("closed", Files.readString(marker), "The service did not close the runtime");
        } finally {
            // A hung or interrupted probe must not outlive the test; its log stays for diagnosis.
            if (process.isAlive()) {
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }
}
