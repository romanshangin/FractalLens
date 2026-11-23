package com.shangin.fractal.controller;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.FakeJuliaPreviewRuntime;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.scene.FractalScene;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Separate-JVM probe for {@link JuliaGpuRendererExitTest}: closes the renderer while a
 * delayed GPU request runs, then returns from {@code main}. The deferred close must keep
 * the JVM alive until it closed the service and wrote the marker file.
 */
final class JuliaGpuRendererExitProbe {
    private JuliaGpuRendererExitProbe() {}

    public static void main(String[] args) throws Exception {
        Path marker = Path.of(args[0]);
        var runtime = new FakeJuliaPreviewRuntime();
        runtime.setGated(true);
        var service = new FractalRenderService(new DirectDoubleRenderBackend(), runtime);
        var renderer = new JuliaGpuRenderer(service, Runnable::run);
        FractalScene scene = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE);
        RenderJob job = new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), 64, 48, 100);
        renderer.submit(new JuliaGpuRenderer.Request(1, job, scene.coloring(), false), new JuliaGpuRenderer.Listener() {
            @Override public void rendered(JuliaGpuRenderer.Request request, int[] colors) {}
            @Override public void unavailable(JuliaGpuRenderer.Request request) {}
            @Override public void failed(JuliaGpuRenderer.Request request, Throwable error) {}
        });
        while (runtime.started() == 0) Thread.sleep(5);
        // The native call finishes only after main has returned.
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            runtime.release();
        });
        releaser.setDaemon(true);
        releaser.start();
        renderer.close(() -> {
            service.close();
            try {
                Files.writeString(marker, runtime.closed() ? "closed" : "open");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
    }
}
