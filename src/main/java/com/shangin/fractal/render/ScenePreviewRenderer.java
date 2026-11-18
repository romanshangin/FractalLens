package com.shangin.fractal.render;

import com.shangin.fractal.scene.FractalScene;

import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Renders small base-quality (not antialiased) previews of immutable scenes with
 * the same CPU backends, iteration policy and coloring as interactive rendering.
 * Calls block the calling thread and must not run on the JavaFX Application Thread.
 */
public final class ScenePreviewRenderer implements AutoCloseable {
    private final RenderBackend backend;
    private final FractalColorizer colorizer = new FractalColorizer();

    public ScenePreviewRenderer() {
        this(new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(),
                new PrecisionSelectingRenderBackend(new MandelbrotPerturbationRenderBackend(),
                        new JuliaDeepZoomRenderBackend())));
    }

    ScenePreviewRenderer(RenderBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /**
     * Returns packed non-premultiplied ARGB pixels in row-major order, or {@code null}
     * when {@code cancelled} reported cancellation before the preview completed.
     *
     * @throws IllegalArgumentException if no CPU backend supports the scene precision
     */
    public int[] render(FractalScene scene, int width, int height, BooleanSupplier cancelled)
            throws InterruptedException {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(cancelled);
        RenderJob job = new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), width, height,
                scene.iterations().maxIterations(
                        scene.fractal().defaultViewport().scaleExact(), scene.viewport().scaleExact()));
        if (!backend.supports(job)) {
            throw new IllegalArgumentException("No CPU backend supports this preview precision");
        }
        RenderFrame frame = backend.render(RenderFrame.create(job), cancelled, region -> {}, null);
        if (cancelled.getAsBoolean() || !frame.isComplete()) return null;
        SamplePlane samples = frame.samplePlane();
        int[] pixels = new int[samples.size()];
        colorizer.color(samples, IntBuffer.wrap(pixels), scene.coloring().createStrategy(samples));
        return pixels;
    }

    @Override
    public void close() {
        backend.close();
    }
}
