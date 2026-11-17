package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.AntialiasSettings;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.WidePreviewSettings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A display scale (DPI) change between launches: the restored scene keeps its exact viewport,
 * so the render size follows the new scale. A stored frame whose render grid no longer matches
 * must not be shown, nor its AA; one whose grid matches exactly, because the window's pixel
 * size is unchanged, is a valid exact hit.
 */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class PersistentFrameDisplayScaleFxTest {

    private static final String FRAME_SUFFIX = ".frame";
    private static final String AA_SUFFIX = ".aa";
    /** A restored exploration view rather than the preset's home view. */
    private static final FractalScene SCENE = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
            .withViewport(new Viewport("-0.75", "0.1", "1.5"))
            .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.REFINED));

    @TempDir
    Path frameStore;

    @BeforeAll
    static void start() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stop() { Platform.exit(); }

    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    /** What one launch showed once its render, including AA, completed. */
    private record Shown(int width, int height, int[] argb, RenderFrame frame, int calculatedFrames,
                         InteractiveAntialiasService.Profile antialiasing) {
    }

    /**
     * The same logical window at another display scale needs another render size, so the
     * frame stored at the previous scale misses: the view is calculated and antialiased again,
     * exactly as without a store, and the previous scale's entry stays valid for its scale.
     */
    @ParameterizedTest(name = "stored at {0}x, restarted at {1}x")
    @CsvSource({"1.0, 2.0", "2.0, 1.0", "1.0, 1.5", "1.25, 1.0"})
    void aRestartAtAnotherDisplayScaleRendersAgainInsteadOfUsingTheStoredFrame(double storedScale,
                                                                               double restartScale)
            throws Exception {
        Shown expected = show(false, 160, 100, restartScale);

        Shown stored = show(true, 160, 100, storedScale);
        assertEquals(1, stored.calculatedFrames());
        assertTrue(stored.antialiasing().samples() > 0, "The fixture must need antialiasing samples");
        assertEquals(1, storedFiles(FRAME_SUFFIX), "The frame must be stored");
        assertEquals(1, storedFiles(AA_SUFFIX), "Its AA must be stored");
        assertNotEquals(stored.width() + "x" + stored.height(), expected.width() + "x" + expected.height(),
                "The fixture must change the render size");

        Shown restarted = show(true, 160, 100, restartScale);
        assertEquals(expected.width(), restarted.width());
        assertEquals(expected.height(), restarted.height());
        assertEquals(1, restarted.calculatedFrames(), "The frame stored at another scale must not be used");
        assertTrue(restarted.antialiasing().testedPixels() > 0, "The stored AA must not be used");
        assertTrue(restarted.antialiasing().samples() > 0, "AA is supersampled again at the new scale");
        assertSameSamples(expected.frame(), restarted.frame());
        assertArrayEquals(expected.argb(), restarted.argb(), "The new scale must show the recalculated view");
        assertEquals(2, storedFiles(FRAME_SUFFIX), "Each scale keeps its own stored frame");
        assertEquals(2, storedFiles(AA_SUFFIX), "Each scale keeps its own stored AA");

        Shown back = show(true, 160, 100, storedScale);
        assertEquals(0, back.calculatedFrames(), "Returning to the first scale restores its stored frame");
        assertEquals(0, back.antialiasing().testedPixels(), "Its stored AA needs no candidate detection");
        assertEquals(0, back.antialiasing().samples(), "Its stored AA needs no supersampling");
        assertSameSamples(stored.frame(), back.frame());
        assertArrayEquals(stored.argb(), back.argb());
    }

    /**
     * A scale change that leaves the window's pixel size unchanged, such as a window half as
     * wide at twice the scale, yields the same render grid for the same viewport, so the stored
     * frame and its AA are an exact hit with no base or AA work.
     */
    @ParameterizedTest(name = "stored at {0}x{1} pt and {2}x, restarted at {3}x{4} pt and {5}x")
    @CsvSource({"320, 200, 1.0, 160, 100, 2.0", "160, 100, 2.0, 320, 200, 1.0",
            "240, 150, 1.0, 160, 100, 1.5"})
    void aRestartAtAnotherDisplayScaleWithTheSamePixelSizeRestoresTheStoredFrame(
            int storedWidth, int storedHeight, double storedScale,
            int restartWidth, int restartHeight, double restartScale) throws Exception {
        Shown stored = show(true, storedWidth, storedHeight, storedScale);
        assertEquals(1, stored.calculatedFrames());
        assertTrue(stored.antialiasing().samples() > 0, "The fixture must need antialiasing samples");

        Shown restarted = show(true, restartWidth, restartHeight, restartScale);
        assertEquals(stored.width(), restarted.width(), "The fixture must keep the render size");
        assertEquals(stored.height(), restarted.height(), "The fixture must keep the render size");
        assertEquals(0, restarted.calculatedFrames(), "The matching stored frame must be restored");
        assertEquals(0, restarted.antialiasing().testedPixels(), "Restored AA needs no candidate detection");
        assertEquals(0, restarted.antialiasing().samples(), "Restored AA needs no supersampling");
        assertSameSamples(stored.frame(), restarted.frame());
        assertArrayEquals(stored.argb(), restarted.argb(), "The restored view must show the stored pixels");
        assertEquals(1, storedFiles(FRAME_SUFFIX), "The shared entry is not stored twice");
    }

    /** The size and viewport of a frame the store passed on for calculation, or one shown. */
    private record RenderCall(int width, int height, Viewport viewport) {
        static RenderCall of(RenderFrame frame) {
            var job = frame.request();
            return new RenderCall(job.width(), job.height(), job.viewport());
        }
    }

    /**
     * A launch in a shown window, whose display scale comes from the window as at startup,
     * after the frame was stored at another scale for the same window size: every frame
     * calculated or shown uses the window's scale and the restored viewport, so the stored
     * frame is never shown, and the first and final frames shown are the recalculated view.
     */
    @Test
    void aWindowLaunchAtANewDisplayScaleNeverRestoresTheFrameStoredAtThePreviousScale() throws Exception {
        double windowScale = fx(() -> Screen.getPrimary().getOutputScaleX());
        double storedScale = windowScale == 1.0 ? 2.0 : 1.0;
        Shown expected = show(false, 160, 100, windowScale);
        Shown stored = show(true, 160, 100, storedScale);
        assertTrue(stored.antialiasing().samples() > 0, "The fixture must need antialiasing samples");

        WindowLaunch launch = showInWindow(160, 100);
        assertEquals(windowScale, launch.outputScale(), "The window must report the primary display scale");
        assertFalse(launch.calculated().isEmpty(), "The frame stored at another scale must not be restored");
        assertCallsAt(launch.calculated(), expected.width(), expected.height());
        assertCallsAt(launch.shownFrames(), expected.width(), expected.height());
        assertTrue(launch.shown().antialiasing().samples() > 0, "The stored AA must not be used");
        assertSameSamples(expected.frame(), launch.shown().frame());
        assertArrayEquals(expected.argb(), launch.shown().argb(), "The window must show the recalculated view");
        assertEquals(2, storedFiles(FRAME_SUFFIX), "Each scale keeps its own stored frame");
    }

    /**
     * A launch in a shown window whose pixel size equals the size stored at another scale
     * restores that frame and its AA, and shows no other frame first.
     */
    @Test
    void aWindowLaunchWithTheStoredPixelSizeAtANewDisplayScaleRestoresTheStoredFrame() throws Exception {
        double windowScale = fx(() -> Screen.getPrimary().getOutputScaleX());
        double storedScale = windowScale == 1.0 ? 2.0 : 1.0;
        // About 320x200 render pixels at the window's scale, rounded up to even sizes as the
        // surface does; the stored window (at 1x or 2x) is sized to give the same render size.
        int windowWidth = (int) Math.round(320 / windowScale);
        int windowHeight = (int) Math.round(200 / windowScale);
        int[] windowRender = fx(() -> {
            var surface = new FractalSurface();
            surface.setOutputScale(windowScale, windowScale);
            return new int[]{surface.renderWidthFor(windowWidth), surface.renderHeightFor(windowHeight)};
        });
        int storedWidth = (int) (windowRender[0] / storedScale);
        int storedHeight = (int) (windowRender[1] / storedScale);
        // Snapshot bounds round to whole device pixels, so pixels are compared at the window's
        // geometry and scale: a fractional scale's snapshot can be narrower than the render.
        Shown expected = show(false, windowWidth, windowHeight, windowScale);
        Shown stored = show(true, storedWidth, storedHeight, storedScale);
        assertTrue(stored.antialiasing().samples() > 0, "The fixture must need antialiasing samples");
        assertArrayEquals(windowRender, new int[]{stored.width(), stored.height()},
                "The stored frame must have the window's render size");

        WindowLaunch launch = showInWindow(windowWidth, windowHeight);
        assertEquals(List.of(), launch.calculated(), "The matching stored frame must be restored");
        assertEquals(List.of(RenderCall.of(launch.shown().frame())), launch.shownFrames(),
                "No other frame may be shown first");
        assertCallsAt(launch.shownFrames(), stored.width(), stored.height());
        assertEquals(0, launch.shown().antialiasing().testedPixels(), "Restored AA needs no candidate detection");
        assertEquals(0, launch.shown().antialiasing().samples(), "Restored AA needs no supersampling");
        assertSameSamples(stored.frame(), launch.shown().frame());
        assertArrayEquals(expected.argb(), launch.shown().argb(),
                "The window must show the stored view as calculated at its own scale");
    }

    private static void assertCallsAt(List<RenderCall> calls, int width, int height) {
        for (RenderCall call : calls) {
            assertEquals(width + "x" + height, call.width() + "x" + call.height(),
                    "Every frame must use the window's display scale: " + calls);
            assertEquals(SCENE.viewport(), call.viewport(), "Every frame must keep the restored viewport");
        }
    }

    /**
     * A window launch: the frames its store passed on for calculation, every frame shown when
     * a render finished, in order, and the final result.
     */
    private record WindowLaunch(double outputScale, List<RenderCall> calculated, List<RenderCall> shownFrames,
                                Shown shown) {
    }

    /** Launches {@link #SCENE} over the store in a shown window, as the application does. */
    private WindowLaunch showInWindow(int logicalWidth, int logicalHeight) throws Exception {
        var calculated = new CopyOnWriteArrayList<RenderCall>();
        var calculatedFrames = new AtomicInteger();
        RenderBackend counting = countingBackend(calculatedFrames);
        // Inside the store, so the render service still sees the persistent backend.
        RenderBackend recording = new RenderBackend() {
            @Override
            public RenderFrame render(RenderFrame frame, java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<RenderRegion> region,
                                      java.util.function.Consumer<TileTimingStats> timings)
                    throws InterruptedException {
                if (!frame.isComplete()) calculated.add(RenderCall.of(frame));
                return counting.render(frame, cancelled, region, timings);
            }

            @Override
            public void close() { counting.close(); }
        };
        RenderBackend persistent = new PersistentFrameRenderBackend(recording, new PersistentFrameStore(frameStore));
        String profiling = System.getProperty("fractal.aa.profile");
        System.setProperty("fractal.aa.profile", "true");
        Object[] ui;
        try {
            ui = fx(() -> {
                var view = new FractalView(SCENE, true, new FractalRenderService(persistent));
                view.setWidePreviewSettings(WidePreviewSettings.DISABLED);
                var shownFrames = new CopyOnWriteArrayList<RenderCall>();
                view.setOnRenderingChanged(active -> {
                    var completed = surface(view).completedRender();
                    if (!active && completed != null) shownFrames.add(RenderCall.of(completed.frame()));
                });
                var stage = new Stage();
                stage.setScene(new Scene(view, logicalWidth, logicalHeight));
                stage.show();
                return new Object[]{view, stage, shownFrames};
            });
        } finally {
            if (profiling == null) System.clearProperty("fractal.aa.profile");
            else System.setProperty("fractal.aa.profile", profiling);
        }
        var view = (FractalView) ui[0];
        var stage = (Stage) ui[1];
        @SuppressWarnings("unchecked")
        var shownFrames = (List<RenderCall>) ui[2];
        try {
            awaitCompleted(view);
            return fx(() -> {
                double scale = stage.getOutputScaleX();
                FractalSurface surface = surface(view);
                RenderFrame frame = surface.completedRender().frame();
                assertEquals(logicalWidth, (int) view.getWidth(), "The window must keep its size");
                assertEquals(logicalHeight, (int) view.getHeight(), "The window must keep its size");
                return new WindowLaunch(scale, List.copyOf(calculated), List.copyOf(shownFrames),
                        new Shown(frame.request().width(), frame.request().height(), pixels(surface, scale),
                                frame, calculatedFrames.get(), aaProfile(view)));
            });
        } finally {
            fx(() -> { view.close(); stage.close(); return null; });
        }
    }

    /** Launches a view of {@link #SCENE} at a window size and display scale, then closes it. */
    private Shown show(boolean persistent, int logicalWidth, int logicalHeight, double outputScale)
            throws Exception {
        var calculatedFrames = new AtomicInteger();
        RenderBackend backend = countingBackend(calculatedFrames);
        if (persistent) backend = new PersistentFrameRenderBackend(backend, new PersistentFrameStore(frameStore));
        RenderBackend rendering = backend;
        String profiling = System.getProperty("fractal.aa.profile");
        System.setProperty("fractal.aa.profile", "true");
        FractalView view;
        try {
            view = fx(() -> new FractalView(SCENE, true, new FractalRenderService(rendering)));
        } finally {
            if (profiling == null) System.clearProperty("fractal.aa.profile");
            else System.setProperty("fractal.aa.profile", profiling);
        }
        try {
            fx(() -> {
                view.setWidePreviewSettings(WidePreviewSettings.DISABLED);
                // No window: the display scale is the one this launch sets.
                surface(view).setOutputScale(outputScale, outputScale);
                view.resize(logicalWidth, logicalHeight);
                view.layout();
                return null;
            });
            awaitCompleted(view);
            return fx(() -> {
                FractalSurface surface = surface(view);
                RenderFrame frame = surface.completedRender().frame();
                assertEquals(SCENE.viewport(), frame.request().viewport(), "The restored viewport is kept exactly");
                assertEquals(surface.renderWidthFor(logicalWidth), frame.request().width());
                assertEquals(surface.renderHeightFor(logicalHeight), frame.request().height());
                return new Shown(frame.request().width(), frame.request().height(),
                        pixels(surface, outputScale), frame, calculatedFrames.get(), aaProfile(view));
            });
        } finally {
            // Closing the view closes its store, which writes what is still pending.
            fx(() -> { view.close(); return null; });
        }
    }

    private static void awaitCompleted(FractalView view) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!fx(() -> view.isLatencyIdle() && !visiblyBusy(view)
                && surface(view).completedRender() != null
                && view.sceneSnapshot().equals(surface(view).completedRender().scene()))) {
            if (System.nanoTime() > deadline) fail("The view did not complete its render");
            // Each probe is a full FX queue round trip, so the loop awaits concrete state.
        }
    }

    private static boolean visiblyBusy(FractalView view) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("visibleBusy");
        field.setAccessible(true);
        return field.getBoolean(controller(view));
    }

    private static InteractiveAntialiasService.Profile aaProfile(FractalView view) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("antialiasService");
        field.setAccessible(true);
        return ((InteractiveAntialiasService) field.get(controller(view))).lastProfile();
    }

    private static FractalRenderController controller(FractalView view) throws Exception {
        var field = FractalView.class.getDeclaredField("renderController");
        field.setAccessible(true);
        return (FractalRenderController) field.get(view);
    }

    private static FractalSurface surface(FractalView view) {
        return (FractalSurface) view.getChildrenUnmodifiable().getFirst();
    }

    /** The displayed surface, including its AA layer, at the launch's device pixel scale. */
    private static int[] pixels(FractalSurface surface, double outputScale) {
        var parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(outputScale, outputScale));
        WritableImage image = surface.snapshot(parameters, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    private static void assertSameSamples(RenderFrame expected, RenderFrame actual) {
        SamplePlane source = expected.samplePlane();
        SamplePlane shown = actual.samplePlane();
        assertEquals(source.size(), shown.size());
        for (int index = 0; index < source.size(); index++) {
            assertEquals(source.iterations(index), shown.iterations(index), "iterations " + index);
            assertEquals(Double.doubleToRawLongBits(source.smoothIterations(index)),
                    Double.doubleToRawLongBits(shown.smoothIterations(index)), "smooth " + index);
            assertEquals(source.escaped(index), shown.escaped(index), "escaped " + index);
        }
    }

    private long storedFiles(String suffix) throws Exception {
        try (Stream<Path> files = Files.list(frameStore)) {
            return files.filter(path -> path.toString().endsWith(suffix)).count();
        }
    }

    private static RenderBackend countingBackend(AtomicInteger calculatedFrames) {
        var direct = new DirectDoubleRenderBackend();
        return new RenderBackend() {
            @Override
            public RenderFrame render(RenderFrame frame, java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<RenderRegion> region,
                                      java.util.function.Consumer<TileTimingStats> timings)
                    throws InterruptedException {
                if (!frame.isComplete()) calculatedFrames.incrementAndGet();
                return direct.render(frame, cancelled, region, timings);
            }

            @Override
            public void close() { direct.close(); }
        };
    }
}
