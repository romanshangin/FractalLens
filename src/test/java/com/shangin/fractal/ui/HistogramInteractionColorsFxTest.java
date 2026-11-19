package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.HistogramPaletteColoring;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.FractalColorizer;
import com.shangin.fractal.render.FractalRenderService;
import com.shangin.fractal.render.FrameReusePlanner;
import com.shangin.fractal.render.RenderBackend;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderPriority;
import com.shangin.fractal.render.RenderRegion;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.render.SamplePlane;
import com.shangin.fractal.render.TileTimingStats;
import com.shangin.fractal.render.WidePreviewSnapshot;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.WidePreviewSettings;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Histogram Coloring during interaction: pixels shown before a frame completes use the
 * last completed frame's histogram, and the completed frame shows only its own mapping.
 */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class HistogramInteractionColorsFxTest {

    private static final int WIDTH = 80;
    private static final int HEIGHT = 60;
    private static final int PAN_PIXELS = 20;
    private static final ColoringSettings HISTOGRAM = histogram();
    private static final FractalScene SCENE = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
            .withViewport(new Viewport("-0.75", "0.1", "0.6"))
            .withColoring(HISTOGRAM);

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @Test
    void progressiveTilesAfterAPanUseTheCompletedFramesHistogram() throws Exception {
        GatedBackend backend = new GatedBackend();
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface,
                new FractalRenderService(backend)));
        FractalScene panned = pan(SCENE, PAN_PIXELS);
        try {
            fx(() -> {
                controller.setWidePreviewSettings(WidePreviewSettings.DISABLED);
                surface.resize(WIDTH, HEIGHT);
                surface.resizeBuffer(WIDTH, HEIGHT);
                return null;
            });
            render(surface, controller, SCENE, InteractiveRenderMode.BASE_ONLY);
            RenderFrame first = fx(() -> surface.completedRender().frame());
            int[] firstShown = displayed(surface);
            HistogramPaletteColoring reference =
                    (HistogramPaletteColoring) HISTOGRAM.createStrategy(first.samplePlane());
            assertArrayEquals(colors(first.samplePlane(), reference), firstShown,
                    "The fixture must show the completed frame's histogram");

            // The pan calculates every new sample, then waits before it completes.
            backend.gate.set(true);
            CountDownLatch completed = startRender(controller, panned, InteractiveRenderMode.BASE_ONLY);
            assertTrue(backend.calculated.await(15, TimeUnit.SECONDS));
            RenderFrame second = activeFrame(controller);
            int[] expected = colors(second.samplePlane(), HISTOGRAM.createProgressiveStrategy(reference));
            int[] plain = colors(second.samplePlane(), HISTOGRAM.createStrategy());
            assertFalse(Arrays.equals(expected, plain), "The fixture must distinguish the two mappings");
            awaitCondition(() -> Arrays.equals(expected, progressive(surface)));
            int[] shown = progressive(surface);
            assertArrayEquals(expected, shown, "Progressive tiles must use the completed frame's histogram");
            // The reused center keeps exactly the colors the completed frame showed.
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH - PAN_PIXELS; x++) {
                    assertEquals(firstShown[y * WIDTH + x + PAN_PIXELS], shown[y * WIDTH + x],
                            "Reused pixel (" + x + ", " + y + ") changed color during the pan");
                }
            }

            backend.release.countDown();
            assertTrue(completed.await(15, TimeUnit.SECONDS));
            assertArrayEquals(colors(second.samplePlane(), HISTOGRAM.createStrategy(second.samplePlane())),
                    displayed(surface), "The completed frame must show its own histogram");
        } finally {
            backend.release.countDown();
            fx(() -> { controller.close(); return null; });
        }
    }

    @ParameterizedTest
    @EnumSource(value = InteractiveRenderMode.class, names = {"FAST", "REFINED"})
    void antialiasingReusedAcrossAPanFollowsTheNewHistogramAndOnRevisits(InteractiveRenderMode mode)
            throws Exception {
        FractalScene panned = pan(SCENE, PAN_PIXELS);
        int[] expectedPanned = freshRender(panned, mode);
        int[] expectedOriginal = freshRender(SCENE, mode);
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        try {
            fx(() -> {
                surface.resize(WIDTH, HEIGHT);
                surface.resizeBuffer(WIDTH, HEIGHT);
                return null;
            });
            render(surface, controller, SCENE, mode);
            assertTrue(fx(() -> surface.refinedPixelCount(surface.completedRender().frame())) > 0,
                    "The source frame must contain antialiased pixels to reuse");
            render(surface, controller, panned, mode);
            assertArrayEquals(expectedPanned, displayed(surface),
                    "Reused AA pixels must not keep the source frame's histogram colors");
            // Pans between overlapping frames reuse the active frame and antialias again.
            render(surface, controller, SCENE, mode);
            assertArrayEquals(expectedOriginal, displayed(surface), "Revisited original frame");
            render(surface, controller, panned, mode);
            assertArrayEquals(expectedPanned, displayed(surface), "Revisited panned frame");

            // Another fractal leaves no active frame to shift, so returning to the panned frame
            // takes it from the frame cache with its retained AA, which must not be recalculated.
            render(surface, controller, FractalScene.create(FractalPreset.BURNING_SHIP, PalettePreset.ICE)
                    .withColoring(HISTOGRAM), mode);
            fx(() -> { surface.latency().arm(ignored -> {}); return null; });
            render(surface, controller, panned, mode);
            var published = fx(() -> surface.latency().stop()).stream()
                    .map(InteractionLatency.Event::stage).toList();
            assertArrayEquals(expectedPanned, displayed(surface), "Panned frame restored from the frame cache");
            assertFalse(published.contains("aa_publish"),
                    "The retained AA of the panned frame must be restored, not recalculated: " + published);
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @ParameterizedTest
    @EnumSource(value = InteractiveRenderMode.class, names = {"BASE_ONLY", "FAST"})
    void aCropOfACompletedFrameShowsItsOwnHistogram(InteractiveRenderMode mode) throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        FractalCamera camera = new FractalCamera(FractalPreset.MANDELBROT);
        try {
            camera.resize(120, 100);
            camera.zoomBy(60, 50, 0.25, 120, 100, 120, 100);
            fx(() -> {
                surface.resize(120, 100);
                surface.resizeBuffer(120, 100);
                return null;
            });
            render(surface, controller, FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withViewport(camera.viewport()).withColoring(HISTOGRAM), mode, 120, 100,
                    camera.defaultViewport(120, 100), false);
            camera.resize(80, 60, 100, 60);
            fx(() -> {
                surface.resize(80, 60);
                surface.resizeBuffer(80, 60);
                return null;
            });
            render(surface, controller, FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withViewport(camera.viewport()).withColoring(HISTOGRAM), mode, 80, 60,
                    camera.defaultViewport(80, 60), true);
            RenderFrame crop = fx(() -> surface.completedRender().frame());
            assertEquals(80, crop.request().width());
            int[] expected = colors(crop.samplePlane(), HISTOGRAM.createStrategy(crop.samplePlane()));
            int[] shown = displayed(surface);
            if (mode == InteractiveRenderMode.BASE_ONLY) {
                assertArrayEquals(expected, shown, "The crop must show its own histogram");
            } else {
                int same = 0;
                for (int index = 0; index < expected.length; index++) if (expected[index] == shown[index]) same++;
                assertTrue(same > expected.length * 3 / 4,
                        "Only AA pixels may differ from the crop's own histogram: " + same + " of " + expected.length);
            }
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void widePreviewUsesTheCompletedFramesHistogram() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        try {
            fx(() -> {
                controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
                surface.resize(WIDTH, HEIGHT);
                surface.resizeBuffer(WIDTH, HEIGHT);
                return null;
            });
            render(surface, controller, SCENE, InteractiveRenderMode.BASE_ONLY);
            RenderFrame visible = fx(() -> surface.completedRender().frame());
            ColoringStrategy expectedColoring = HISTOGRAM.createProgressiveStrategy(
                    (HistogramPaletteColoring) HISTOGRAM.createStrategy(visible.samplePlane()));
            awaitCompleteReserve(surface);
            assertReserveColoredWith(surface, expectedColoring);
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void aReserveOfTheOlderHistogramIsHiddenUntilItIsColoredAgain() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        CountDownLatch release = new CountDownLatch(1);
        try {
            fx(() -> {
                controller.setWidePreviewSettings(WidePreviewSettings.CANDIDATE);
                surface.resize(WIDTH, HEIGHT);
                surface.resizeBuffer(WIDTH, HEIGHT);
                return null;
            });
            render(surface, controller, SCENE, InteractiveRenderMode.BASE_ONLY);
            awaitCompleteReserve(surface);

            // The reserve worker is held, so the next frame's reserve cannot be published yet.
            CountDownLatch held = new CountDownLatch(1);
            previewCoordinator(controller).execute(() -> {
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(held.await(15, TimeUnit.SECONDS));
            render(surface, controller, pan(SCENE, PAN_PIXELS), InteractiveRenderMode.BASE_ONLY);
            RenderFrame visible = fx(() -> surface.completedRender().frame());
            assertEquals(null, fx(() -> widePreviewSnapshot(surface)),
                    "A reserve colored with the previous frame's histogram must not stay shown");
            assertEquals(null, fx(() -> reserve(surface).getImage()));

            release.countDown();
            awaitCompleteReserve(surface);
            assertReserveColoredWith(surface, HISTOGRAM.createProgressiveStrategy(
                    (HistogramPaletteColoring) HISTOGRAM.createStrategy(visible.samplePlane())));
        } finally {
            release.countDown();
            fx(() -> { controller.close(); return null; });
        }
    }

    @Test
    void renderingTheDisplayedGridAgainKeepsItsHistogramAntialiasing() throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        try {
            fx(() -> {
                surface.resize(WIDTH, HEIGHT);
                surface.resizeBuffer(WIDTH, HEIGHT);
                return null;
            });
            render(surface, controller, SCENE, InteractiveRenderMode.FAST);
            int[] antialiased = displayed(surface);
            fx(() -> { surface.latency().arm(ignored -> {}); return null; });
            render(surface, controller, SCENE, InteractiveRenderMode.FAST);
            var published = fx(() -> surface.latency().stop()).stream()
                    .map(InteractionLatency.Event::stage).toList();
            assertFalse(published.contains("aa_publish"),
                    "AA of the same grid and histogram must be reused, not recalculated: " + published);
            assertArrayEquals(antialiased, displayed(surface));
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    private static void awaitCompleteReserve(FractalSurface surface) throws Exception {
        awaitCondition(() -> {
            WidePreviewSnapshot snapshot = fx(() -> widePreviewSnapshot(surface));
            return snapshot != null && snapshot.readySamples()
                    == snapshot.plan().job().width() * snapshot.plan().job().height();
        });
    }

    /** Recalculates the shown reserve's grid and compares its colors under {@code coloring}. */
    private static void assertReserveColoredWith(FractalSurface surface, ColoringStrategy coloring)
            throws Exception {
        WidePreviewSnapshot snapshot = fx(() -> widePreviewSnapshot(surface));
        RenderFrame reserve = new FrameReusePlanner().createFrame(null, snapshot.plan().job());
        try (DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend()) {
            direct.render(reserve, () -> false, ignored -> {}, null);
        }
        int[] expected = colors(reserve.samplePlane(), coloring);
        assertFalse(Arrays.equals(expected, colors(reserve.samplePlane(), HISTOGRAM.createStrategy())),
                "The fixture must distinguish the two mappings");
        assertArrayEquals(expected, snapshot.copyColors(),
                "Wide-preview pixels must use the completed frame's histogram");
    }

    private static java.util.concurrent.ExecutorService previewCoordinator(FractalRenderController controller)
            throws Exception {
        var serviceField = FractalRenderController.class.getDeclaredField("widePreview");
        serviceField.setAccessible(true);
        Object service = serviceField.get(controller);
        var coordinator = com.shangin.fractal.render.WidePreviewService.class.getDeclaredField("coordinator");
        coordinator.setAccessible(true);
        return (java.util.concurrent.ExecutorService) coordinator.get(service);
    }

    private static ImageView reserve(FractalSurface surface) {
        return (ImageView) surface.getChildrenUnmodifiable().getLast();
    }

    private static int[] freshRender(FractalScene scene, InteractiveRenderMode mode) throws Exception {
        FractalSurface surface = fx(FractalSurface::new);
        FractalRenderController controller = fx(() -> new FractalRenderController(surface));
        try {
            fx(() -> {
                surface.resize(WIDTH, HEIGHT);
                surface.resizeBuffer(WIDTH, HEIGHT);
                return null;
            });
            render(surface, controller, scene, mode);
            return displayed(surface);
        } finally {
            fx(() -> { controller.close(); return null; });
        }
    }

    private static ColoringSettings histogram() {
        ColoringSettings ice = new ColoringSettings(PalettePreset.ICE);
        return new ColoringSettings(ice.palette(), ice.paletteStops(), ice.colorScale(), ice.offset(),
                true, OrbitTrap.NONE);
    }

    private static FractalScene pan(FractalScene scene, int pixels) {
        BigDecimal unit = scene.viewport().imaginaryUnitsPerPixelExact(HEIGHT);
        return scene.withViewport(scene.viewport().pan(unit.multiply(BigDecimal.valueOf(pixels)), BigDecimal.ZERO));
    }

    private static int[] colors(SamplePlane samples, ColoringStrategy coloring) {
        int[] colors = new int[samples.size()];
        new FractalColorizer().color(samples, IntBuffer.wrap(colors), coloring);
        return colors;
    }

    private static void render(FractalSurface surface, FractalRenderController controller, FractalScene scene,
                               InteractiveRenderMode mode) throws Exception {
        render(surface, controller, scene, mode, WIDTH, HEIGHT, scene.fractal().defaultViewport(), false);
    }

    private static void render(FractalSurface surface, FractalRenderController controller, FractalScene scene,
                               InteractiveRenderMode mode, int width, int height, Viewport defaultViewport,
                               boolean preserveIterationLimit) throws Exception {
        assertTrue(startRender(controller, scene, mode, width, height, defaultViewport, preserveIterationLimit)
                .await(30, TimeUnit.SECONDS));
        fx(() -> null);
    }

    private static CountDownLatch startRender(FractalRenderController controller, FractalScene scene,
                                              InteractiveRenderMode mode) throws Exception {
        return startRender(controller, scene, mode, WIDTH, HEIGHT, scene.fractal().defaultViewport(), false);
    }

    private static CountDownLatch startRender(FractalRenderController controller, FractalScene scene,
                                              InteractiveRenderMode mode, int width, int height,
                                              Viewport defaultViewport, boolean preserveIterationLimit)
            throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            controller.cancelCurrent();
            boolean[] started = {false};
            controller.setOnRenderingChanged(active -> {
                if (active) started[0] = true;
                else if (started[0]) completed.countDown();
            });
            controller.render(scene.withAntialiasing(new AntialiasSettings(mode)),
                    new RenderTarget(width, height, RenderPriority.center()),
                    defaultViewport, preserveIterationLimit);
            return null;
        });
        return completed;
    }

    private static RenderFrame activeFrame(FractalRenderController controller) throws Exception {
        return fx(() -> {
            var field = FractalRenderController.class.getDeclaredField("activeFrame");
            field.setAccessible(true);
            return (RenderFrame) field.get(controller);
        });
    }

    private static WidePreviewSnapshot widePreviewSnapshot(FractalSurface surface) throws Exception {
        var field = FractalSurface.class.getDeclaredField("widePreviewSnapshot");
        field.setAccessible(true);
        return (WidePreviewSnapshot) field.get(surface);
    }

    private static int[] displayed(FractalSurface surface) throws Exception {
        return fx(() -> pixels(((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage()));
    }

    /** The staging image that shows progressive tiles above the displayed frame. */
    private static int[] progressive(FractalSurface surface) throws Exception {
        return fx(() -> {
            Image image = ((ImageView) surface.getChildrenUnmodifiable().get(2)).getImage();
            return image == null ? new int[0] : pixels(image);
        });
    }

    private static int[] pixels(Image image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    /** Polls from the test thread; {@code condition} reads FX state through {@link #fx}. */
    private static void awaitCondition(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            fx(() -> null);
        }
        throw new AssertionError("Condition not reached within 15 s");
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }

    /** Calculates normally; once gated, holds a finished frame back from completion. */
    private static final class GatedBackend implements RenderBackend {
        private final DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
        private final AtomicBoolean gate = new AtomicBoolean();
        private final CountDownLatch calculated = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled, Consumer<RenderRegion> region,
                                  Consumer<TileTimingStats> timings) throws InterruptedException {
            RenderFrame result = direct.render(frame, cancelled, region, timings);
            if (gate.get()) {
                calculated.countDown();
                release.await();
            }
            return result;
        }

        @Override
        public void close() {
            direct.close();
        }
    }
}
