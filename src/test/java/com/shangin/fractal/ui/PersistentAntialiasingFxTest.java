package com.shangin.fractal.ui;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.controller.RenderStatus;
import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Antialiasing stored with a completed frame survives a restart: a new controller over the
 * same frame store shows the view without base calculation or any AA work.
 */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class PersistentAntialiasingFxTest {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 100;
    /** Stored antialiasing files, beside their {@code .frame} base frames. */
    private static final String AA_SUFFIX = ".aa";

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
        return task.get(15, TimeUnit.SECONDS);
    }

    /** One application launch: a controller over the shared store, or over no store at all. */
    private record Launch(FractalSurface surface, FractalRenderController controller, Stage stage,
                          AtomicInteger calculatedFrames, AtomicReference<CompletableFuture<Void>> completed) {

        void close() throws Exception {
            // Closing the controller closes the store, which writes what is still pending.
            fx(() -> { controller.close(); stage.close(); return null; });
        }
    }

    private Launch launch(boolean persistent) throws Exception {
        var calculatedFrames = new AtomicInteger();
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        RenderBackend backend = countingBackend(calculatedFrames);
        if (persistent) backend = new PersistentFrameRenderBackend(backend, new PersistentFrameStore(frameStore));
        RenderBackend rendering = backend;
        String profiling = System.getProperty("fractal.aa.profile");
        System.setProperty("fractal.aa.profile", "true");
        try {
            return fx(() -> {
                var surface = new FractalSurface();
                var stage = new Stage();
                stage.setScene(new Scene(new StackPane(surface), WIDTH, HEIGHT));
                stage.show();
                surface.setOutputScale(1, 1);
                surface.resizeBuffer(WIDTH, HEIGHT);
                var controller = new FractalRenderController(surface, new FractalRenderService(rendering));
                controller.setWidePreviewSettings(WidePreviewSettings.DISABLED);
                controller.setOnRenderStatusChanged(status -> {
                    if (status.state() == RenderStatus.State.COMPLETE) completed.get().complete(null);
                });
                return new Launch(surface, controller, stage, calculatedFrames, completed);
            });
        } finally {
            if (profiling == null) System.clearProperty("fractal.aa.profile");
            else System.setProperty("fractal.aa.profile", profiling);
        }
    }

    @ParameterizedTest
    @EnumSource(value = InteractiveRenderMode.class, names = {"REFINED", "FAST"})
    void aRestartRestoresTheStoredAntialiasingWithoutAnyAntialiasingWork(InteractiveRenderMode mode)
            throws Exception {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(mode));
        var fire = new ColoringSettings(PalettePreset.FIRE);

        Launch reference = launch(false);
        int[] expectedFire;
        try {
            renderAndCapture(reference, scene);
            expectedFire = recolor(reference, fire);
        } finally {
            reference.close();
        }

        Launch first = launch(true);
        int[] expected;
        try {
            expected = renderAndCapture(first, scene);
            assertEquals(1, first.calculatedFrames().get());
            assertTrue(aaProfile(first).samples() > 0, "The fixture must need antialiasing samples");
        } finally {
            first.close();
        }
        assertTrue(storedFiles(AA_SUFFIX) > 0, "The AA must be stored");

        Launch restarted = launch(true);
        try {
            int[] restored = renderAndCapture(restarted, scene);
            var pass = aaProfile(restarted);
            assertEquals(0, restarted.calculatedFrames().get(), "The base frame must be restored, not calculated");
            assertEquals(0, pass.testedPixels(), "Restored AA colors need no candidate detection");
            assertEquals(0, pass.samples(), "Restored AA needs no supersampling");
            assertArrayEquals(expected, restored, "The restored view must show the stored AA pixels");
            assertArrayEquals(expectedFire, recolor(restarted, fire),
                    "A palette change after the restart must recolor the restored AA samples exactly");
        } finally {
            restarted.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = InteractiveRenderMode.class, names = {"REFINED", "FAST"})
    void aRestartWithoutStoredAntialiasingRestoresTheBaseFrameAndAntialiasesAgain(InteractiveRenderMode mode)
            throws Exception {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(mode));
        Launch first = launch(true);
        int[] expected;
        try {
            expected = renderAndCapture(first, scene);
        } finally {
            first.close();
        }
        try (Stream<Path> files = Files.list(frameStore)) {
            for (Path file : files.filter(path -> path.toString().endsWith(AA_SUFFIX))
                    .toList()) {
                Files.delete(file);
            }
        }

        Launch restarted = launch(true);
        try {
            int[] restored = renderAndCapture(restarted, scene);
            assertEquals(0, restarted.calculatedFrames().get(), "The base frame must still be restored");
            assertTrue(aaProfile(restarted).samples() > 0, "Missing AA is calculated again");
            assertArrayEquals(expected, restored);
        } finally {
            restarted.close();
        }
        assertTrue(storedFiles(AA_SUFFIX) > 0, "The recalculated AA is stored again");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAntialiasingModeAfterARestartInBaseOnlyShowsTheStoredAntialiasing(boolean histogram)
            throws Exception {
        var ice = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE);
        var coloring = ice.coloring();
        var refined = ice.withColoring(new ColoringSettings(coloring.palette(), coloring.colorScale(),
                        coloring.offset(), histogram))
                .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.REFINED));
        Launch first = launch(true);
        int[] expected;
        try {
            expected = renderAndCapture(first, refined);
            assertTrue(aaProfile(first).samples() > 0, "The fixture must need antialiasing samples");
        } finally {
            first.close();
        }

        Launch restarted = launch(true);
        try {
            renderAndCapture(restarted, refined.withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY)));
            assertEquals(0, restarted.calculatedFrames().get(), "The base frame must be restored, not calculated");
            int[] shown = renderAndCapture(restarted, refined);
            var pass = aaProfile(restarted);
            assertEquals(0, pass.testedPixels(), "The stored AA colors need no candidate detection");
            assertEquals(0, pass.samples(), "The stored AA colors need no supersampling");
            assertArrayEquals(expected, shown, "The AA mode must show the stored AA pixels");
        } finally {
            restarted.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = InteractiveRenderMode.class, names = {"REFINED", "FAST"})
    void aRestartThatShowsTheStoredAntialiasingDoesNotRewriteIt(InteractiveRenderMode mode) throws Exception {
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(mode));
        Launch first = launch(true);
        try {
            renderAndCapture(first, scene);
        } finally {
            first.close();
        }
        Map<Path, Object> stored = storedFileKeys(AA_SUFFIX);
        assertFalse(stored.isEmpty(), "The AA must be stored");
        assumeTrue(stored.values().stream().allMatch(Objects::nonNull),
                "The file system must identify files to detect a rewrite");

        Launch restarted = launch(true);
        try {
            renderAndCapture(restarted, scene);
        } finally {
            // Closing writes whatever is still waiting for its settle delay.
            restarted.close();
        }
        assertEquals(stored, storedFileKeys(AA_SUFFIX), "Unchanged stored AA must not be rewritten");
    }

    private Map<Path, Object> storedFileKeys(String suffix) throws Exception {
        try (Stream<Path> files = Files.list(frameStore)) {
            Map<Path, Object> keys = new HashMap<>();
            for (Path file : files.filter(path -> path.toString().endsWith(suffix)).toList()) {
                keys.put(file, Files.readAttributes(file, BasicFileAttributes.class).fileKey());
            }
            return keys;
        }
    }

    private long storedFiles(String suffix) throws Exception {
        try (Stream<Path> files = Files.list(frameStore)) {
            return files.filter(path -> path.toString().endsWith(suffix)).count();
        }
    }

    private static InteractiveAntialiasService.Profile aaProfile(Launch launch) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("antialiasService");
        field.setAccessible(true);
        return ((InteractiveAntialiasService) field.get(launch.controller())).lastProfile();
    }

    private static int[] recolor(Launch launch, ColoringSettings coloring) throws Exception {
        var applied = new CompletableFuture<Void>();
        fx(() -> { launch.controller().recolor(coloring, () -> applied.complete(null)); return null; });
        applied.get(15, TimeUnit.SECONDS);
        return fx(() -> pixels(launch.surface()));
    }

    private static int[] pixels(FractalSurface surface) {
        WritableImage image = surface.snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    private static RenderBackend countingBackend(AtomicInteger calculatedFrames) {
        var direct = new DirectDoubleRenderBackend();
        return new RenderBackend() {
            @Override
            public RenderFrame render(RenderFrame frame, java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<RenderRegion> region,
                                      java.util.function.Consumer<TileTimingStats> timings) throws InterruptedException {
                if (!frame.isComplete()) calculatedFrames.incrementAndGet();
                return direct.render(frame, cancelled, region, timings);
            }

            @Override
            public void close() { direct.close(); }
        };
    }

    private static int[] renderAndCapture(Launch launch, FractalScene scene) throws Exception {
        var done = new CompletableFuture<Void>();
        fx(() -> {
            launch.completed().set(done);
            launch.controller().cancelCurrent();
            launch.controller().render(scene, new RenderTarget(WIDTH, HEIGHT, RenderPriority.center()),
                    scene.fractal().defaultViewport());
            return null;
        });
        done.get(15, TimeUnit.SECONDS);
        return fx(() -> {
            assertSame(scene, launch.surface().completedRender().scene(),
                    "The completed render must belong to this scene");
            return pixels(launch.surface());
        });
    }
}
