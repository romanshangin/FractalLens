package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.ui.BaselineInputAccess;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.shangin.fractal.render.BaselineFxBenchmark.fx;
import static com.shangin.fractal.render.BaselineInputBenchmark.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the production view and controller through installed input handlers. Each
 * visible frame is compared pixel by pixel with an exact CPU control, including the
 * antialiasing retained from its source, while fresh frames take spare storage.
 */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class SpareFrameStorageFxTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 420;

    @BeforeAll static void startFx() throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
        started.get(15, TimeUnit.SECONDS);
    }

    @AfterAll static void stopFx() { Platform.exit(); }

    @ParameterizedTest @EnumSource(value = InteractiveRenderMode.class, names = {"FAST", "REFINED"})
    void navigationOverSpareStorageMatchesExactControls(InteractiveRenderMode mode) throws Exception {
        var origin = new Viewport("-0.75", "0.1", "1.2");
        var seed = step("seed", job(origin, WIDTH, HEIGHT));
        var fixture = new BaselineFixtures.Fixture("spare-storage", List.of(seed));
        List<RenderFrame> frames = new ArrayList<>();
        try (var ui = fx(Ui::new)) {
            ui.seed(seed, mode);
            frames.add(completedFrame(ui));
            long trial = 0;

            // Pan in every direction, by an even physical offset so the grid stays aligned.
            int[][] directions = {{40, 0}, {-40, 0}, {0, 30}, {0, -30}, {24, 18}, {-24, 18}, {24, -18}, {-24, -18}};
            for (int[] direction : directions) {
                Viewport current = completedFrame(ui).job().viewport();
                navigateOverSpare(ui, fixture, step("pan", job(
                        current.shiftedByPixels(direction[0], direction[1], WIDTH, HEIGHT), WIDTH, HEIGHT)),
                        mode, ++trial, frames);
            }

            Viewport beforeZoom = completedFrame(ui).job().viewport();
            navigateOverSpare(ui, fixture, step("zoom-out", job(beforeZoom.zoom(new BigDecimal("1.5")), WIDTH, HEIGHT)),
                    mode, ++trial, frames);
            navigateOverSpare(ui, fixture, step("zoom-in", job(beforeZoom, WIDTH, HEIGHT)), mode, ++trial, frames);

            // A replacing wheel input cancels the first zoom while its tiles are partial.
            awaitSpare(ui);
            int taken = fx(() -> spares(ui).sparesTaken());
            var replaced = measure(ui, fixture, seed, mode, "replacement", ++trial);
            verify(replaced);
            assertEquals(2, replaced.events.stream().filter(e -> e.stage().equals("render_start")).count());
            assertTrue(fx(() -> spares(ui).sparesTaken()) > taken, "The cancelled zoom must use spare storage");
            assertOwnsStorage(replaced.frame, frames);

            // An iteration-limit change makes every sample fresh at the same size.
            awaitSpare(ui);
            taken = fx(() -> spares(ui).sparesTaken());
            var iterationChange = applyScene(ui, mode, new IterationSettings(450, 0), ++trial);
            verify(iterationChange);
            assertEquals(450, iterationChange.frame.job().maxIterations());
            assertEquals(taken + 1, (int) fx(() -> spares(ui).sparesTaken()));
            assertOwnsStorage(iterationChange.frame, frames);

            // A resize allocates its own frame; the next pan at the new size takes a spare.
            int grownWidth = WIDTH + 80;
            int grownHeight = HEIGHT + 40;
            Viewport current = completedFrame(ui).job().viewport();
            var resize = measure(ui, fixture, step("resize", job(current, grownWidth, grownHeight)), mode,
                    "sequence", ++trial);
            verify(resize);
            assertEquals(grownWidth, resize.frame.job().width());
            assertOwnsStorage(resize.frame, frames);
            Viewport resized = resize.frame.job().viewport();
            navigateOverSpare(ui, fixture, step("pan-after-resize", job(
                    resized.shiftedByPixels(-30, 20, grownWidth, grownHeight), grownWidth, grownHeight)),
                    mode, ++trial, frames);
        }
    }

    /** Waits for the spare of the visible size, navigates and checks the frame took it. */
    private static void navigateOverSpare(Ui ui, BaselineFixtures.Fixture fixture, BaselineFixtures.Step step,
                                          InteractiveRenderMode mode, long trial, List<RenderFrame> frames)
            throws Exception {
        awaitSpare(ui);
        int taken = fx(() -> spares(ui).sparesTaken());
        var result = measure(ui, fixture, step, mode, "sequence", trial);
        verify(result);
        assertTrue(result.changed, step.name() + " must render a new frame");
        assertEquals(taken + 1, (int) fx(() -> spares(ui).sparesTaken()), step.name() + " must take the spare");
        assertOwnsStorage(result.frame, frames);
    }

    private static Result applyScene(Ui ui, InteractiveRenderMode mode, IterationSettings iterations, long trial)
            throws Exception {
        Result r = new Result();
        r.trial = trial;
        r.fixture = "spare-storage";
        r.gesture = "scene";
        r.mode = mode;
        r.source = completedFrame(ui);
        r.sourcePixels = fx(() -> BaselineInputAccess.surface(ui.view).refinedPixelSnapshot(r.source));
        fx(() -> {
            var shown = BaselineInputAccess.surface(ui.view).completedRender().scene();
            BaselineInputAccess.seed(ui.view, new FractalScene(shown.fractal(), shown.viewport(), iterations,
                    shown.coloring(), shown.antialiasing()));
            return null;
        });
        awaitIdle(ui);
        fx(() -> {
            r.frame = BaselineInputAccess.surface(ui.view).completedRender().frame();
            r.pixels = BaselineInputAccess.surface(ui.view).refinedPixelSnapshot(r.frame).colors();
            r.actual = actualStep(ui, step("scene", r.frame.job()), "scene");
            return null;
        });
        assertNotSame(r.source, r.frame);
        return r;
    }

    private static void assertOwnsStorage(RenderFrame frame, List<RenderFrame> earlier) {
        for (RenderFrame other : earlier) {
            assertFalse(frame.fractalData().sharesStorageWith(other.fractalData()),
                    "A visible frame shares sample storage with an earlier frame");
            assertNotSame(frame.validity(), other.validity());
        }
        earlier.add(frame);
    }

    private static void awaitSpare(Ui ui) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!fx(() -> spares(ui).hasSpareFor(completedFrame(ui).job()))) {
            if (System.nanoTime() > deadline) fail("No spare was prepared for the visible size");
            Thread.sleep(5);
        }
    }

    private static RenderFrame completedFrame(Ui ui) throws Exception {
        return BaselineInputAccess.surface(ui.view).completedRender().frame();
    }

    private static SpareFrameStorage spares(Ui ui) throws Exception {
        var controllerField = ui.view.getClass().getDeclaredField("renderController");
        controllerField.setAccessible(true);
        Object controller = controllerField.get(ui.view);
        var storageField = controller.getClass().getDeclaredField("spareFrameStorage");
        storageField.setAccessible(true);
        return (SpareFrameStorage) storageField.get(controller);
    }

    private static BaselineFixtures.Step step(String name, RenderJob job) {
        return new BaselineFixtures.Step(name, BaselineFixtures.Action.REUSE, job, "fixed",
                BaselineFixtures.Aa.EMPTY, BaselineFixtures.Colors.SMOOTH);
    }

    private static RenderJob job(Viewport viewport, int width, int height) {
        return new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                viewport, width, height, 300);
    }
}
