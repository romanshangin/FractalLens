package com.shangin.fractal.render;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.WidePreviewSettings;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Measures cooperative reserve cancellation after actual sample publication, including worker drain. */
public final class WidePreviewCancellationBenchmark {
    private WidePreviewCancellationBenchmark() {}

    public static void main(String[] args) throws Exception {
        System.out.println("formula,scale,run,first_region_ms,cancel_tail_ms,ready_samples");
        for (var preset : new FractalPreset[]{FractalPreset.MANDELBROT, FractalPreset.JULIA}) {
            for (String scale : new String[]{"0.024", "1e-80"}) {
                for (int run = -1; run < 3; run++) measure(preset, scale, run);
            }
        }
    }

    private static void measure(FractalPreset preset, String scale, int run) throws Exception {
        var scene = FractalScene.create(preset, PalettePreset.ICE).withViewport(
                new Viewport("-0.743643887037151", "0.13182590420533", scale));
        var firstRegion = new CountDownLatch(1);
        var cancelledAt = new AtomicLong();
        var serviceRef = new java.util.concurrent.atomic.AtomicReference<WidePreviewService>();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        long start = System.nanoTime();
        try (var service = new WidePreviewService(Runnable::run, () -> {
            RenderBackend selected = new PrecisionSelectingRenderBackend(
                    new DirectDoubleRenderBackend(new ParallelFractalCalculator(WidePreviewSettings.WORKERS)),
                    new PrecisionSelectingRenderBackend(new MandelbrotPerturbationRenderBackend(
                            WidePreviewSettings.WORKERS, ignored -> {}, WidePreviewSettings.REFERENCE_CACHE_BYTES),
                            new JuliaDeepZoomRenderBackend(WidePreviewSettings.WORKERS)));
            return new RenderBackend() {
                public boolean supports(RenderJob job) { return selected.supports(job); }
                public RenderFrame render(RenderFrame frame, BooleanSupplier stop,
                                          Consumer<RenderRegion> completed, Consumer<TileTimingStats> timing) throws InterruptedException {
                    return selected.render(frame, stop, region -> {
                        completed.accept(region);
                        long now = System.nanoTime();
                        if (cancelledAt.compareAndSet(0, now)) {
                            serviceRef.get().cancel();
                            firstRegion.countDown();
                        }
                    }, timing);
                }
                public void close() { selected.close(); }
            };
        })) {
            serviceRef.set(service);
            service.request(new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), 3024, 1964, 3000),
                    scene.coloring(), WidePreviewSettings.CANDIDATE, ignored -> {}, error::set);
            if (!firstRegion.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("No real completed region before the reserve budget", error.get());
            var field = WidePreviewService.class.getDeclaredField("coordinator"); field.setAccessible(true);
            ((ExecutorService) field.get(service)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            long drained = System.nanoTime();
            var reserveField = WidePreviewService.class.getDeclaredField("reserve"); reserveField.setAccessible(true);
            var reserve = (RenderFrame) reserveField.get(service);
            if (error.get() != null) throw new IllegalStateException("Reserve failed", error.get());
            System.out.printf(java.util.Locale.ROOT, "%s,%s,%d,%.6f,%.6f,%d%n", preset.name(), scale, run,
                    (cancelledAt.get() - start) / 1e6, (drained - cancelledAt.get()) / 1e6,
                    reserve.validity().readyPixelCount());
        }
    }
}
