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

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Completed frames survive fractal switches, so returning to a retained view calculates nothing. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class FractalSwitchFrameRetentionFxTest {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 100;

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

    @Test
    void returningToAnotherFractalsRetainedViewReusesItsBaseSamples() throws Exception {
        var calculatedFrames = new AtomicInteger();
        RenderBackend counting = countingBackend(calculatedFrames);
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        Object[] ui = fx(() -> {
            var surface = new FractalSurface();
            var stage = new Stage();
            stage.setScene(new Scene(new StackPane(surface), WIDTH, HEIGHT));
            stage.show();
            surface.setOutputScale(1, 1);
            surface.resizeBuffer(WIDTH, HEIGHT);
            var controller = new FractalRenderController(surface, new FractalRenderService(counting));
            controller.setWidePreviewSettings(WidePreviewSettings.DISABLED);
            controller.setOnRenderStatusChanged(status -> {
                if (status.state() == RenderStatus.State.COMPLETE) completed.get().complete(null);
            });
            return new Object[]{surface, controller, stage};
        });
        var surface = (FractalSurface) ui[0];
        var controller = (FractalRenderController) ui[1];
        var baseOnly = new AntialiasSettings(InteractiveRenderMode.BASE_ONLY);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(baseOnly);
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE).withAntialiasing(baseOnly);
        try {
            int[] first = renderAndCapture(controller, surface, completed, mandelbrot);
            int[] juliaPixels = renderAndCapture(controller, surface, completed, julia);
            assertEquals(2, calculatedFrames.get(), "Each fractal's first view is calculated once");
            assertFalse(java.util.Arrays.equals(first, juliaPixels), "The fixture fractals must differ");

            int[] returned = renderAndCapture(controller, surface, completed, mandelbrot);
            assertEquals(2, calculatedFrames.get(), "Returning to Mandelbrot must not recalculate its samples");
            assertArrayEquals(first, returned, "The retained frame must show the original pixels");

            renderAndCapture(controller, surface, completed, julia);
            assertEquals(2, calculatedFrames.get(), "Returning to Julia must not recalculate its samples");

            renderAndCapture(controller, surface, completed,
                    julia.withJuliaParameters(new JuliaParameters(0.285, 0.01)));
            assertEquals(3, calculatedFrames.get(), "Another Julia constant must calculate new samples");
        } finally {
            fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = InteractiveRenderMode.class, names = {"REFINED", "FAST"})
    void returningToAnotherFractalsRetainedViewReusesItsAntialiasing(InteractiveRenderMode mode) throws Exception {
        var calculatedFrames = new AtomicInteger();
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        Object[] ui = profiledUi(calculatedFrames, completed);
        var surface = (FractalSurface) ui[0];
        var controller = (FractalRenderController) ui[1];
        var antialiasing = new AntialiasSettings(mode);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(antialiasing);
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE).withAntialiasing(antialiasing);
        try {
            int[] first = renderAndCapture(controller, surface, completed, mandelbrot);
            var firstPass = aaProfile(controller);
            assertTrue(firstPass.samples() > 0, "The fixture must need antialiasing samples");
            int[] firstFire = recolor(controller, surface, new ColoringSettings(PalettePreset.FIRE));
            recolor(controller, surface, mandelbrot.coloring());
            renderAndCapture(controller, surface, completed, julia);
            assertTrue(aaProfile(controller).samples() > 0);

            int[] returned = renderAndCapture(controller, surface, completed, mandelbrot);
            var returnPass = aaProfile(controller);
            assertEquals(2, calculatedFrames.get(), "Returning must not recalculate base samples");
            assertEquals(0, returnPass.testedPixels(), "Retained AA pixels need no candidate detection");
            assertEquals(0, returnPass.samples(), "Retained AA pixels need no supersampling");
            assertArrayEquals(first, returned, "The retained AA must show the original pixels");
            assertArrayEquals(firstFire, recolor(controller, surface, new ColoringSettings(PalettePreset.FIRE)),
                    "Palette changes on the returned frame must keep its restored AA samples");
            recolor(controller, surface, mandelbrot.coloring());

            // Another palette keeps the palette-independent samples, so no retained candidate is sampled again.
            var fire = julia.withColoring(new ColoringSettings(PalettePreset.FIRE));
            renderAndCapture(controller, surface, completed, fire);
            var juliaReturn = aaProfile(controller);
            assertEquals(2, calculatedFrames.get());
            assertTrue(juliaReturn.testedPixels() > 0, "Another palette re-detects candidates from its colors");
            assertTrue(juliaReturn.samples() < firstPass.samples() || juliaReturn.samples() == 0,
                    "Retained samples must replace most supersampling: " + juliaReturn.samples());
        } finally {
            fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    @Test
    void aVisitWithoutAntialiasingKeepsTheRetainedAntialiasingForTheNextVisit() throws Exception {
        var calculatedFrames = new AtomicInteger();
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        Object[] ui = profiledUi(calculatedFrames, completed);
        var surface = (FractalSurface) ui[0];
        var controller = (FractalRenderController) ui[1];
        var fast = new AntialiasSettings(InteractiveRenderMode.FAST);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(fast);
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE).withAntialiasing(fast);
        try {
            int[] first = renderAndCapture(controller, surface, completed, mandelbrot);
            renderAndCapture(controller, surface, completed, julia);
            // The Base only visit reuses the cached frame without an AA pass.
            renderAndCapture(controller, surface, completed,
                    mandelbrot.withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY)));
            renderAndCapture(controller, surface, completed, julia);

            int[] returned = renderAndCapture(controller, surface, completed, mandelbrot);
            var returnPass = aaProfile(controller);
            assertEquals(2, calculatedFrames.get());
            assertEquals(0, returnPass.testedPixels(), "The AA retained before the Base only visit must survive it");
            assertEquals(0, returnPass.samples());
            assertArrayEquals(first, returned);
        } finally {
            fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    /** {@code presentationChange}: the pass antialiases a frame first shown as Base only, in place. */
    @org.junit.jupiter.params.ParameterizedTest(name = "presentationChange={0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aRecolorDuringAntialiasingIsNeverRestoredAsFinishedAntialiasing(boolean presentationChange) throws Exception {
        var fast = new AntialiasSettings(InteractiveRenderMode.FAST);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(fast);
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE).withAntialiasing(fast);
        var fire = new ColoringSettings(PalettePreset.FIRE);
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        int[] expected;
        Object[] reference = profiledUi(new AtomicInteger(), completed);
        try {
            expected = renderAndCapture((FractalRenderController) reference[1],
                    (FractalSurface) reference[0], completed, mandelbrot);
        } finally {
            fx(() -> { ((FractalRenderController) reference[1]).close(); ((Stage) reference[2]).close(); return null; });
        }

        // The recolor starts at base completion; only a run where it is published before the
        // AA pass completes mixes palettes in the displayed AA, so retry until one does.
        boolean mixedExercised = false;
        for (int attempt = 0; attempt < 10 && !mixedExercised; attempt++) {
            var calculatedFrames = new AtomicInteger();
            Object[] ui = profiledUi(calculatedFrames, completed);
            var surface = (FractalSurface) ui[0];
            var controller = (FractalRenderController) ui[1];
            try {
                if (presentationChange) {
                    renderAndCapture(controller, surface, completed,
                            mandelbrot.withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY)));
                }
                var recolored = new CompletableFuture<Void>();
                var done = new CompletableFuture<Void>();
                var recoloredBeforeCompletion = new AtomicReference<Boolean>();
                fx(() -> {
                    completed.set(done);
                    // The listener first receives the current status, the earlier render's COMPLETE.
                    var started = new java.util.concurrent.atomic.AtomicBoolean();
                    controller.setOnRenderStatusChanged(status -> {
                        if (status.state() == RenderStatus.State.RENDERING) started.set(true);
                        if (status.state() != RenderStatus.State.COMPLETE || !started.get()) return;
                        // Both run on the FX thread: the recolor callback follows its publication.
                        recoloredBeforeCompletion.compareAndSet(null, recolored.isDone());
                        completed.get().complete(null);
                    });
                    controller.setOnRenderProgressChanged(progress -> {
                        if (progress >= 0.5 && !recolored.isDone()) {
                            controller.setOnRenderProgressChanged(ignored -> {});
                            controller.recolor(fire, () -> recolored.complete(null));
                        }
                    });
                    controller.render(mandelbrot, new RenderTarget(WIDTH, HEIGHT, RenderPriority.center()),
                            mandelbrot.fractal().defaultViewport());
                    return null;
                });
                done.get(15, TimeUnit.SECONDS);
                recolored.get(15, TimeUnit.SECONDS);
                fx(() -> null);
                boolean mixed = recoloredBeforeCompletion.get();

                renderAndCapture(controller, surface, completed, julia);
                int[] returned = renderAndCapture(controller, surface, completed, mandelbrot);
                assertEquals(2, calculatedFrames.get(), "Neither the mode change nor the return recalculates base samples");
                if (mixed) {
                    assertTrue(aaProfile(controller).testedPixels() > 0,
                            "Display colors from a pass with a recolor must not be restored as finished AA");
                    mixedExercised = true;
                } else {
                    assertEquals(0, aaProfile(controller).testedPixels(),
                            "AA finished before the recolor is still a single-palette result");
                }
                assertArrayEquals(expected, returned, "The returned frame must match one that never saw the recolor");
            } finally {
                fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
            }
        }
        assertTrue(mixedExercised, "No attempt published the recolor before the AA pass completed");
    }

    /**
     * At realistic window sizes AA samples outgrow the frame cache budget; a budget that
     * holds both frames and their AA colors, but no samples, reproduces that at test size.
     */
    @Test
    void antialiasingColorsSurviveWithoutSamplesAndAPaletteChangeSamplesThemAgain() throws Exception {
        var calculatedFrames = new AtomicInteger();
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        var budget = tightBudget();
        Object[] ui = profiledUi(calculatedFrames, completed, budget);
        var surface = (FractalSurface) ui[0];
        var controller = (FractalRenderController) ui[1];
        var fast = new AntialiasSettings(InteractiveRenderMode.FAST);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(fast);
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE).withAntialiasing(fast);
        var fire = new ColoringSettings(PalettePreset.FIRE);
        try {
            int[] first = renderAndCapture(controller, surface, completed, mandelbrot);
            assertTrue(aaProfile(controller).samples() > 0);
            var mandelbrotFrame = fx(() -> surface.completedRender().frame());
            renderAndCapture(controller, surface, completed, julia);
            // The second fractal's frame takes the room of the first fractal's samples.
            var retained = fx(() -> budget.refinementFor(mandelbrotFrame).orElseThrow());
            assertNull(retained.samples(), "The fixture budget must leave no room for both fractals' samples");
            assertNotNull(retained.pixels(), "AA colors must be kept when their samples are not");

            int[] returned = renderAndCapture(controller, surface, completed, mandelbrot);
            assertEquals(2, calculatedFrames.get());
            assertEquals(0, aaProfile(controller).testedPixels(), "Retained colors alone show finished AA");
            assertArrayEquals(first, returned);

            // Without samples the recolor shows base colors under AA, so the AA is sampled again.
            var passes = new AtomicInteger();
            var resampled = new CompletableFuture<Void>();
            fx(() -> {
                controller.setOnRenderStatusChanged(status -> {
                    if (status.state() == RenderStatus.State.RENDERING) passes.incrementAndGet();
                    if (status.state() == RenderStatus.State.COMPLETE && passes.get() > 0) resampled.complete(null);
                });
                controller.recolor(fire);
                return null;
            });
            resampled.get(15, TimeUnit.SECONDS);
            assertTrue(aaProfile(controller).samples() > 0, "A palette change must sample unsampled AA again");
            int[] recolored = fx(() -> pixels(surface));
            assertEquals(2, calculatedFrames.get());

            Object[] reference = profiledUi(new AtomicInteger(), completed);
            try {
                int[] expected = renderAndCapture((FractalRenderController) reference[1], (FractalSurface) reference[0],
                        completed, mandelbrot.withColoring(fire));
                assertArrayEquals(expected, recolored, "The resampled AA must match a render in the new palette");
            } finally {
                fx(() -> { ((FractalRenderController) reference[1]).close(); ((Stage) reference[2]).close(); return null; });
            }

            // Once sampled, further palette changes recolor the AA without another pass.
            int[] back = recolor(controller, surface, mandelbrot.coloring());
            fx(() -> null);
            assertEquals(1, passes.get(), "Recoloring sampled AA needs no further pass");
            // Recoloring decodes 16-bit sample phases, so channels may differ by one from direct AA colors.
            assertNearlyEqual(first, back, "Recoloring after resampling must keep the AA");
        } finally {
            fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    /**
     * Built-in palettes share one candidate set, so switching among them needs no pass. A
     * steeper edited palette samples its missing candidates once, in place; afterwards
     * every switch repeats a direct render in that palette without another pass.
     */
    @Test
    void steepEditedPaletteSamplesItsMissingAntialiasingOnceAndPaletteSwitchesRepeat() throws Exception {
        var fast = new AntialiasSettings(InteractiveRenderMode.FAST);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(fast);
        var fire = new ColoringSettings(PalettePreset.FIRE);
        var steep = new ColoringSettings(PalettePreset.ICE, java.util.List.of(
                new com.shangin.fractal.coloring.ColorStop(0.0, 0xFF001020),
                new com.shangin.fractal.coloring.ColorStop(0.5, 0xFF000000),
                new com.shangin.fractal.coloring.ColorStop(0.52, 0xFFFFFFFF),
                new com.shangin.fractal.coloring.ColorStop(1.0, 0xFFFFFFFF)),
                ColoringSettings.DEFAULT_COLOR_SCALE, 0.0, false, com.shangin.fractal.coloring.OrbitTrap.NONE);
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        long[] steepSamples = new long[1];
        int[] directFire = directRender(completed, mandelbrot.withColoring(fire), new long[1]);
        int[] directSteep = directRender(completed, mandelbrot.withColoring(steep), steepSamples);

        Object[] ui = profiledUi(new AtomicInteger(), completed);
        var surface = (FractalSurface) ui[0];
        var controller = (FractalRenderController) ui[1];
        try {
            int[] ice = renderAndCapture(controller, surface, completed, mandelbrot);
            long iceSamples = aaProfile(controller).samples();
            assertTrue(iceSamples > 0, "The fixture must need antialiasing samples");
            var passes = new AtomicInteger();
            var resampled = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
            fx(() -> {
                controller.setOnRenderStatusChanged(status -> {
                    if (status.state() == RenderStatus.State.RENDERING) passes.incrementAndGet();
                    if (status.state() == RenderStatus.State.COMPLETE && passes.get() > 0) resampled.get().complete(null);
                });
                return null;
            });

            assertNearlyEqual(directFire, recolor(controller, surface, fire), "Ice -> Fire must match a Fire render");
            fx(() -> null);
            assertEquals(0, passes.get(), "Built-in palettes share their antialiasing candidates");

            recolor(controller, surface, steep);
            resampled.get().get(15, TimeUnit.SECONDS);
            assertEquals(1, passes.get(), "The steeper palette samples its missing candidates once");
            assertTrue(steepSamples[0] > iceSamples, "Fixture: steep " + steepSamples[0] + ", Ice " + iceSamples);
            assertEquals(steepSamples[0] - iceSamples, aaProfile(controller).samples(),
                    "Only the missing candidates may be sampled");
            int[] firstSteep = fx(() -> pixels(surface));
            assertNearlyEqual(directSteep, firstSteep, "The steep palette must match its own render");

            for (int round = 0; round < 2; round++) {
                assertArrayEquals(ice, recolor(controller, surface, mandelbrot.coloring()), "-> Ice, round " + round);
                assertNearlyEqual(directFire, recolor(controller, surface, fire), "-> Fire, round " + round);
                assertArrayEquals(firstSteep, recolor(controller, surface, steep), "-> steep, round " + round);
                fx(() -> null);
            }
            assertEquals(1, passes.get(), "Once sampled, palette switches need no further pass");
        } finally {
            fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    /**
     * A frame whose AA candidates exceed the sample cache budget still shows its full AA:
     * the visit's pass displays every candidate without a second pass, and each palette
     * change samples the candidates the cache rejected, so switches repeat direct renders.
     */
    @Test
    void candidatesBeyondTheSampleCacheBudgetAreSampledAgainOnEachPaletteChange() throws Exception {
        var fast = new AntialiasSettings(InteractiveRenderMode.FAST);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(fast);
        var fire = new ColoringSettings(PalettePreset.FIRE);
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        long[] iceSamples = new long[1];
        int[] directIce = directRender(completed, mandelbrot, iceSamples);
        int[] directFire = directRender(completed, mandelbrot.withColoring(fire), new long[1]);
        // A direct pass takes 16 samples per candidate; hold about half of them.
        int candidates = (int) (iceSamples[0] / 16);
        var probe = new AntialiasSampleCache();
        var sixteen = new com.shangin.fractal.formula.FractalSample[16];
        java.util.Arrays.fill(sixteen, new com.shangin.fractal.formula.FractalSample(10, true, 10, 0));
        var smooth = new com.shangin.fractal.coloring.SmoothPaletteColoring(PalettePreset.ICE.palette());
        for (int pixel = 0; pixel < candidates / 2; pixel++) probe.put(pixel, sixteen, smooth);
        var sampleCache = new AntialiasSampleCache(probe.usedBytes());

        Object[] ui = profiledUi(new AtomicInteger(), completed, new RenderFrameCache(), sampleCache);
        var surface = (FractalSurface) ui[0];
        var controller = (FractalRenderController) ui[1];
        try {
            var passes = new AtomicInteger();
            var resampled = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
            int[] ice = renderAndCapture(controller, surface, completed, mandelbrot);
            assertArrayEquals(directIce, ice, "The visit's pass shows every candidate's AA");
            assertTrue(sampleCache.dropped(), "The fixture must overflow the sample cache");
            var busyField = FractalRenderController.class.getDeclaredField("visibleBusy");
            busyField.setAccessible(true);
            assertFalse(fx(() -> busyField.getBoolean(controller)), "The visit must not start a second AA pass");
            int cached = sampleCache.size();
            fx(() -> {
                controller.setOnRenderStatusChanged(status -> {
                    if (status.state() == RenderStatus.State.RENDERING) passes.incrementAndGet();
                    if (status.state() == RenderStatus.State.COMPLETE && passes.get() > 0) {
                        resampled.get().complete(null);
                    }
                });
                return null;
            });

            for (int round = 0; round < 2; round++) {
                resampled.set(new CompletableFuture<>());
                recolor(controller, surface, fire);
                resampled.get().get(15, TimeUnit.SECONDS);
                assertEquals((long) (candidates - cached) * 16, aaProfile(controller).samples(),
                        "Only the rejected candidates may be sampled again");
                assertNearlyEqual(directFire, fx(() -> pixels(surface)), "-> Fire, round " + round);
                resampled.set(new CompletableFuture<>());
                recolor(controller, surface, mandelbrot.coloring());
                resampled.get().get(15, TimeUnit.SECONDS);
                assertArrayEquals(ice, fx(() -> pixels(surface)), "-> Ice, round " + round);
            }
            fx(() -> null);
            assertEquals(4, passes.get(), "Each palette change runs exactly one in-place pass");
        } finally {
            fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    /** A pan from a revisited frame showing AA colors without samples tests only the exposed strip. */
    @Test
    void panningARevisitedFrameWithColorsOnlyRunsOnlyItsOwnAntialiasingPass() throws Exception {
        var calculatedFrames = new AtomicInteger();
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        var budget = tightBudget();
        Object[] ui = profiledUi(calculatedFrames, completed, budget);
        var surface = (FractalSurface) ui[0];
        var controller = (FractalRenderController) ui[1];
        var fast = new AntialiasSettings(InteractiveRenderMode.FAST);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(fast);
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE).withAntialiasing(fast);
        try {
            var mandelbrotFrame = renderFrame(controller, surface, completed, mandelbrot);
            renderAndCapture(controller, surface, completed, julia);
            assertNull(budget.refinementFor(mandelbrotFrame).orElseThrow().samples(),
                    "The fixture budget must keep the AA colors without their samples");
            renderAndCapture(controller, surface, completed, mandelbrot);
            assertEquals(0, aaProfile(controller).testedPixels(), "Retained colors alone show finished AA");

            var passes = new AtomicInteger();
            var settled = new CompletableFuture<Void>();
            var panned = mandelbrot.withViewport(mandelbrot.viewport().shiftedByPixels(8, 0, WIDTH, HEIGHT));
            fx(() -> {
                controller.setOnRenderStatusChanged(status -> {
                    if (status.state() == RenderStatus.State.RENDERING) passes.incrementAndGet();
                    if (status.state() == RenderStatus.State.COMPLETE && passes.get() > 0) settled.complete(null);
                });
                controller.render(panned, new RenderTarget(WIDTH, HEIGHT, RenderPriority.center()),
                        panned.fractal().defaultViewport());
                return null;
            });
            settled.get(15, TimeUnit.SECONDS);
            var panPass = aaProfile(controller);
            // A follow-up pass would start in the same FX callback that completed the pan.
            var busyField = FractalRenderController.class.getDeclaredField("visibleBusy");
            busyField.setAccessible(true);
            assertFalse(fx(() -> busyField.getBoolean(controller)), "The pan must not start a second AA pass");
            assertEquals(1, passes.get(), "The pan must not start a second antialiasing pass");
            assertTrue(panPass.testedPixels() > 0 && panPass.testedPixels() < WIDTH * HEIGHT / 4,
                    "Only the exposed strip is tested: " + panPass.testedPixels());
        } finally {
            fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
        }
    }

    /** Renders {@code scene} on a fresh controller and reports its AA sample count. */
    private static int[] directRender(AtomicReference<CompletableFuture<Void>> completed, FractalScene scene,
                                      long[] samples) throws Exception {
        Object[] reference = profiledUi(new AtomicInteger(), completed);
        try {
            int[] pixels = renderAndCapture((FractalRenderController) reference[1], (FractalSurface) reference[0],
                    completed, scene);
            samples[0] = aaProfile((FractalRenderController) reference[1]).samples();
            return pixels;
        } finally {
            fx(() -> { ((FractalRenderController) reference[1]).close(); ((Stage) reference[2]).close(); return null; });
        }
    }

    /** A palette change while the return visit still renders waits for that pass, then samples. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = InteractiveRenderMode.class, names = {"REFINED", "FAST"})
    void aPaletteChangeDuringTheReturnVisitResamplesAfterItWithoutStrandingIt(InteractiveRenderMode mode)
            throws Exception {
        var antialiasing = new AntialiasSettings(mode);
        var mandelbrot = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE).withAntialiasing(antialiasing);
        var julia = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE).withAntialiasing(antialiasing);
        var fire = new ColoringSettings(PalettePreset.FIRE);
        var completed = new AtomicReference<CompletableFuture<Void>>(new CompletableFuture<>());
        int[] expected;
        Object[] reference = profiledUi(new AtomicInteger(), completed);
        try {
            expected = renderAndCapture((FractalRenderController) reference[1], (FractalSurface) reference[0],
                    completed, mandelbrot.withColoring(fire));
        } finally {
            fx(() -> { ((FractalRenderController) reference[1]).close(); ((Stage) reference[2]).close(); return null; });
        }

        boolean deferralExercised = false;
        for (int attempt = 0; attempt < 10 && !deferralExercised; attempt++) {
            var calculatedFrames = new AtomicInteger();
            var budget = tightBudget();
            Object[] ui = profiledUi(calculatedFrames, completed, budget);
            var surface = (FractalSurface) ui[0];
            var controller = (FractalRenderController) ui[1];
            try {
                var mandelbrotFrame = renderFrame(controller, surface, completed, mandelbrot);
                renderAndCapture(controller, surface, completed, julia);
                assertNull(budget.refinementFor(mandelbrotFrame).orElseThrow().samples());

                var deferredField = FractalRenderController.class.getDeclaredField("deferredRecoloring");
                deferredField.setAccessible(true);
                var completions = new AtomicInteger();
                var deferredAtVisitEnd = new AtomicReference<Boolean>();
                var settled = new CompletableFuture<Void>();
                fx(() -> {
                    var started = new java.util.concurrent.atomic.AtomicBoolean();
                    controller.setOnRenderStatusChanged(status -> {
                        if (status.state() == RenderStatus.State.RENDERING) started.set(true);
                        if (status.state() != RenderStatus.State.COMPLETE || !started.get()) return;
                        try {
                            deferredAtVisitEnd.compareAndSet(null, deferredField.get(controller) != null);
                        } catch (IllegalAccessException e) { throw new AssertionError(e); }
                        if (completions.incrementAndGet() == 2) settled.complete(null);
                    });
                    controller.render(mandelbrot, new RenderTarget(WIDTH, HEIGHT, RenderPriority.center()),
                            mandelbrot.fractal().defaultViewport());
                    controller.recolor(fire);
                    return null;
                });
                settled.get(15, TimeUnit.SECONDS);
                fx(() -> null);
                deferralExercised = deferredAtVisitEnd.get();
                fx(() -> {
                    var shown = surface.completedRender();
                    assertEquals(FractalPreset.MANDELBROT, shown.scene().fractal(), "No other fractal's frame may stay");
                    assertEquals(fire, shown.scene().coloring());
                    assertTrue(shown.frame().isComplete());
                    return null;
                });
                assertEquals(2, calculatedFrames.get());
                assertNearlyEqual(expected, fx(() -> pixels(surface)),
                        "The frame must show AA sampled under the new palette");
            } finally {
                fx(() -> { controller.close(); ((Stage) ui[2]).close(); return null; });
            }
        }
        assertTrue(deferralExercised, "No attempt published the palette change before the return visit finished");
    }

    /**
     * At realistic window sizes AA samples outgrow the frame cache budget; this budget
     * holds two test frames and their AA colors, but no samples.
     */
    private static RenderFrameCache tightBudget() {
        long pixels = (long) WIDTH * HEIGHT;
        long frameBytes = pixels * (Integer.BYTES + 2L * Double.BYTES + 2L);
        long colorBytes = 256 + pixels * (Integer.BYTES + 1L);
        return new RenderFrameCache(2 * (frameBytes + colorBytes) + 1024);
    }

    private static RenderFrame renderFrame(FractalRenderController controller, FractalSurface surface,
                                           AtomicReference<CompletableFuture<Void>> completed,
                                           FractalScene scene) throws Exception {
        renderAndCapture(controller, surface, completed, scene);
        return fx(() -> surface.completedRender().frame());
    }

    private static void assertNearlyEqual(int[] expected, int[] actual, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int index = 0; index < expected.length; index++) {
            for (int shift = 0; shift <= 24; shift += 8) {
                int difference = Math.abs(((expected[index] >>> shift) & 0xff) - ((actual[index] >>> shift) & 0xff));
                if (difference > 1) {
                    fail(message + " at pixel " + index + ": " + Integer.toHexString(expected[index])
                            + " vs " + Integer.toHexString(actual[index]));
                }
            }
        }
    }

    private static Object[] profiledUi(AtomicInteger calculatedFrames,
                                       AtomicReference<CompletableFuture<Void>> completed) throws Exception {
        return profiledUi(calculatedFrames, completed, new RenderFrameCache());
    }

    private static Object[] profiledUi(AtomicInteger calculatedFrames,
                                       AtomicReference<CompletableFuture<Void>> completed,
                                       RenderFrameCache frameCache) throws Exception {
        return profiledUi(calculatedFrames, completed, frameCache, new AntialiasSampleCache());
    }

    private static Object[] profiledUi(AtomicInteger calculatedFrames,
                                       AtomicReference<CompletableFuture<Void>> completed,
                                       RenderFrameCache frameCache, AntialiasSampleCache sampleCache)
            throws Exception {
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
                var controller = new FractalRenderController(surface,
                        new FractalRenderService(countingBackend(calculatedFrames)), frameCache, sampleCache);
                controller.setWidePreviewSettings(WidePreviewSettings.DISABLED);
                controller.setOnRenderStatusChanged(status -> {
                    if (status.state() == RenderStatus.State.COMPLETE) completed.get().complete(null);
                });
                return new Object[]{surface, controller, stage};
            });
        } finally {
            if (profiling == null) System.clearProperty("fractal.aa.profile");
            else System.setProperty("fractal.aa.profile", profiling);
        }
    }

    private static InteractiveAntialiasService.Profile aaProfile(FractalRenderController controller) throws Exception {
        var field = FractalRenderController.class.getDeclaredField("antialiasService");
        field.setAccessible(true);
        return ((InteractiveAntialiasService) field.get(controller)).lastProfile();
    }

    private static int[] recolor(FractalRenderController controller, FractalSurface surface,
                                 ColoringSettings coloring) throws Exception {
        var applied = new CompletableFuture<Void>();
        fx(() -> { controller.recolor(coloring, () -> applied.complete(null)); return null; });
        applied.get(15, TimeUnit.SECONDS);
        return fx(() -> pixels(surface));
    }

    private static int[] pixels(FractalSurface surface) {
        WritableImage image = surface.snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    @Test
    void fractalMenuSwitchBackToTheRememberedViewReusesItsBaseSamples() throws Exception {
        var calculatedFrames = new AtomicInteger();
        var scene = FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY));
        Object[] ui = fx(() -> {
            var view = new FractalView(scene, false, new FractalRenderService(countingBackend(calculatedFrames)));
            var stage = new Stage();
            stage.setScene(new Scene(view, 320, 240));
            stage.show();
            return new Object[]{view, stage};
        });
        var view = (FractalView) ui[0];
        try {
            awaitCompleted(view, FractalPreset.MANDELBROT);
            fx(() -> { view.zoom(true); return null; });
            awaitCompleted(view, FractalPreset.MANDELBROT);
            FractalScene zoomed = fx(view::sceneSnapshot);
            int[] zoomedPixels = fx(() -> displayedPixels(view));

            fx(() -> { view.setFractal(FractalPreset.BURNING_SHIP); return null; });
            awaitCompleted(view, FractalPreset.BURNING_SHIP);
            int beforeReturn = calculatedFrames.get();

            fx(() -> { view.setFractal(FractalPreset.MANDELBROT); return null; });
            awaitCompleted(view, FractalPreset.MANDELBROT);
            fx(() -> {
                assertEquals(zoomed, view.sceneSnapshot(), "Mandelbrot returns to its remembered view");
                assertEquals(zoomed, surface(view).completedRender().scene());
                assertArrayEquals(zoomedPixels, displayedPixels(view));
                return null;
            });
            assertEquals(beforeReturn, calculatedFrames.get(),
                    "The remembered view's completed base frame must be reused, not recalculated");

            // Reset View is explicit and still renders the home view, which is itself retained.
            fx(() -> { view.setFractal(FractalPreset.MANDELBROT); return null; });
            awaitCompleted(view, FractalPreset.MANDELBROT);
            assertNotEquals(zoomed.viewport(), fx(view::sceneSnapshot).viewport());
        } finally {
            fx(() -> { view.close(); ((Stage) ui[1]).close(); return null; });
        }
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

    private static FractalSurface surface(FractalView view) {
        return (FractalSurface) view.getChildrenUnmodifiable().getFirst();
    }

    private static int[] displayedPixels(FractalView view) {
        WritableImage image = surface(view).snapshot(null, null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    private static void awaitCompleted(FractalView view, FractalPreset fractal) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fx(() -> view.isLatencyIdle() && view.sceneSnapshot().fractal() == fractal
                && surface(view).completedRender() != null
                && view.sceneSnapshot().equals(surface(view).completedRender().scene()))) {
            if (System.nanoTime() > deadline) fail("Render of " + fractal + " did not complete");
            // Each probe is a full FX queue round trip, so the loop awaits concrete state.
        }
    }

    private static int[] renderAndCapture(FractalRenderController controller, FractalSurface surface,
                                          AtomicReference<CompletableFuture<Void>> completed,
                                          FractalScene scene) throws Exception {
        var done = new CompletableFuture<Void>();
        fx(() -> {
            completed.set(done);
            controller.cancelCurrent();
            controller.render(scene, new RenderTarget(WIDTH, HEIGHT, RenderPriority.center()),
                    scene.fractal().defaultViewport());
            return null;
        });
        done.get(15, TimeUnit.SECONDS);
        return fx(() -> {
            assertSame(scene, surface.completedRender().scene(), "The completed render must belong to this scene");
            WritableImage image = surface.snapshot(null, null);
            int width = (int) image.getWidth(), height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
            return pixels;
        });
    }
}
