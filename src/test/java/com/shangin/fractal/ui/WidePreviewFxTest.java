package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.controller.RenderStatus;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class WidePreviewFxTest {
    @BeforeAll static void start() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(15, TimeUnit.SECONDS));
    }
    @AfterAll static void stop() { Platform.exit(); }
    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS);
    }
    private static ImageView reserve(FractalSurface surface) {
        return (ImageView) surface.getChildrenUnmodifiable().getLast();
    }
    private static int[] pixels(Image image) {
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    private static void drainPreview(FractalRenderController controller) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("widePreview"); field.setAccessible(true);
        Object service = field.get(controller);
        var executor = WidePreviewService.class.getDeclaredField("coordinator"); executor.setAccessible(true);
        ((ExecutorService) executor.get(service)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        fx(() -> null);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    private static WidePreviewSnapshot completeSnapshot(WidePreviewService service, RenderJob job, ColoringSettings coloring) throws Exception {
        var ready = new CompletableFuture<WidePreviewSnapshot>();
        service.request(job, coloring, WidePreviewSettings.CANDIDATE, snapshot -> {
            if (snapshot.readySamples() == snapshot.plan().job().width() * snapshot.plan().job().height()) ready.complete(snapshot);
        }, ready::completeExceptionally);
        return ready.get(10, TimeUnit.SECONDS);
    }

    @Test void sameSizedPreviewUpdatesReuseImageAndUploadBufferWithoutStalePixels() throws Exception {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);
        var job = new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(), 80, 60, 300);
        try (var service = new WidePreviewService(Runnable::run)) {
            var first = completeSnapshot(service, job, scene.coloring());
            var nextColoring = new ColoringSettings(PalettePreset.FIRE);
            var second = completeSnapshot(service, job, nextColoring);
            var resizedJob = new RenderJob(job.formula(), job.viewport(), 96, 64, 300);
            var resized = completeSnapshot(service, resizedJob, nextColoring);
            fx(() -> {
                var surface = new FractalSurface(); surface.resize(80, 60);
                surface.prepareWidePreview(job, scene.coloring(), scene.iterations(), WidePreviewSettings.CANDIDATE);
                surface.displayWidePreview(first);
                var image = reserve(surface).getImage();
                var upload = field(surface, "widePreviewUpload");
                surface.prepareWidePreview(job, nextColoring, scene.iterations(), WidePreviewSettings.CANDIDATE);
                surface.displayWidePreview(second);
                assertSame(image, reserve(surface).getImage(), "Palette changes reuse the raster texture");
                assertSame(upload, field(surface, "widePreviewUpload"), "Updates must not clone an upload array");
                assertArrayEquals(second.copyColors(), pixels(image));
                assertFalse(java.util.Arrays.equals(first.copyColors(), pixels(image)));
                surface.prepareWidePreview(resizedJob, nextColoring, scene.iterations(), WidePreviewSettings.CANDIDATE);
                surface.displayWidePreview(resized);
                assertNotSame(image, reserve(surface).getImage(), "A different grid needs a different sized image");
                assertArrayEquals(resized.copyColors(), pixels(reserve(surface).getImage()));
                return null;
            });
        }
    }

    @Test void thirtyVisibleCycleTicksDoNotCalculateOrUploadHiddenReserveAndStopRefreshesItsPalette() throws Exception {
        var firstPreview = new CompletableFuture<Void>();
        Object[] ui = fx(() -> {
            var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY));
            var view = new FractalView(scene);
            var surface = (FractalSurface) field(view, "fractalSurface");
            var controller = (FractalRenderController) field(view, "renderController");
            controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
            view.setOnRenderError(firstPreview::completeExceptionally);
            reserve(surface).imageProperty().addListener((observable, old, image) -> { if (image != null) firstPreview.complete(null); });
            var stage = new Stage(); stage.setScene(new Scene(view, 320, 240)); stage.show();
            return new Object[]{view, surface, controller, stage};
        });
        try {
            firstPreview.get(20, TimeUnit.SECONDS);
            var view = (FractalView) ui[0];
            var surface = (FractalSurface) ui[1];
            var controller = (FractalRenderController) ui[2];
            drainPreview(controller);
            fx(() -> { ((javafx.animation.AnimationTimer) field(view, "colorCycleTimer")).stop(); return null; });
            var image = fx(() -> reserve(surface).getImage());
            int[] before = fx(() -> pixels(((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage()));
            var wideService = field(controller, "widePreview");
            var generation = (java.util.concurrent.atomic.AtomicLong) field(wideService, "generation");
            var advance = FractalView.class.getDeclaredMethod("advanceColorCycle", long.class); advance.setAccessible(true);
            fx(() -> { view.setColorCycling(true); advance.invoke(view, 1_000_000_000L); return null; });
            long suspended = generation.get();
            for (int tick = 1; tick <= 30; tick++) {
                long now = 1_000_000_000L + tick * 34_000_000L;
                fx(() -> { advance.invoke(view, now); return null; });
                ((ExecutorService) field(controller, "recolorExecutor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
                fx(() -> null);
                drainPreview(controller);
                assertEquals(suspended, generation.get(), "Cycling must not submit or invalidate reserve jobs on each tick");
                fx(() -> { assertNull(reserve(surface).getImage()); return null; });
            }
            fx(() -> {
                assertFalse(java.util.Arrays.equals(before, pixels(((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage())),
                        "The visible image must really cycle, rather than suspending all recoloring");
                view.setColorCycling(false);
                return null;
            });
            drainPreview(controller);
            fx(() -> {
                assertSame(image, reserve(surface).getImage());
                assertEquals(view.sceneSnapshot().coloring(), field(surface, "widePreviewColoring"));
                var reserveFrame = (RenderFrame) field(wideService, "reserve");
                int[] expected = new int[reserveFrame.samplePlane().size()];
                new FractalColorizer().color(reserveFrame.samplePlane(), java.nio.IntBuffer.wrap(expected), view.sceneSnapshot().coloring().createStrategy());
                assertArrayEquals(expected, pixels(image), "Stopping resumes with the last applied visible palette");
                return null;
            });
        } finally { fx(() -> { ((FractalView) ui[0]).close(); ((Stage) ui[3]).close(); return null; }); }
    }

    @ParameterizedTest @EnumSource(InteractiveRenderMode.class)
    void reserveRunsAfterVisibleAndAaCompletionAndNeverEntersExport(InteractiveRenderMode mode) throws Exception {
        var complete = new CompletableFuture<Void>();
        var preview = new CompletableFuture<Void>();
        int[][] completedPixels = new int[1][];
        Object[] ui = fx(() -> {
            var surface = new FractalSurface();
            var stage = new Stage(); stage.setScene(new Scene(new StackPane(surface), 240, 160)); stage.show();
            surface.setOutputScale(2, 2); surface.resizeBuffer(240, 160);
            var controller = new FractalRenderController(surface);
            controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
            var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withAntialiasing(new AntialiasSettings(mode));
            controller.setOnRenderError(complete::completeExceptionally);
            controller.setOnRenderStatusChanged(status -> {
                if (status.state() == RenderStatus.State.COMPLETE) {
                    assertNull(reserve(surface).getImage(), "Background work starts after visible completion");
                    completedPixels[0] = pixels(surface.snapshot(null, null));
                    complete.complete(null);
                }
            });
            reserve(surface).imageProperty().addListener((observable, old, image) -> {
                if (image != null) {
                    if (!complete.isDone()) preview.completeExceptionally(new AssertionError("Preview competed with visible rendering"));
                    else preview.complete(null);
                }
            });
            controller.render(scene, new RenderTarget(480, 320, RenderPriority.center()), scene.viewport());
            return new Object[]{surface, controller, stage, scene};
        });
        try {
            complete.get(30, TimeUnit.SECONDS); preview.get(15, TimeUnit.SECONDS);
            drainPreview((FractalRenderController) ui[1]);
            fx(() -> {
                var surface = (FractalSurface) ui[0];
                var completed = surface.completedRender();
                assertTrue(completed.frame().isComplete());
                assertEquals(480, completed.frame().job().width());
                assertTrue(reserve(surface).getImage().getWidth() < 480);
                assertArrayEquals(completedPixels[0], pixels(surface.snapshot(null, null)),
                        "An opaque exact frame must completely cover the coarse layer");
                var scene = (FractalScene) ui[3];
                Viewport moved = scene.viewport().shiftedByPixels(-96, 64, 480, 320).zoom(1.25);
                surface.showPreview(moved);
                var bounds = reserve(surface).getBoundsInParent();
                assertTrue(bounds.getMinX() < 0 && bounds.getMaxX() > surface.getWidth());
                assertTrue(bounds.getMinY() < 0 && bounds.getMaxY() > surface.getHeight());
                Image projected = surface.snapshot(null, null);
                for (int x : new int[]{0, (int) projected.getWidth() - 1})
                    for (int y : new int[]{0, (int) projected.getHeight() - 1})
                        assertEquals(255, projected.getPixelReader().getArgb(x, y) >>> 24,
                                "Newly exposed corners must contain calculated reserve pixels");
                // Export owns only the exact frame, regardless of presentation transforms.
                assertSame(completed.frame(), surface.completedRender().frame());
                assertEquals(480 * 320, completed.frame().validity().readyPixelCount());
                return null;
            });
        } finally {
            fx(() -> { ((FractalRenderController) ui[1]).close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    @Test void configurationAndPaletteChangesRemoveOldPixelsAndCancelQueuedWork() throws Exception {
        var first = new CompletableFuture<Void>();
        Object[] ui = fx(() -> {
            var surface = new FractalSurface();
            var stage = new Stage(); stage.setScene(new Scene(new StackPane(surface), 160, 100)); stage.show();
            surface.setOutputScale(1, 1); surface.resizeBuffer(160, 100);
            var controller = new FractalRenderController(surface);
            controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
            var scene = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                    .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY));
            reserve(surface).imageProperty().addListener((observable, old, image) -> { if (image != null) first.complete(null); });
            controller.render(scene, new RenderTarget(160, 100, RenderPriority.center()), scene.viewport());
            return new Object[]{surface, controller, stage};
        });
        try {
            first.get(30, TimeUnit.SECONDS);
            var replacement = new CompletableFuture<Void>();
            fx(() -> {
                var surface = (FractalSurface) ui[0];
                var controller = (FractalRenderController) ui[1];
                reserve(surface).imageProperty().addListener((observable, old, image) -> { if (image != null) replacement.complete(null); });
                controller.setWidePreviewSettings(new WidePreviewSettings(3, 16, 10000, 500, 300));
                assertNull(reserve(surface).getImage());
                return null;
            });
            replacement.get(15, TimeUnit.SECONDS);
            fx(() -> {
                var surface = (FractalSurface) ui[0];
                var controller = (FractalRenderController) ui[1];
                controller.recolor(new ColoringSettings(PalettePreset.FIRE));
                assertNull(reserve(surface).getImage(), "The old palette must disappear synchronously");
                controller.cancelCurrent(); controller.close(); controller.close();
                assertNull(reserve(surface).getImage());
                return null;
            });
        } finally { fx(() -> { ((FractalRenderController) ui[1]).close(); ((Stage) ui[2]).close(); return null; }); }
    }

    @Test void adaptiveZoomOutKeepsReadyReserveUntilReplacedButExplicitIterationChangeDropsIt() throws Exception {
        var ready = new CompletableFuture<Void>();
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var direct = new DirectDoubleRenderBackend();
        RenderBackend backend = new RenderBackend() {
            public RenderFrame render(RenderFrame frame, java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<RenderRegion> region,
                                      java.util.function.Consumer<TileTimingStats> timings) throws InterruptedException {
                if (block.get()) { blocked.countDown(); release.await(); }
                return direct.render(frame, cancelled, region, timings);
            }
            public void close() { direct.close(); }
        };
        Object[] ui = fx(() -> {
            var surface = new FractalSurface();
            var stage = new Stage(); stage.setScene(new Scene(new StackPane(surface), 160, 100)); stage.show();
            surface.setOutputScale(1, 1); surface.resizeBuffer(160, 100);
            var controller = new FractalRenderController(surface, new FractalRenderService(backend));
            controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
            var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withViewport(new Viewport(-0.75, 0, 0.24));
            reserve(surface).imageProperty().addListener((observable, old, image) -> {
                if (image != null) ready.complete(null);
            });
            controller.render(scene, new RenderTarget(160, 100, RenderPriority.center()), FractalPreset.MANDELBROT.defaultViewport());
            return new Object[]{surface, controller, stage, scene};
        });
        try {
            ready.get(15, TimeUnit.SECONDS);
            drainPreview((FractalRenderController) ui[1]);
            block.set(true);
            fx(() -> {
                var surface = (FractalSurface) ui[0];
                var controller = (FractalRenderController) ui[1];
                var old = reserve(surface).getImage();
                var scene = (FractalScene) ui[3];
                var moved = scene.withViewport(scene.viewport().zoom(1.5));
                surface.showPreview(moved.viewport());
                controller.cancelCurrent();
                controller.render(moved, new RenderTarget(160, 100, RenderPriority.center()), FractalPreset.MANDELBROT.defaultViewport());
                assertSame(old, reserve(surface).getImage(), "An adaptive cap change does not invalidate approximate display pixels");
                var image = surface.snapshot(null, null);
                assertEquals(255, image.getPixelReader().getArgb(0, 0) >>> 24);
                controller.cancelCurrent();
                controller.render(moved.withIterations(new IterationSettings(1000, 50)),
                        new RenderTarget(160, 100, RenderPriority.center()), FractalPreset.MANDELBROT.defaultViewport());
                assertNull(reserve(surface).getImage(), "An explicit iteration policy change removes old preview pixels");
                return null;
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            fx(() -> { ((FractalRenderController) ui[1]).close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    @Test void olderPaletteCompletionCannotRestartPreviewWhileNewerRecolorIsPending() throws Exception {
        var ready = new CompletableFuture<Void>();
        var firstApplied = new CompletableFuture<Void>();
        var firstStarted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Object[] ui = fx(() -> {
            var surface = new FractalSurface();
            var stage = new Stage(); stage.setScene(new Scene(new StackPane(surface), 160, 100)); stage.show();
            surface.setOutputScale(1, 1); surface.resizeBuffer(160, 100);
            var controller = new FractalRenderController(surface, new FractalRenderService(),
                    (aa, frame, coloring, colors, backend) -> {
                        if (calls.incrementAndGet() == 1) { firstStarted.countDown(); firstRelease.await(); }
                        else { secondStarted.countDown(); secondRelease.await(); }
                        return aa.recolorCachedInto(frame, coloring, colors, backend);
                    });
            reserve(surface).imageProperty().addListener((observable, old, image) -> { if (image != null) ready.complete(null); });
            controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
            var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY));
            controller.render(scene, new RenderTarget(160, 100, RenderPriority.center()), scene.viewport());
            return new Object[]{surface, controller, stage};
        });
        try {
            ready.get(15, TimeUnit.SECONDS);
            fx(() -> { ((FractalRenderController) ui[1]).recolor(new ColoringSettings(PalettePreset.FIRE), () -> firstApplied.complete(null)); return null; });
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            fx(() -> { ((FractalRenderController) ui[1]).recolor(new ColoringSettings(PalettePreset.OCEAN)); return null; });
            firstRelease.countDown();
            firstApplied.get(5, TimeUnit.SECONDS);
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            fx(() -> null); // The older completion callback must finish before the background fence.
            var field = FractalRenderController.class.getDeclaredField("widePreview"); field.setAccessible(true);
            var service = field.get(ui[1]);
            var executorField = WidePreviewService.class.getDeclaredField("coordinator"); executorField.setAccessible(true);
            ((ExecutorService) executorField.get(service)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            fx(() -> {
                assertNull(reserve((FractalSurface) ui[0]).getImage(), "Pending visible recolor must keep background work suspended");
                return null;
            });
        } finally {
            firstRelease.countDown(); secondRelease.countDown();
            fx(() -> { ((FractalRenderController) ui[1]).close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    @Test void rejectedPolicyStaysDisabledUntilExplicitlyConfiguredAndCanBeDisabledAgain() throws Exception {
        var completed = new CompletableFuture<Void>();
        var shown = new CompletableFuture<Void>();
        Object[] ui = fx(() -> {
            var surface = new FractalSurface();
            var stage = new Stage(); stage.setScene(new Scene(new StackPane(surface), 160, 100)); stage.show();
            surface.setOutputScale(1, 1); surface.resizeBuffer(160, 100);
            var controller = new FractalRenderController(surface);
            controller.setOnRenderStatusChanged(status -> {
                if (status.state() == RenderStatus.State.COMPLETE) completed.complete(null);
            });
            reserve(surface).imageProperty().addListener((observable, old, image) -> { if (image != null) shown.complete(null); });
            var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY));
            controller.render(scene, new RenderTarget(160, 100, RenderPriority.center()), scene.viewport());
            return new Object[]{surface, controller, stage};
        });
        try {
            completed.get(15, TimeUnit.SECONDS);
            fx(() -> {
                var surface = (FractalSurface) ui[0];
                var controller = (FractalRenderController) ui[1];
                assertFalse(controller.widePreviewSettings().enabled());
                assertNull(reserve(surface).getImage());
                controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
                return null;
            });
            shown.get(10, TimeUnit.SECONDS);
            fx(() -> {
                var surface = (FractalSurface) ui[0];
                var controller = (FractalRenderController) ui[1];
                var exact = surface.completedRender().frame();
                controller.setWidePreviewSettings(WidePreviewSettings.DEFAULT);
                assertNull(reserve(surface).getImage());
                assertSame(exact, surface.completedRender().frame());
                return null;
            });
            var field = FractalRenderController.class.getDeclaredField("widePreview"); field.setAccessible(true);
            var service = field.get(ui[1]);
            var executorField = WidePreviewService.class.getDeclaredField("coordinator"); executorField.setAccessible(true);
            ((ExecutorService) executorField.get(service)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            fx(() -> { assertNull(reserve((FractalSurface) ui[0]).getImage()); return null; });
        } finally { fx(() -> { ((FractalRenderController) ui[1]).close(); ((Stage) ui[2]).close(); return null; }); }
    }
}
