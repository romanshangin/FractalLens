package com.shangin.fractal.export;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ScenePngExportTest {
    @TempDir Path directory;

    @Test
    void cancellationSuppressesFurtherProgressAndCompletion() throws Exception {
        var values = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean();
        Path path = directory.resolve("cancelled-progress.png");
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);
        try (var service = new AdaptivePngExportService()) {
            service.export(scene, new RenderTarget(67, 41), path, percent -> {
                values.add(percent);
                if (percent == 45) {
                    service.cancelCurrent();
                    cancelled.countDown();
                }
            }, ignored -> callback.set(true), ignored -> callback.set(true));
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
            var drained = new CompletableFuture<Path>();
            service.export(scene, new RenderTarget(5, 5), directory.resolve("after-progress.png"),
                    drained::complete, drained::completeExceptionally);
            drained.get(10, TimeUnit.SECONDS);
        }
        assertEquals(45, values.getLast());
        assertFalse(callback.get());
        assertFalse(Files.exists(path));
    }

    @Test
    void reportsMonotonicProgressAndCompletesOnlyAfterFilePublication() throws Exception {
        var values = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        Path path = directory.resolve("progress.png");
        CompletableFuture<Path> completed = new CompletableFuture<>();
        try (var service = new AdaptivePngExportService()) {
            service.export(FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE),
                    new RenderTarget(67, 41), path, percent -> {
                        if (percent == 100) assertTrue(Files.exists(path));
                        values.add(percent);
                    }, completed::complete, completed::completeExceptionally);
            completed.get(20, TimeUnit.SECONDS);
        }
        assertEquals(0, values.getFirst());
        assertEquals(100, values.getLast());
        assertTrue(values.contains(45));
        assertTrue(values.contains(95));
        for (int index = 1; index < values.size(); index++) {
            assertTrue(values.get(index) > values.get(index - 1));
        }
    }

    @Test
    void independentTargetsMatchReferenceFramesAcrossTileEdgesForEveryFormula() throws Exception {
        for (FractalPreset preset : FractalPreset.values()) {
            FractalScene scene = FractalScene.create(preset, PalettePreset.ICE)
                    .withIterations(new IterationSettings(60, 0))
                    .withJuliaParameters(new JuliaParameters(-0.4, 0.6));
            verifyReference(scene, new RenderTarget(67, 41));
        }
    }

    @Test
    void histogramAndOrbitTrapUseExportSamplesAndSceneColoring() throws Exception {
        FractalScene scene = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                .withIterations(new IterationSettings(50, 0));
        verifyReference(scene.withColoring(new ColoringSettings(PalettePreset.ICE, 0.03, 0.2, true)),
                new RenderTarget(35, 33));
        verifyReference(scene.withColoring(new ColoringSettings(PalettePreset.ICE,
                        PalettePreset.ICE.stops(), 0.03, 0.2, false, OrbitTrap.CROSS)),
                new RenderTarget(34, 35));
    }

    @Test
    void deepScenesPreserveDecimalCoordinatesAndDimensionParity() throws Exception {
        for (FractalPreset preset : new FractalPreset[]{FractalPreset.MANDELBROT, FractalPreset.JULIA}) {
            FractalScene scene = FractalScene.create(preset, PalettePreset.ICE)
                    .withViewport(new Viewport("2", "0", "1e-80"))
                    .withIterations(new IterationSettings(20, 0));
            verifyReference(scene, new RenderTarget(5, 6));
        }
    }

    private void verifyReference(FractalScene scene, RenderTarget target) throws Exception {
        RenderJob job = new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(),
                target.width(), target.height(), scene.iterations().baseIterations());
        RenderFrame reference;
        try (RenderBackend backend = new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(),
                new PrecisionSelectingRenderBackend(new MandelbrotPerturbationRenderBackend(),
                        new JuliaDeepZoomRenderBackend()))) {
            reference = backend.render(RenderFrame.create(job), () -> false, ignored -> {}, null);
        }
        Path expected = directory.resolve("reference.png");
        Path actual = directory.resolve("scene.png");
        try (var service = new AdaptivePngExportService()) {
            CompletableFuture<Path> completion = new CompletableFuture<>();
            service.export(reference, scene.coloring().createStrategy(reference.samplePlane()), expected,
                    completion::complete, completion::completeExceptionally);
            completion.get(20, TimeUnit.SECONDS);
            completion = new CompletableFuture<>();
            service.export(scene, target, actual, completion::complete, completion::completeExceptionally);
            completion.get(20, TimeUnit.SECONDS);
        }
        var expectedImage = ImageIO.read(expected.toFile());
        var actualImage = ImageIO.read(actual.toFile());
        assertEquals(target.width(), actualImage.getWidth());
        assertEquals(target.height(), actualImage.getHeight());
        assertArrayEquals(expectedImage.getRGB(0, 0, target.width(), target.height(), null, 0, target.width()),
                actualImage.getRGB(0, 0, target.width(), target.height(), null, 0, target.width()));
        assertEquals(scene.viewport(), reference.job().viewport());
    }

    @Test
    void supersessionWaitsForBackendExitAndSuppressesCancelledPublication() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean callback = new AtomicBoolean();
        RenderBackend backend = new RenderBackend() {
            private final RenderBackend delegate = new DirectDoubleRenderBackend();
            public RenderFrame render(RenderFrame frame, BooleanSupplier stop,
                                      Consumer<RenderRegion> progress, Consumer<TileTimingStats> timing)
                    throws InterruptedException {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    while (!stop.getAsBoolean()) Thread.onSpinWait();
                    cancelled.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return frame;
                }
                return delegate.render(frame, stop, progress, timing);
            }
            public void close() { delegate.close(); }
        };
        Path existing = directory.resolve("existing.png");
        Files.writeString(existing, "original");
        try (var service = new AdaptivePngExportService(backend)) {
            var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);
            service.export(scene, new RenderTarget(5, 5), existing,
                    ignored -> callback.set(true), ignored -> callback.set(true));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            service.cancelCurrent();
            CompletableFuture<Path> completed = new CompletableFuture<>();
            Path replacement = directory.resolve("replacement.png");
            service.export(scene, new RenderTarget(7, 6), replacement,
                    completed::complete, completed::completeExceptionally);
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get(), "Next export must wait for the cancelled backend to exit");
            assertFalse(Files.exists(replacement));
            release.countDown();
            completed.get(10, TimeUnit.SECONDS);
            assertFalse(callback.get());
            assertEquals("original", Files.readString(existing));
        } finally {
            release.countDown();
        }
        assertNoStagingFiles();
    }

    @Test
    void cancellationDuringAntialiasingDrainsWorkersBeforeNextExport() throws Exception {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withIterations(new IterationSettings(10, 0));
        RenderFrame frame;
        try (var backend = new DirectDoubleRenderBackend()) {
            frame = backend.render(RenderFrame.create(new RenderJob(FormulaDefinition.forScene(scene),
                    scene.viewport(), 4, 4, 10)), () -> false, ignored -> {}, null);
        }
        CountDownLatch sampling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean oldCallback = new AtomicBoolean();
        Path existing = directory.resolve("existing.png");
        Files.writeString(existing, "original");
        try (var service = new AdaptivePngExportService()) {
            service.export(frame, (iterations, smooth, escaped, maximum) -> {
                if (Thread.currentThread().getName().startsWith("fractal-export-worker")) {
                    sampling.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException exception) { throw new AssertionError(exception); }
                }
                return 0xFF000000;
            }, existing, ignored -> oldCallback.set(true), ignored -> oldCallback.set(true));
            assertTrue(sampling.await(10, TimeUnit.SECONDS));
            service.cancelCurrent();
            CompletableFuture<Path> completed = new CompletableFuture<>();
            service.export(scene, new RenderTarget(5, 5), directory.resolve("next.png"),
                    completed::complete, completed::completeExceptionally);
            assertFalse(completed.isDone());
            release.countDown();
            completed.get(10, TimeUnit.SECONDS);
            assertEquals("original", Files.readString(existing));
            assertFalse(oldCallback.get());
        } finally { release.countDown(); }
        assertNoStagingFiles();
    }

    @Test
    void rejectsUnsafeTargetsAndUnsupportedPrecisionBeforeAllocating() {
        var scene = FractalScene.create(FractalPreset.BURNING_SHIP, PalettePreset.ICE)
                .withViewport(new Viewport("1", "0", "1e-80"));
        try (var service = new AdaptivePngExportService()) {
            assertThrows(IllegalArgumentException.class, () -> AdaptivePngExportService.validateTarget(
                    new RenderTarget(Integer.MAX_VALUE, Integer.MAX_VALUE)));
            assertThrows(IllegalArgumentException.class, () -> service.export(scene, new RenderTarget(4, 4),
                    directory.resolve("invalid.png"), ignored -> fail(), ignored -> fail()));
            service.close();
            assertThrows(IllegalStateException.class, () -> service.export(
                    FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE), new RenderTarget(4, 4),
                    directory.resolve("closed.png"), ignored -> fail(), ignored -> fail()));
        }
    }

    @Test
    void closeDuringRenderSuppressesCallbacksAndClosesBackendAfterExit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch backendClosed = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean();
        RenderBackend backend = new RenderBackend() {
            public RenderFrame render(RenderFrame frame, BooleanSupplier stop,
                                      Consumer<RenderRegion> progress, Consumer<TileTimingStats> timing)
                    throws InterruptedException {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                assertTrue(stop.getAsBoolean());
                return frame;
            }
            public void close() { backendClosed.countDown(); }
        };
        Path target = directory.resolve("closed.png");
        try (var service = new AdaptivePngExportService(backend)) {
            service.export(FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE),
                    new RenderTarget(4, 4), target,
                    ignored -> callback.set(true), ignored -> callback.set(true));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            service.close();
            service.close();
            assertEquals(1, backendClosed.getCount());
            release.countDown();
            assertTrue(backendClosed.await(10, TimeUnit.SECONDS));
            assertFalse(callback.get());
            assertFalse(Files.exists(target));
        } finally { release.countDown(); }
    }

    @Test
    void writeFailureReportsErrorAndLeavesNoStagingFile() throws Exception {
        Path destination = directory.resolve("destination.png");
        Files.createDirectory(destination);
        Files.writeString(destination.resolve("keep"), "original");
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        try (var service = new AdaptivePngExportService()) {
            service.export(FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE),
                    new RenderTarget(4, 4), destination, ignored -> failure.complete(null), failure::complete);
            assertInstanceOf(java.io.IOException.class, failure.get(10, TimeUnit.SECONDS));
        }
        assertEquals("original", Files.readString(destination.resolve("keep")));
        assertNoStagingFiles();
    }

    private void assertNoStagingFiles() throws Exception {
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".fractallens-export-")));
        }
    }
}
