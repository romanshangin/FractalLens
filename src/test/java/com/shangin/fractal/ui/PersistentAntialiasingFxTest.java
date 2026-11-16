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
import org.junit.jupiter.api.Test;
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
    /** {@link RenderFrameCache}'s charge for one base frame pixel. */
    private static final long BASE_BYTES_PER_PIXEL = Integer.BYTES + 2L * Double.BYTES + 2L;
    /** {@link RetainedRefinement}'s charge for one AA color pixel. */
    private static final long AA_COLOR_BYTES_PER_PIXEL = Integer.BYTES + 1L;

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
                          AtomicInteger calculatedFrames, AtomicReference<CompletableFuture<Void>> completed,
                          RenderGate gate) {

        void close() throws Exception {
            // Closing the controller closes the store, which writes what is still pending.
            fx(() -> { controller.close(); stage.close(); return null; });
        }
    }

    private Launch launch(boolean persistent) throws Exception {
        return launch(persistent, RenderFrameCache.DEFAULT_MAX_BYTES);
    }

    private Launch launch(boolean persistent, long frameCacheBytes) throws Exception {
        return launch(persistent, frameCacheBytes, InteractiveAntialiasService::recolorCachedInto);
    }

    private Launch launch(boolean persistent, long frameCacheBytes,
                          FractalRenderController.RecolorOperation recolorOperation) throws Exception {
        var calculatedFrames = new AtomicInteger();
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        var gate = new RenderGate();
        RenderBackend backend = countingBackend(calculatedFrames, gate);
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
                var controller = new FractalRenderController(surface, new FractalRenderService(rendering),
                        recolorOperation, new RenderFrameCache(frameCacheBytes));
                controller.setWidePreviewSettings(WidePreviewSettings.DISABLED);
                controller.setOnRenderStatusChanged(status -> {
                    if (status.state() == RenderStatus.State.COMPLETE) completed.get().complete(null);
                });
                return new Launch(surface, controller, stage, calculatedFrames, completed, gate);
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

    /**
     * Julia's AA, stored with its samples, is restored; showing Mandelbrot in a frame cache
     * with room for both base frames and AA colors only drops Julia's samples from memory.
     * Returning to Julia reads the stored samples again, so a palette change recolors them
     * without an AA pass, and the AA stored after the return still holds them.
     */
    @Test
    void aFractalRoundTripKeepsTheStoredAntialiasingSamples() throws Exception {
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.REFINED));
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.REFINED));
        var fire = new ColoringSettings(PalettePreset.FIRE);

        Launch reference = launch(false);
        int[] expectedFire;
        try {
            renderAndCapture(reference, julia);
            expectedFire = recolor(reference, fire);
        } finally {
            reference.close();
        }

        Launch first = launch(true);
        try {
            renderAndCapture(first, julia);
            assertTrue(aaProfile(first).samples() > 0, "The fixture must need antialiasing samples");
        } finally {
            first.close();
        }

        long pixels = (long) WIDTH * HEIGHT;
        Launch roundTrip = launch(true, 2 * (pixels * (BASE_BYTES_PER_PIXEL + AA_COLOR_BYTES_PER_PIXEL) + 256));
        try {
            renderAndCapture(roundTrip, julia);
            RenderFrame juliaFrame = activeFrame(roundTrip);
            assertNotNull(retainedSamples(roundTrip, juliaFrame), "The restored AA must bring its samples");
            renderAndCapture(roundTrip, mandelbrot);
            assertNull(retainedSamples(roundTrip, juliaFrame),
                    "The fixture must make the Mandelbrot frame displace Julia's AA samples");
            assertNotNull(fx(() -> frameCache(roundTrip).refinementFor(juliaFrame).orElse(null)),
                    "Julia's AA colors must stay cached");
            renderAndCapture(roundTrip, julia);
            assertNotNull(serviceSamples(roundTrip), "Returning to Julia must read its stored samples again");
            assertArrayEquals(expectedFire, recolor(roundTrip, fire),
                    "A palette change after the return must recolor the stored AA samples exactly");
            assertFalse(visiblyBusy(roundTrip), "No AA pass may follow the recolor after the return");
            renderAndCapture(roundTrip, julia);
        } finally {
            // Closing writes the colors-only AA still waiting for its settle delay.
            roundTrip.close();
        }

        Launch restarted = launch(true);
        try {
            renderAndCapture(restarted, julia);
            assertEquals(0, restarted.calculatedFrames().get(), "The base frame must be restored, not calculated");
            assertNotNull(retainedSamples(restarted), "The stored AA must keep its samples");
            assertArrayEquals(expectedFire, recolor(restarted, fire),
                    "A palette change must recolor the restored AA samples exactly");
            assertEquals(0, aaProfile(restarted).samples(), "Recoloring restored samples needs no AA pass");
            assertFalse(visiblyBusy(restarted), "No AA pass may follow the recolor");
        } finally {
            restarted.close();
        }
    }

    /**
     * A palette change while the returned frame still reads its stored samples is computed
     * before that frame is shown. It must be published once the visit finishes, and its AA
     * checked after that, instead of being dropped because the bound samples cover it.
     */
    @Test
    void aPaletteChangeWhileReturningToAFractalIsShownOnceItsSamplesAreBound() throws Exception {
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.REFINED));
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.REFINED));
        var fire = new ColoringSettings(PalettePreset.FIRE);

        Launch reference = launch(false);
        int[] expectedFire;
        try {
            renderAndCapture(reference, julia);
            expectedFire = recolor(reference, fire);
        } finally {
            reference.close();
        }
        Launch first = launch(true);
        try {
            renderAndCapture(first, julia);
        } finally {
            first.close();
        }

        long pixels = (long) WIDTH * HEIGHT;
        Launch roundTrip = launch(true, 2 * (pixels * (BASE_BYTES_PER_PIXEL + AA_COLOR_BYTES_PER_PIXEL) + 256));
        try {
            renderAndCapture(roundTrip, julia);
            RenderFrame juliaFrame = activeFrame(roundTrip);
            renderAndCapture(roundTrip, mandelbrot);
            assertNull(retainedSamples(roundTrip, juliaFrame),
                    "The fixture must make the Mandelbrot frame displace Julia's AA samples");

            var done = new CompletableFuture<Void>();
            roundTrip.gate().arm();
            fx(() -> {
                roundTrip.completed().set(done);
                roundTrip.controller().render(julia, new RenderTarget(WIDTH, HEIGHT, RenderPriority.center()),
                        julia.fractal().defaultViewport());
                return null;
            });
            roundTrip.gate().awaitEntered();
            var applied = new CompletableFuture<Void>();
            fx(() -> { roundTrip.controller().recolor(fire, () -> applied.complete(null)); return null; });
            awaitRecolorHandled(roundTrip);
            roundTrip.gate().release();
            done.get(15, TimeUnit.SECONDS);
            applied.get(15, TimeUnit.SECONDS);
            assertNotNull(serviceSamples(roundTrip), "Returning to Julia must read its stored samples again");
            assertArrayEquals(expectedFire, fx(() -> pixels(roundTrip.surface())),
                    "The palette changed during the return must be shown on the finished frame");
            assertEquals(fire, fx(() -> roundTrip.surface().completedRender().scene().coloring()));
            assertFalse(visiblyBusy(roundTrip), "The bound samples cover the new palette without an AA pass");
        } finally {
            roundTrip.gate().release();
            roundTrip.close();
        }
    }

    /**
     * A palette change computed before the returned frame binds its stored samples, but
     * published after that frame is shown, lacks their AA. It must be computed again with
     * them instead of replacing the AA with base colors.
     */
    @Test
    void aPaletteChangeComputedBeforeTheReturnedSamplesAreBoundIsComputedAgain() throws Exception {
        var mode = InteractiveRenderMode.FAST;
        var fire = new ColoringSettings(PalettePreset.FIRE);
        int[] expectedFire = referenceRecolor(mode, fire);
        var hold = new java.util.concurrent.atomic.AtomicBoolean();
        var computed = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        FractalRenderController.RecolorOperation held = (service, frame, coloring, colors, backend) -> {
            var timing = service.recolorCachedInto(frame, coloring, colors, backend);
            if (hold.compareAndSet(true, false)) {
                computed.countDown();
                assertTrue(publish.await(15, TimeUnit.SECONDS));
            }
            return timing;
        };
        Launch roundTrip = displaceJuliaSamples(mode, held);
        try {
            var done = startGatedReturn(roundTrip, julia(mode));
            var applied = new CompletableFuture<Void>();
            hold.set(true);
            fx(() -> { roundTrip.controller().recolor(fire, () -> applied.complete(null)); return null; });
            assertTrue(computed.await(15, TimeUnit.SECONDS), "The palette must be computed before the samples are bound");
            roundTrip.gate().release();
            done.get(15, TimeUnit.SECONDS);
            assertNotNull(serviceSamples(roundTrip), "Returning to Julia must read its stored samples again");
            publish.countDown();
            applied.get(15, TimeUnit.SECONDS);
            assertArrayEquals(expectedFire, fx(() -> pixels(roundTrip.surface())),
                    "The palette must be shown with the AA of the bound samples");
            assertFalse(visiblyBusy(roundTrip), "The bound samples cover the new palette without an AA pass");
        } finally {
            publish.countDown();
            roundTrip.gate().release();
            roundTrip.close();
        }
    }

    /**
     * A palette change deferred because the returned frame was not shown yet is superseded
     * by a newer one published while the visit's AA pass still runs: the visit must end in
     * the newer palette.
     */
    @Test
    void aDeferredPaletteChangeDoesNotOverrideANewerOne() throws Exception {
        var mode = InteractiveRenderMode.FAST;
        var fire = new ColoringSettings(PalettePreset.FIRE);
        var ice = julia(mode).coloring();
        Launch roundTrip = displaceJuliaSamples(mode, InteractiveAntialiasService::recolorCachedInto);
        var holdPass = new CountDownLatch(1);
        try {
            int[] expectedIce;
            var done = startGatedReturn(roundTrip, julia(mode));
            fx(() -> { roundTrip.controller().recolor(fire); return null; });
            awaitRecolorHandled(roundTrip);
            assertNotNull(deferredRecoloring(roundTrip), "The first palette must wait for the returned frame");
            // The visit's AA pass queues behind this task, so the newer palette is published first.
            blockAntialiasCoordinator(roundTrip, holdPass);
            roundTrip.gate().release();
            RenderFrame returned = activeFrame(roundTrip);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (fx(() -> roundTrip.surface().completedRender() == null
                    || roundTrip.surface().completedRender().frame() != returned)) {
                assertTrue(System.nanoTime() < deadline, "The returned frame must be shown");
            }
            var applied = new CompletableFuture<Void>();
            fx(() -> { roundTrip.controller().recolor(ice, () -> applied.complete(null)); return null; });
            applied.get(15, TimeUnit.SECONDS);
            expectedIce = fx(() -> pixels(roundTrip.surface()));
            holdPass.countDown();
            done.get(15, TimeUnit.SECONDS);
            awaitRecolorHandled(roundTrip);
            fx(() -> null);
            assertEquals(ice, fx(() -> roundTrip.surface().completedRender().scene().coloring()),
                    "The deferred palette must not replace the newer one");
            assertArrayEquals(expectedIce, fx(() -> pixels(roundTrip.surface())));
        } finally {
            holdPass.countDown();
            roundTrip.gate().release();
            roundTrip.close();
        }
    }

    /**
     * Returning to Julia in Base only after its AA samples were displaced still reads the
     * stored samples, kept hidden, so switching to an AA mode then needs no supersampling.
     */
    @ParameterizedTest
    @EnumSource(value = InteractiveRenderMode.class, names = {"REFINED", "FAST"})
    void anAntialiasingModeAfterABaseOnlyReturnReusesTheStoredSamples(InteractiveRenderMode mode) throws Exception {
        int[] expected;
        Launch reference = launch(false);
        try {
            expected = renderAndCapture(reference, julia(mode));
        } finally {
            reference.close();
        }
        Launch roundTrip = displaceJuliaSamples(mode, InteractiveAntialiasService::recolorCachedInto);
        try {
            renderAndCapture(roundTrip, julia(InteractiveRenderMode.BASE_ONLY));
            assertNotNull(serviceSamples(roundTrip), "Base only must bind the stored samples too");
            int[] shown = renderAndCapture(roundTrip, julia(mode));
            assertEquals(0, aaProfile(roundTrip).samples(), "The stored samples need no supersampling");
            assertArrayEquals(expected, shown, "The AA mode must show the stored AA exactly");
        } finally {
            roundTrip.close();
        }
    }

    private static FractalScene julia(InteractiveRenderMode mode) {
        return FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(mode));
    }

    /** Julia in {@code mode} recolored to {@code coloring} without any store. */
    private int[] referenceRecolor(InteractiveRenderMode mode, ColoringSettings coloring) throws Exception {
        Launch reference = launch(false);
        try {
            renderAndCapture(reference, julia(mode));
            return recolor(reference, coloring);
        } finally {
            reference.close();
        }
    }

    /**
     * Stores Julia's AA with its samples, then opens a launch whose frame cache has room for
     * the Julia and Mandelbrot base frames and AA colors only, and shows Julia, then Mandelbrot.
     */
    private Launch displaceJuliaSamples(InteractiveRenderMode mode,
                                        FractalRenderController.RecolorOperation recolorOperation) throws Exception {
        Launch first = launch(true);
        try {
            renderAndCapture(first, julia(mode));
        } finally {
            first.close();
        }
        long pixels = (long) WIDTH * HEIGHT;
        Launch roundTrip = launch(true, 2 * (pixels * (BASE_BYTES_PER_PIXEL + AA_COLOR_BYTES_PER_PIXEL) + 256),
                recolorOperation);
        try {
            renderAndCapture(roundTrip, julia(mode));
            RenderFrame juliaFrame = activeFrame(roundTrip);
            renderAndCapture(roundTrip, FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withAntialiasing(new AntialiasSettings(mode)));
            assertNull(retainedSamples(roundTrip, juliaFrame),
                    "The fixture must make the Mandelbrot frame displace Julia's AA samples");
            return roundTrip;
        } catch (Throwable failure) {
            roundTrip.close();
            throw failure;
        }
    }

    /** Starts returning to {@code scene}, held in the backend until the gate is released. */
    private static CompletableFuture<Void> startGatedReturn(Launch launch, FractalScene scene) throws Exception {
        var done = new CompletableFuture<Void>();
        launch.gate().arm();
        fx(() -> {
            launch.completed().set(done);
            launch.controller().render(scene, new RenderTarget(WIDTH, HEIGHT, RenderPriority.center()),
                    scene.fractal().defaultViewport());
            return null;
        });
        launch.gate().awaitEntered();
        return done;
    }

    private static Object deferredRecoloring(Launch launch) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("deferredRecoloring");
        field.setAccessible(true);
        return fx(() -> field.get(launch.controller()));
    }

    /** Occupies the AA service's coordinator until {@code release} opens. */
    private static void blockAntialiasCoordinator(Launch launch, CountDownLatch release) throws Exception {
        var serviceField = FractalRenderController.class.getDeclaredField("antialiasService");
        serviceField.setAccessible(true);
        var coordinatorField = InteractiveAntialiasService.class.getDeclaredField("coordinator");
        coordinatorField.setAccessible(true);
        var coordinator = (ExecutorService) coordinatorField.get(serviceField.get(launch.controller()));
        var started = new CountDownLatch(1);
        coordinator.execute(() -> {
            started.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    /** Waits until the latest palette change was computed and handled on the FX thread. */
    private static void awaitRecolorHandled(Launch launch) throws Exception {
        var requested = FractalRenderController.class.getDeclaredField("recolorSequence");
        var handled = FractalRenderController.class.getDeclaredField("publishedRecolorSequence");
        requested.setAccessible(true);
        handled.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(() -> ((java.util.concurrent.atomic.AtomicLong) handled.get(launch.controller())).get()
                != ((java.util.concurrent.atomic.AtomicLong) requested.get(launch.controller())).get())) {
            assertTrue(System.nanoTime() < deadline, "The palette change must be handled");
        }
    }

    /** True while the controller runs a visible pass, such as AA resampling after a recolor. */
    private static boolean visiblyBusy(Launch launch) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("visibleBusy");
        field.setAccessible(true);
        return fx(() -> field.getBoolean(launch.controller()));
    }

    /** The AA samples the interactive AA service holds for the displayed frame, or null. */
    private static Object serviceSamples(Launch launch) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("antialiasService");
        field.setAccessible(true);
        RenderFrame frame = activeFrame(launch);
        return fx(() -> ((InteractiveAntialiasService) field.get(launch.controller())).retainSamples(frame));
    }

    /** The AA samples retained beside the displayed frame, or null. */
    private static Object retainedSamples(Launch launch) throws Exception {
        return retainedSamples(launch, activeFrame(launch));
    }

    /** The AA samples retained beside {@code frame} in the frame cache, or null. */
    private static Object retainedSamples(Launch launch, RenderFrame frame) throws Exception {
        return fx(() -> frameCache(launch).refinementFor(frame).map(RetainedRefinement::samples).orElse(null));
    }

    private static RenderFrameCache frameCache(Launch launch) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("frameCache");
        field.setAccessible(true);
        return (RenderFrameCache) field.get(launch.controller());
    }

    private static RenderFrame activeFrame(Launch launch) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("activeFrame");
        field.setAccessible(true);
        return fx(() -> (RenderFrame) field.get(launch.controller()));
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

    /** Holds the next render of an already complete frame, such as a cached frame shown again. */
    private static final class RenderGate {
        private final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void arm() { armed.set(true); }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(15, TimeUnit.SECONDS), "The gated render must start");
        }

        void release() { released.countDown(); }

        void pass(RenderFrame frame) throws InterruptedException {
            if (frame.isComplete() && armed.compareAndSet(true, false)) {
                entered.countDown();
                assertTrue(released.await(15, TimeUnit.SECONDS));
            }
        }
    }

    private static RenderBackend countingBackend(AtomicInteger calculatedFrames, RenderGate gate) {
        var direct = new DirectDoubleRenderBackend();
        return new RenderBackend() {
            @Override
            public RenderFrame render(RenderFrame frame, java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<RenderRegion> region,
                                      java.util.function.Consumer<TileTimingStats> timings) throws InterruptedException {
                gate.pass(frame);
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
