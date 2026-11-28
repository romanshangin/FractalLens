package com.shangin.fractal.ui;

import com.shangin.fractal.app.LastSessionStore;
import com.shangin.fractal.app.PreferencesStore;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.config.JuliaClickSpeed;
import com.shangin.fractal.controller.FractalRenderController;
import com.shangin.fractal.controller.RenderStatus;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.FakeJuliaPreviewRuntime;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.*;
import com.shangin.fractal.scene.*;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Render > Julia GPU: approximate GPU frames replace the CPU render for supported Julia scenes. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class JuliaGpuModeFxTest {
    private static final int WIDTH = 80;
    private static final int HEIGHT = 60;
    private static final FractalScene JULIA = FractalScene.create(FractalPreset.JULIA, PalettePreset.ICE)
            .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY));

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        assertTrue(started.await(15, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @Test
    void gpuModeShowsOnlyTheGpuFrameAndRecolorsItOnTheGpu() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            int expected = FakeJuliaPreviewRuntime.expectedColor(JULIA.coloring(), -0.8);
            assertSolid(expected, fixture.displayed());
            assertEquals(0, fixture.backend.calls.get(), "GPU mode must not start the CPU render");
            assertEquals(1, fixture.runtime.calls());
            assertNull(fx(() -> fixture.surface.completedRender()), "A GPU frame has no exact samples");
            assertTrue(fx(() -> fixture.surface.hasCompletedFrame()));
            assertTrue(fx(() -> fixture.controller.showsJuliaGpuFrame()));
            assertEquals(RenderStatus.State.COMPLETE, fixture.status.get().state());

            ColoringSettings fire = new ColoringSettings(PalettePreset.values()[1]);
            CountDownLatch applied = new CountDownLatch(1);
            fixture.controller(controller -> controller.recolor(fire, applied::countDown));
            assertTrue(applied.await(15, TimeUnit.SECONDS));
            assertSolid(FakeJuliaPreviewRuntime.expectedColor(fire, -0.8), fixture.displayed());
            assertEquals(2, fixture.runtime.calls());
            assertEquals(0, fixture.backend.calls.get());

            // Turning the mode off renders the exact CPU frame again.
            fixture.controller(controller -> controller.setJuliaGpu(false));
            fixture.render(JULIA.withColoring(fire));
            assertEquals(1, fixture.backend.calls.get());
            assertNotNull(fx(() -> fixture.surface.completedRender()));
            assertFalse(fx(() -> fixture.controller.showsJuliaGpuFrame()));
            assertNotEquals(FakeJuliaPreviewRuntime.expectedColor(fire, -0.8), fixture.displayed()[0]);
        }
    }

    @Test
    void unsupportedScenesAndOtherFractalsStayOnCpu() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            var histogram = JULIA.withColoring(new ColoringSettings(PalettePreset.ICE,
                    com.shangin.fractal.coloring.SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0, true));
            fixture.render(histogram);
            fixture.render(FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withAntialiasing(new AntialiasSettings(InteractiveRenderMode.BASE_ONLY)));
            // Neighboring pixels collapse to one FP32 coordinate here: the kernel refuses it.
            fixture.render(JULIA.withViewport(new Viewport("0.3", "0.2", "1e-9")));
            assertEquals(3, fixture.backend.calls.get());
            assertEquals(0, fixture.runtime.started());
            assertNotNull(fx(() -> fixture.surface.completedRender()));
        }
    }

    @Test
    void refusedKernelFallsBackToTheCpuFrameOnce() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.runtime.setEmptyResult(true);
            fixture.render(JULIA);
            assertEquals(1, fixture.runtime.calls());
            assertEquals(1, fixture.backend.calls.get());
            assertNotNull(fx(() -> fixture.surface.completedRender()));
            assertEquals(RenderStatus.State.COMPLETE, fixture.status.get().state());
            fixture.render(JULIA.withViewport(new Viewport("0.1", "0", "2.4")));
            assertEquals(1, fixture.runtime.calls(), "An unavailable kernel is not retried");
            assertEquals(2, fixture.backend.calls.get());
        }
    }

    @Test
    void kernelFailureFailsTheRender() throws Exception {
        try (var fixture = new Fixture()) {
            AtomicReference<Throwable> reported = new AtomicReference<>();
            fixture.controller(controller -> {
                controller.setJuliaGpu(true);
                controller.setOnRenderError(reported::set);
            });
            fixture.runtime.setFailure(new IllegalStateException("injected"));
            fixture.startRender(JULIA);
            // FAILED is published before the error handler runs in the same FX task.
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.FAILED
                    && reported.get() != null);
            assertEquals("injected", reported.get().getMessage());
            assertEquals(0, fixture.backend.calls.get());
        }
    }

    @Test
    void afterAFailedSceneRenderPaletteChangesNeitherResubmitNorReportAgain() throws Exception {
        try (var fixture = new Fixture()) {
            AtomicInteger errors = new AtomicInteger();
            fixture.controller(controller -> {
                controller.setJuliaGpu(true);
                controller.setOnRenderError(error -> errors.incrementAndGet());
                controller.setColorCycling(true, JULIA.coloring());
            });
            fixture.runtime.setFailure(new IllegalStateException("injected"));
            fixture.startRender(JULIA);
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.FAILED && errors.get() > 0);
            int started = fixture.runtime.started();
            // The failure persists: neither a palette change nor stopping the animation may
            // resubmit the failed scene and report the failure again.
            ColoringSettings fire = new ColoringSettings(PalettePreset.FIRE);
            fixture.controller(controller -> {
                controller.recolor(fire);
                controller.cancelRecolor();
                controller.setColorCycling(false, fire);
            });
            // A resubmission would reach the worker within milliseconds; wait for it to show.
            Thread.sleep(300);
            fx(() -> null);
            assertEquals(started, fixture.runtime.started(), "The failed scene was resubmitted to the GPU");
            assertEquals(1, errors.get());
            assertEquals(RenderStatus.State.FAILED, fixture.status.get().state());
            assertFalse(fx(() -> fixture.surface.hasCompletedFrame()), "A frame of the failed scene was shown");
            // The next render tries again.
            fixture.runtime.setFailure(null);
            fixture.render(JULIA.withColoring(fire));
            assertSolid(FakeJuliaPreviewRuntime.expectedColor(fire, -0.8), fixture.displayed());
        }
    }

    @Test
    void supersededGpuFrameIsNeverShown() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.runtime.setGated(true);
            fixture.startRender(JULIA);
            awaitCondition(() -> fixture.runtime.started() == 1);
            var replacement = JULIA.withJuliaParameters(new JuliaParameters(-0.123, 0.745));
            fixture.startRender(replacement);
            fixture.runtime.release();
            // The first frame finished after it was superseded; the second is still gated.
            awaitCondition(() -> fixture.runtime.started() == 2);
            fx(() -> null);
            fx(() -> null);
            int stale = FakeJuliaPreviewRuntime.expectedColor(JULIA.coloring(), -0.8);
            assertFalse(fx(() -> fixture.surface.hasCompletedFrame()) && fixture.displayed()[0] == stale,
                    "A superseded GPU frame was shown");
            assertEquals(RenderStatus.State.RENDERING, fixture.status.get().state());
            fixture.runtime.release();
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE);
            assertSolid(FakeJuliaPreviewRuntime.expectedColor(JULIA.coloring(), -0.123), fixture.displayed());
        }
    }

    @Test
    void slowGpuStillShowsAnimatedPaletteFrames() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            fixture.runtime.setGated(true);
            ColoringSettings ocean = new ColoringSettings(PalettePreset.OCEAN);
            ColoringSettings fire = new ColoringSettings(PalettePreset.FIRE);
            ColoringSettings plasma = new ColoringSettings(PalettePreset.PLASMA);
            CountDownLatch oceanShown = new CountDownLatch(1);
            CountDownLatch plasmaShown = new CountDownLatch(1);
            AtomicInteger fireShown = new AtomicInteger();
            // Palette ticks arrive faster than the GPU finishes a frame.
            fixture.controller(controller -> controller.recolor(ocean, oceanShown::countDown));
            awaitCondition(() -> fixture.runtime.started() == 2);
            fixture.controller(controller -> {
                controller.recolor(fire, fireShown::incrementAndGet);
                controller.recolor(plasma, plasmaShown::countDown);
            });
            fixture.runtime.release();
            assertTrue(oceanShown.await(15, TimeUnit.SECONDS), "The finished frame must be shown");
            assertSolid(FakeJuliaPreviewRuntime.expectedColor(ocean, -0.8), fixture.displayed());
            awaitCondition(() -> fixture.runtime.started() == 3);
            fixture.runtime.release();
            assertTrue(plasmaShown.await(15, TimeUnit.SECONDS));
            assertSolid(FakeJuliaPreviewRuntime.expectedColor(plasma, -0.8), fixture.displayed());
            assertEquals(3, fixture.runtime.calls(), "Only the latest queued palette is rendered");
            assertEquals(0, fireShown.get());
        }
    }

    @Test
    void stoppingAnimationDropsThePaletteFrameInFlight() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            int shown = FakeJuliaPreviewRuntime.expectedColor(JULIA.coloring(), -0.8);
            fixture.runtime.setGated(true);
            AtomicInteger applied = new AtomicInteger();
            fixture.controller(controller -> controller.recolor(new ColoringSettings(PalettePreset.OCEAN),
                    applied::incrementAndGet));
            awaitCondition(() -> fixture.runtime.started() == 2);
            fixture.controller(FractalRenderController::cancelRecolor);
            fixture.runtime.release();
            awaitCondition(() -> fixture.runtime.calls() == 2);
            fx(() -> null);
            fx(() -> null);
            assertSolid(shown, fixture.displayed());
            assertEquals(0, applied.get());
        }
    }

    @Test
    void cancelledRequestThatHasNotStartedNeverRuns() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.runtime.setGated(true);
            fixture.startRender(JULIA);
            awaitCondition(() -> fixture.runtime.started() == 1);
            // Queued behind the running frame, then cancelled by switching the mode off.
            fixture.startRender(JULIA.withJuliaParameters(new JuliaParameters(-0.123, 0.745)));
            fixture.controller(controller -> controller.setJuliaGpu(false));
            fixture.startRender(JULIA);
            fixture.runtime.release();
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE);
            awaitCondition(() -> fixture.runtime.calls() == 1);
            fx(() -> null);
            assertEquals(1, fixture.runtime.started(), "A cancelled queued request reached the GPU");
            assertNotNull(fx(() -> fixture.surface.completedRender()));
        }
    }

    @Test
    void closingNeverWaitsForTheGpuOnTheFxThread() throws Exception {
        var fixture = new Fixture();
        try {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.runtime.setGated(true);
            fixture.startRender(JULIA);
            awaitCondition(() -> fixture.runtime.started() == 1);
            long started = System.nanoTime();
            fixture.controller(FractalRenderController::close);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
                    "Closing waited for the running GPU frame on the FX thread");
            assertFalse(fixture.runtime.closed(), "The runtime closed under a running native call");
        } finally {
            fixture.runtime.release();
        }
        awaitCondition(fixture.runtime::closed);
        fixture.close();
    }

    @Test
    void animatingAndStoppingThePaletteOnAGpuFrameKeepsNavigationWorking() throws Exception {
        try (var window = new MainWindow()) {
            window.enableJuliaGpu();
            int framesBefore = window.runtime.calls();
            fx(() -> { window.view.setColorCycling(true); return null; });
            awaitCondition(() -> window.runtime.calls() >= framesBefore + 2);
            fx(() -> { window.view.setColorCycling(false); return null; });
            fx(() -> { window.view.setColorCycling(true); return null; });
            awaitCondition(() -> window.runtime.calls() >= framesBefore + 4);
            int beforeZoom = window.runtime.calls();
            fx(() -> { window.view.zoom(true); return null; });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.showsJuliaGpuFrame())
                    && window.runtime.calls() > beforeZoom);
            fx(() -> { window.view.setColorCycling(false); return null; });
            assertTrue(fx(window.view::showsJuliaGpuFrame));
        }
    }

    @Test
    void gpuRefusalTurnsTheModeOffInTheMenu() throws Exception {
        try (var window = new MainWindow()) {
            window.runtime.setEmptyResult(true);
            CheckMenuItem juliaGpu = window.juliaGpuItem();
            fx(() -> { juliaGpu.setSelected(true); juliaGpu.fire(); return null; });
            awaitCondition(() -> window.runtime.calls() >= 1
                    && fx(() -> window.view.hasExportableFrame() && window.view.isLatencyIdle()));
            assertFalse(fx(juliaGpu::isSelected), "The menu still shows the refused mode as active");
            assertTrue(fx(juliaGpu::isDisable), "The refused mode must not stay available");
            assertFalse(fx(window.view::juliaGpu));
            assertFalse(fx(() -> window.item("File", "Export PNG…").isDisable()));
        }
    }

    @Test
    void cpuFallbackAfterResizeKeepsTheGpuIterationLimit() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            FractalScene zoomed = JULIA.withViewport(new Viewport("0", "0", "1.2"));
            fixture.render(zoomed);
            int gpuLimit = fx(() -> fixture.lastIterations.get());
            Viewport widerDefault = new Viewport("0", "0", "1.2");
            int recalculated = zoomed.iterations().maxIterations(widerDefault.scaleExact(),
                    zoomed.viewport().scaleExact());
            assertNotEquals(recalculated, gpuLimit, "The fixture must change the recalculated limit");

            // A resize keeps the pixel scale and the limit; the GPU then refuses the kernel.
            fixture.runtime.setEmptyResult(true);
            fx(() -> {
                fixture.surface.resize(2 * WIDTH, 2 * HEIGHT);
                fixture.surface.resizeBuffer(2 * WIDTH, 2 * HEIGHT);
                return null;
            });
            fixture.startRender(zoomed, 2 * WIDTH, 2 * HEIGHT, widerDefault, true);
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE
                    && fx(() -> fixture.surface.completedRender()) != null);
            assertEquals(gpuLimit, (int) fx(() -> fixture.surface.completedRender().frame().request().maxIterations()),
                    "The CPU fallback recalculated the iteration limit");
            assertEquals(1, fixture.backend.calls.get());
        }
    }

    @Test
    void panningOnTheGpuAfterAFailedPaletteFrameKeepsThePreservedLimit() throws Exception {
        try (var fixture = new Fixture()) {
            int preserved = resizeThenFailAPaletteFrame(fixture);
            // A pan keeps the pixel scale and the limit; the GPU works again.
            fixture.runtime.setFailure(null);
            fixture.startRender(ZOOMED.withViewport(ZOOMED.viewport().shiftedByPixels(5, 0, 2 * WIDTH, 2 * HEIGHT)),
                    2 * WIDTH, 2 * HEIGHT, WIDER_DEFAULT, true);
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE);
            assertEquals(preserved, fixture.runtime.lastMaxIterations(), "The pan recalculated the limit");
        }
    }

    @Test
    void turningJuliaGpuOffAfterAFailedPaletteFrameKeepsThePreservedLimit() throws Exception {
        try (var fixture = new Fixture()) {
            int preserved = resizeThenFailAPaletteFrame(fixture);
            fixture.controller(controller -> controller.setJuliaGpu(false));
            fixture.startRender(ZOOMED, 2 * WIDTH, 2 * HEIGHT, WIDER_DEFAULT, true);
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE
                    && fx(() -> fixture.surface.completedRender()) != null);
            assertEquals(preserved, (int) fx(() -> fixture.surface.completedRender().frame().request().maxIterations()),
                    "The CPU frame recalculated the limit");
        }
    }

    private static final FractalScene ZOOMED = JULIA.withViewport(new Viewport("0", "0", "1.2"));
    private static final Viewport WIDER_DEFAULT = new Viewport("0", "0", "1.2");

    /** GPU frame at a preserved limit after a resize, then a failed palette frame; returns that limit. */
    private static int resizeThenFailAPaletteFrame(Fixture fixture) throws Exception {
        fixture.controller(controller -> controller.setJuliaGpu(true));
        fixture.render(ZOOMED);
        int preserved = fixture.runtime.lastMaxIterations();
        int recalculated = ZOOMED.iterations().maxIterations(WIDER_DEFAULT.scaleExact(), ZOOMED.viewport().scaleExact());
        assertNotEquals(recalculated, preserved, "The fixture must change the recalculated limit");
        fx(() -> {
            fixture.surface.resize(2 * WIDTH, 2 * HEIGHT);
            fixture.surface.resizeBuffer(2 * WIDTH, 2 * HEIGHT);
            return null;
        });
        fixture.startRender(ZOOMED, 2 * WIDTH, 2 * HEIGHT, WIDER_DEFAULT, true);
        awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE);
        assertEquals(preserved, fixture.runtime.lastMaxIterations());
        fixture.runtime.setFailure(new IllegalStateException("injected"));
        fixture.controller(controller -> controller.recolor(new ColoringSettings(PalettePreset.OCEAN)));
        awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.FAILED);
        fx(() -> null);
        return preserved;
    }

    @Test
    void refusalOfASupersededRequestStillTurnsTheModeOff() throws Exception {
        try (var fixture = new Fixture()) {
            AtomicReference<Boolean> changed = new AtomicReference<>();
            fixture.controller(controller -> {
                controller.setJuliaGpu(true);
                controller.setOnJuliaGpuChanged(changed::set);
            });
            fixture.runtime.setGated(true);
            fixture.startRender(JULIA);
            awaitCondition(() -> fixture.runtime.started() == 1);
            fixture.runtime.setEmptyResult(true);
            // A resize starts: the running request is superseded before it reports the refusal.
            fixture.controller(FractalRenderController::cancelCurrent);
            fixture.runtime.release();
            awaitCondition(() -> Boolean.FALSE.equals(changed.get()));
            assertFalse(fx(() -> fixture.controller.juliaGpu()));
            assertEquals(0, fixture.backend.calls.get(), "The superseded scene must not render again");
            fixture.render(JULIA);
            assertEquals(1, fixture.backend.calls.get());
            assertEquals(1, fixture.runtime.started());
        }
    }

    @Test
    void failedPaletteFrameFailsOnceAndStopsRetrying() throws Exception {
        try (var fixture = new Fixture()) {
            AtomicInteger errors = new AtomicInteger();
            fixture.controller(controller -> {
                controller.setJuliaGpu(true);
                controller.setOnRenderError(error -> errors.incrementAndGet());
            });
            fixture.render(JULIA);
            fixture.runtime.setFailure(new IllegalStateException("injected"));
            fixture.controller(controller -> controller.recolor(new ColoringSettings(PalettePreset.OCEAN)));
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.FAILED && errors.get() > 0);
            fx(() -> null);
            assertEquals(1, errors.get());
            int calls = fixture.runtime.calls();
            fixture.controller(controller -> {
                controller.recolor(new ColoringSettings(PalettePreset.FIRE));
                controller.recolor(new ColoringSettings(PalettePreset.PLASMA));
            });
            fx(() -> null);
            fx(() -> null);
            assertEquals(calls, fixture.runtime.started(), "A failed palette frame was retried");
            assertEquals(1, errors.get());
            assertEquals(RenderStatus.State.FAILED, fixture.status.get().state());
        }
    }

    @Test
    void paletteAnimationStopsWhenAGpuPaletteFrameFails() throws Exception {
        try (var window = new MainWindow()) {
            window.enableJuliaGpu();
            CheckMenuItem animation = (CheckMenuItem) fx(() -> window.item("Color", "Animate Palette"));
            int before = window.runtime.calls();
            fx(() -> { animation.setSelected(true); animation.fire(); return null; });
            awaitCondition(() -> window.runtime.calls() >= before + 2);
            window.runtime.setFailure(new IllegalStateException("injected"));
            awaitCondition(() -> !fx(animation::isSelected));
            int afterFailure = window.runtime.started();
            // Several animation ticks: none may reach the GPU again.
            Thread.sleep(300);
            fx(() -> null);
            assertEquals(afterFailure, window.runtime.started(), "Palette animation kept retrying the GPU");
        }
    }

    @Test
    void refusalAfterAStoppedAnimationRestoresTheShownSceneOnCpu() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            fixture.runtime.setGated(true);
            fixture.controller(controller -> controller.recolor(new ColoringSettings(PalettePreset.OCEAN)));
            awaitCondition(() -> fixture.runtime.started() == 2);
            fixture.runtime.setEmptyResult(true);
            // Animate Palette stops while the palette frame runs; no render follows.
            fixture.controller(FractalRenderController::cancelRecolor);
            fixture.runtime.release();
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE
                    && fx(() -> fixture.surface.completedRender()) != null);
            assertEquals(JULIA.coloring(), fx(() -> fixture.surface.completedRender().scene().coloring()),
                    "The CPU frame must show the palette that was on screen");
            assertFalse(fx(() -> fixture.controller.showsJuliaGpuFrame()));
            assertFalse(fx(() -> fixture.controller.juliaGpu()));
            assertEquals(1, fixture.backend.calls.get());
        }
    }

    @Test
    void refusedPaletteFrameRecordsItsPaletteAfterTheCpuFrame() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            fixture.runtime.setEmptyResult(true);
            ColoringSettings shifted = new ColoringSettings(PalettePreset.ICE,
                    com.shangin.fractal.coloring.SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.5);
            AtomicReference<ColoringSettings> shownWhenApplied = new AtomicReference<>();
            AtomicInteger applied = new AtomicInteger();
            fixture.controller(controller -> controller.recolor(shifted, () -> {
                applied.incrementAndGet();
                var completed = fixture.surface.completedRender();
                shownWhenApplied.set(completed == null ? null : completed.scene().coloring());
            }));
            awaitCondition(() -> applied.get() == 1);
            fx(() -> null);
            // Recorded once the CPU frame with that palette is on screen, not before.
            assertEquals(shifted, shownWhenApplied.get(), "The palette must be recorded with its CPU frame");
            awaitCondition(() -> fixture.status.get().state() == RenderStatus.State.COMPLETE);
            assertEquals(1, applied.get());
            assertEquals(1, fixture.backend.calls.get());
        }
    }

    @Test
    void refusedPaletteIsRecordedWhenTheBaseFrameShowsEvenIfAntialiasingIsCancelled() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            FractalScene antialiased = JULIA.withAntialiasing(new AntialiasSettings(InteractiveRenderMode.FAST));
            fixture.render(antialiased);
            fixture.runtime.setEmptyResult(true);
            ColoringSettings shifted = new ColoringSettings(PalettePreset.ICE,
                    com.shangin.fractal.coloring.SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.5);
            AtomicInteger applied = new AtomicInteger();
            AtomicReference<RenderStatus.State> stateAtCancel = new AtomicReference<>();
            CountDownLatch cancelled = new CountDownLatch(1);
            fixture.controller(controller -> {
                controller.recolor(shifted, applied::incrementAndGet);
                // Polls on the FX thread: it cancels in the first task after the base CPU frame is
                // published, ahead of any antialiasing result posted later.
                Runnable[] poll = new Runnable[1];
                poll[0] = () -> {
                    if (fixture.surface.completedRender() == null) {
                        Platform.runLater(poll[0]);
                        return;
                    }
                    stateAtCancel.set(fixture.status.get().state());
                    controller.cancelCurrent();
                    cancelled.countDown();
                };
                Platform.runLater(poll[0]);
            });
            assertTrue(cancelled.await(15, TimeUnit.SECONDS));
            fx(() -> null);
            assertEquals(RenderStatus.State.RENDERING, stateAtCancel.get(),
                    "The fixture must cancel before antialiasing completes");
            assertEquals(shifted, fx(() -> fixture.surface.completedRender().scene().coloring()));
            assertEquals(1, applied.get(), "The palette shown on the base CPU frame was not recorded");
        }
    }

    @Test
    void parameterMapUsesCompactBadgeAndAllowsTheMainWindowToClose() throws Exception {
        try (var window = new MainWindow()) {
            var map = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(window.view::isLatencyIdle));
            fx(() -> {
                assertEquals(javafx.stage.StageStyle.DECORATED, map.getStyle());
                assertEquals(javafx.stage.Modality.NONE, map.getModality());
                assertSame(window.stage, map.getOwner());
                var badge = (RenderModeIndicator) window.view.getChildrenUnmodifiable().get(1);
                badge.setDeepZoom(true);
                badge.setRenderStatus(new RenderStatus(RenderStatus.State.RENDERING, 0));
                assertEquals("Standard", badge.getText());
                assertEquals("Standard", badge.getAccessibleText());
                assertNull(badge.getTooltip());
                var icons = (javafx.scene.layout.StackPane) badge.getGraphic();
                assertTrue(icons.getChildren().get(0).isVisible());
                assertFalse(icons.getChildren().get(1).isVisible());
                map.close();
                assertTrue(badge.getText().startsWith("Deep Zoom · Rendering"));
                assertNotNull(badge.getTooltip());

                window.item("Render", JuliaExplorer.TITLE).fire();
                var reopened = javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
                window.stage.fireEvent(new javafx.stage.WindowEvent(window.stage,
                        javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                assertFalse(window.stage.isShowing());
                assertFalse(reopened.isShowing());
                return null;
            });
        }
    }

    @Test
    void mapClickSpeedPersistsAcrossApplicationWindows(@TempDir Path directory) throws Exception {
        var session = new LastSessionStore(directory.resolve("session.json"));
        session.save(JULIA);
        var preferences = PreferencesStore.besides(session);
        var expected = JuliaClickSpeed.DEFAULT;
        for (var scheme : ColorScheme.values()) {
            Stage stage = fx(Stage::new);
            MainView main = fx(() -> new MainView(stage, session,
                    new FractalRenderService(new CountingBackend(), new FakeJuliaPreviewRuntime())));
            try {
                var view = fx(() -> (FractalView) ((StackPane) main.getCenter()).getChildren().getFirst());
                fx(() -> {
                    stage.setScene(new Scene(main, 320, 240));
                    stage.show();
                    return null;
                });
                awaitCondition(() -> fx(() -> view.hasExportableFrame() && view.isLatencyIdle()));
                var openingSpeed = expected;
                var selectedSpeed = openingSpeed == JuliaClickSpeed.DEFAULT
                        ? JuliaClickSpeed.VERY_SLOW : JuliaClickSpeed.VERY_FAST;
                fx(() -> {
                    item((MainMenuBar) main.getTop(), "Render", JuliaExplorer.TITLE).fire();
                    var explorer = javafx.stage.Window.getWindows().stream()
                            .filter(JuliaExplorer.class::isInstance).map(JuliaExplorer.class::cast)
                            .findFirst().orElseThrow();
                    var scene = explorer.getScene();
                    scene.getPreferences().setColorScheme(scheme);
                    scene.getRoot().applyCss();
                    scene.getRoot().layout();
                    var speed = (ChoiceBox<?>) scene.lookup("#julia-click-speed");
                    assertTrue(speed.isVisible());
                    assertTrue(speed.getBoundsInParent().getWidth() > 0);
                    assertEquals(JuliaExplorer.HEIGHT, explorer.map().getHeight());
                    var real = (Label) scene.lookup("#julia-coordinate-real");
                    var imaginary = (Label) scene.lookup("#julia-coordinate-imaginary");
                    assertNotNull(real);
                    assertNotNull(imaginary);
                    assertEquals("-0.800", real.getText());
                    assertEquals("0.156", imaginary.getText());
                    assertCoordinateCaption(scene, "Re", real);
                    assertCoordinateCaption(scene, "Im", imaginary);
                    var mapBounds = explorer.map().localToScene(explorer.map().getLayoutBounds());
                    assertEquals(JuliaExplorer.WIDTH, mapBounds.getWidth());
                    assertTrue(mapBounds.getMinX() > 0 && mapBounds.getMaxX() < scene.getWidth());
                    assertTrue(real.localToScene(real.getBoundsInLocal()).getMaxY() < mapBounds.getMinY());
                    assertTrue(imaginary.localToScene(imaginary.getBoundsInLocal()).getMaxY() < mapBounds.getMinY());
                    assertTrue(speed.localToScene(speed.getBoundsInLocal()).getMinY() > mapBounds.getMaxY());
                    var panel = (VBox) scene.getRoot();
                    var panelColor = (Color) panel.getBackground().getFills().getFirst().getFill();
                    var numberColor = (Color) real.getTextFill();
                    assertEquals(scheme == ColorScheme.DARK, panelColor.getBrightness() < numberColor.getBrightness());
                    assertEquals("Click speed", speed.getAccessibleText());
                    assertEquals(java.util.List.of("Very slow", "Slow", "Default", "Fast", "Very fast"),
                            speed.getItems().stream().map(Object::toString).toList());
                    assertEquals(openingSpeed, speed.getValue());
                    speed.getSelectionModel().select(speed.getItems().indexOf(selectedSpeed));
                    assertEquals(JULIA, view.sceneSnapshot());
                    return null;
                });
                assertEquals(selectedSpeed, preferences.juliaClickSpeed());
                expected = selectedSpeed;
            } finally {
                fx(() -> {
                    main.close();
                    stage.close();
                    return null;
                });
            }
        }
    }

    private static void assertCoordinateCaption(Scene scene, String text, Label value) {
        var caption = scene.getRoot().lookupAll(".coordinate-caption").stream().map(Label.class::cast)
                .filter(label -> label.getText().equals(text)).findFirst().orElseThrow();
        assertSame(value, caption.getLabelFor());
    }

    @ParameterizedTest
    @CsvSource({"LIGHT, #ffcc00", "DARK, #ffd60a", "LIGHT, #001466", "DARK, #001466"})
    void parameterMapFocusAndPopupStayLegible(ColorScheme scheme, String accentHex) throws Exception {
        try (var window = new MainWindow()) {
            var explorer = openExplorer(window);
            awaitCondition(() -> fx(explorer::isFocused));
            fx(() -> {
                var accent = Color.web(accentHex);
                var scene = explorer.getScene();
                var panel = (VBox) scene.getRoot();
                var speed = (ChoiceBox<?>) scene.lookup("#julia-click-speed");
                scene.getPreferences().setColorScheme(scheme);
                panel.styleProperty().unbind();
                panel.setStyle(JuliaExplorer.accentStyle(accent));
                panel.applyCss();
                panel.layout();

                var panelColor = (Color) panel.getBackground().getFills().getFirst().getFill();
                var buttonColor = (Color) speed.getBackground().getFills().getFirst().getFill();
                var keyboardFocus = scene.getFocusOwner();
                keyboardFocus.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB,
                        false, false, false, false));
                assertSame(speed, scene.getFocusOwner(), "Tab must reach the speed control");
                panel.applyCss();
                var strokes = speed.getBorder().getStrokes();
                var outer = strokes.getFirst();
                assertTrue(outer.getInsets().getTop() < 0, "Focus must have an outer outline");
                assertTrue(contrast((Color) outer.getTopStroke(), panelColor) >= 3);
                assertTrue(contrast((Color) outer.getTopStroke(), buttonColor) >= 3);
                assertEquals(accent, strokes.getLast().getTopStroke());

                speed.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE,
                        false, false, false, false));
                assertTrue(speed.isShowing(), "Space must open the speed choices");
                try {
                    var popup = Window.getWindows().stream().filter(PopupWindow.class::isInstance)
                            .map(PopupWindow.class::cast).filter(w -> w.getOwnerWindow() == explorer)
                            .filter(w -> w.getScene().getRoot().lookup(".menu-item") != null)
                            .findFirst().orElseThrow();
                    var popupRoot = popup.getScene().getRoot();
                    popup.getScene().getFocusOwner().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN,
                            false, false, false, false));
                    popupRoot.applyCss();
                    var activeRow = (Region) popup.getScene().getFocusOwner();
                    assertTrue(activeRow.getStyleClass().contains("menu-item"), "Down must focus a menu item");
                    var text = (Label) activeRow.lookup(".label");
                    var highlight = (Color) activeRow.getBackground().getFills().getFirst().getFill();
                    assertEquals(accent, highlight);
                    assertTrue(contrast((Color) text.getTextFill(), highlight) >= 4.5,
                            () -> "The focused menu item must be legible against " + highlight);
                } finally {
                    speed.hide();
                }
                return null;
            });
        }
    }

    private static double contrast(Color first, Color second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    @Test
    void selectedClickSpeedChangesTheNextPublishedMarkerStep() throws Exception {
        double slow = firstClickMarkerStep(JuliaClickSpeed.VERY_SLOW);
        double fast = firstClickMarkerStep(JuliaClickSpeed.VERY_FAST);
        assertTrue(slow > 0, "The first published click frame must move the marker");
        assertEquals(slow * 16, fast, 1e-9);
    }

    private static double firstClickMarkerStep(JuliaClickSpeed speed) throws Exception {
        try (var window = new MainWindow()) {
            var explorer = openExplorer(window);
            awaitCondition(() -> fx(() -> explorer.hasThumbnail() && window.view.isLatencyIdle()
                    && window.view.showsJuliaGpuFrame()));
            var step = new java.util.concurrent.CompletableFuture<Double>();
            fx(() -> {
                assertEquals(JULIA, window.view.sceneSnapshot());
                var choice = (ChoiceBox<?>) explorer.getScene().lookup("#julia-click-speed");
                choice.getSelectionModel().select(choice.getItems().indexOf(speed));
                var map = explorer.map();
                var marker = (javafx.scene.shape.Circle) map.getChildrenUnmodifiable().get(1);
                double startX = marker.getCenterX();
                double targetX = startX + 60;
                double targetY = marker.getCenterY();
                marker.centerXProperty().addListener((observable, before, after) ->
                        step.complete(after.doubleValue() - startX));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_PRESSED, targetX, targetY, true));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_RELEASED, targetX, targetY, false));
                // Fix the first two pulse times in one FX turn. The render still publishes normally.
                long now = System.nanoTime();
                explorer.advanceFrame(now);
                explorer.advanceFrame(now + 16_666_667);
                return null;
            });
            return step.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void parameterMapThumbnailUsesTheScenePaletteAndFollowsPaletteChanges() throws Exception {
        try (var window = new MainWindow()) {
            var explorer = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            var imageView = fx(() -> (ImageView) explorer.map().getChildrenUnmodifiable().getFirst());
            Image initialImage = fx(imageView::getImage);
            assertNotNull(initialImage, "The map must show an image of its full size before the thumbnail is ready");
            assertEquals(JuliaExplorer.WIDTH, initialImage.getWidth());
            assertEquals(JuliaExplorer.HEIGHT, initialImage.getHeight());
            awaitCondition(() -> fx(explorer::hasThumbnail));
            fx(() -> {
                assertSame(initialImage, imageView.getImage(), "The thumbnail must be written into the shown image");
                assertEquals(PalettePreset.ICE.stops(), explorer.thumbnailColoring().paletteStops());
                return null;
            });
            assertThumbnailMatchesPreview(explorer);

            fx(() -> {
                window.view.setPalette(PalettePreset.FIRE);
                return null;
            });
            awaitCondition(() -> fx(() -> explorer.thumbnailColoring().paletteStops().equals(PalettePreset.FIRE.stops())));
            fx(() -> {
                assertTrue(explorer.isShowing());
                assertSame(initialImage, imageView.getImage());
                return null;
            });
            assertThumbnailMatchesPreview(explorer);
        }
    }

    @Test
    void applyingASceneWithTheLargestColorScaleKeepsRenderingWithTheMapOpen() throws Exception {
        try (var window = new MainWindow()) {
            var explorer = openExplorer(window);
            awaitCondition(() -> fx(explorer::hasThumbnail));
            var replacement = fx(() -> {
                var current = window.view.sceneSnapshot();
                var coloring = current.coloring();
                return current.withColoring(new ColoringSettings(coloring.palette(), coloring.paletteStops(),
                        3e307, coloring.offset(), false, OrbitTrap.NONE));
            });
            // Eight times the scale overflows a double; the map must not abort applying the scene.
            fx(() -> { window.view.applyScene(replacement); return null; });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.hasExportableFrame()
                    && window.view.sceneSnapshot().equals(replacement)));
            fx(() -> {
                if (explorer.isShowing()) assertEquals(Double.MAX_VALUE, explorer.thumbnailColoring().colorScale());
                return null;
            });
        }
    }

    @Test
    void aPointFarOutsideTheMapMeasuresTheBoundaryInMapPoints() throws Exception {
        try (var window = new MainWindow()) {
            fx(() -> { window.view.setJuliaParameters(new JuliaParameters(4, 4)); return null; });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.hasExportableFrame()));
            var explorer = openExplorer(window);
            awaitCondition(() -> fx(explorer::hasThumbnail));
            fx(() -> {
                var map = explorer.map();
                // The press scales the gesture to reach (4, 4); the boundary must still be in map points.
                map.fireEvent(pointer(map, MouseEvent.MOUSE_PRESSED, JuliaExplorer.WIDTH - 1, 0, true));
                assertEquals(JuliaFramePacing.BOUNDARY_RANGE, explorer.boundaryDistance(new JuliaParameters(4, 4)));
                assertEquals(JuliaFramePacing.BOUNDARY_RANGE,
                        explorer.boundaryDistance(JuliaExplorer.point(JuliaExplorer.WIDTH - 1, 0)));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_RELEASED, JuliaExplorer.WIDTH - 1, 0, false));
                return null;
            });
        }
    }

    private static JuliaExplorer openExplorer(MainWindow window) throws Exception {
        return fx(() -> {
            window.item("Render", JuliaExplorer.TITLE).fire();
            return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                    .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
        });
    }

    private static void assertThumbnailMatchesPreview(JuliaExplorer explorer) throws Exception {
        var coloring = fx(explorer::thumbnailColoring);
        int[] expected;
        try (var renderer = new com.shangin.fractal.render.ScenePreviewRenderer()) {
            expected = renderer.render(FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE)
                    .withIterations(new IterationSettings(80, 0)).withColoring(coloring),
                    JuliaExplorer.WIDTH, JuliaExplorer.HEIGHT, () -> false);
        }
        int[] actual = fx(() -> {
            int[] pixels = new int[JuliaExplorer.WIDTH * JuliaExplorer.HEIGHT];
            for (int y = 0; y < JuliaExplorer.HEIGHT; y++) {
                for (int x = 0; x < JuliaExplorer.WIDTH; x++) pixels[y * JuliaExplorer.WIDTH + x] = explorer.thumbnailArgb(x, y);
            }
            return pixels;
        });
        assertArrayEquals(expected, actual, "The thumbnail must use the scene palette");
    }

    @Test
    void parameterMapKeepsTheLastGpuFrameAndCommitsOneUndoableScene() throws Exception {
        try (var window = new MainWindow()) {
            window.runtime.showIsolatedBrightPixel();
            var initial = fx(window.view::sceneSnapshot);
            int cpuCalls = window.cpu.calls.get();
            var toggle = window.juliaGpuItem();
            assertFalse(fx(toggle::isSelected));
            var map = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                assertTrue(toggle.isSelected());
                assertTrue(window.view.juliaGpu());
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow().map();
            });
            awaitCondition(() -> fx(() -> window.view.showsJuliaGpuFrame() && window.view.isLatencyIdle()));
            var surface = fx(() -> (FractalSurface) window.view.getChildrenUnmodifiable().getFirst());
            var imageView = fx(() -> (ImageView) surface.getChildrenUnmodifiable().getFirst());
            fx(() -> {
                assertEquals(surface.renderWidth(), imageView.getImage().getWidth());
                assertEquals(surface.renderHeight(), imageView.getImage().getHeight());
                assertEquals(0xff303030, imageView.getImage().getPixelReader().getArgb(1, 1));
                return null;
            });
            var dragPositions = new java.util.ArrayList<Double>();
            var marker = fx(() -> (javafx.scene.shape.Circle) map.getChildrenUnmodifiable().get(1));
            fx(() -> {
                dragPositions.add(marker.getCenterX());
                marker.centerXProperty().addListener((obs, before, after) -> dragPositions.add(after.doubleValue()));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_PRESSED, 70, 60, true));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_DRAGGED, 220, 70, true));
                return null;
            });
            awaitCondition(() -> fx(() -> dragPositions.size() >= 4));
            fx(() -> {
                assertTrue(marker.getCenterX() < 220);
                assertMapCoordinates(map, JuliaExplorer.point(marker.getCenterX(), marker.getCenterY()));
                assertEquals(surface.renderWidth(), imageView.getImage().getWidth());
                assertEquals(surface.renderHeight(), imageView.getImage().getHeight());
                assertEquals(surface.getWidth(), imageView.getFitWidth());
                assertEquals(surface.getHeight(), imageView.getFitHeight());
                assertEquals(0xff303030, imageView.getImage().getPixelReader().getArgb(1, 1));
                assertEquals(0xff202020, imageView.getImage().getPixelReader().getArgb(2, 1));
                assertEquals(initial, window.view.sceneSnapshot());
                map.fireEvent(pointer(map, MouseEvent.MOUSE_DRAGGED, 150, 90, true));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_RELEASED, 90, 75, false));
                return null;
            });
            var dragged = initial.withJuliaParameters(JuliaExplorer.point(90, 75));
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()
                    && window.view.sceneSnapshot().equals(dragged)));
            // The boundary slowdown starts with the thumbnail; the click below checks it.
            var explorer = (JuliaExplorer) fx(() -> map.getScene().getWindow());
            awaitCondition(() -> fx(explorer::hasThumbnail));
            var positions = new java.util.ArrayList<Double>();
            var lastAnimatedImage = new AtomicReference<Image>();
            var lastAnimatedGpuCall = new AtomicInteger();
            fx(() -> {
                imageView.imageProperty().addListener((observable, before, after) -> {
                    if (window.view.sceneSnapshot().equals(dragged)) {
                        lastAnimatedImage.set(after);
                        lastAnimatedGpuCall.set(window.runtime.calls());
                    }
                });
                int expectedWidth = surface.renderWidth();
                int expectedHeight = surface.renderHeight();
                assertNull(surface.completedRender(), "The map should retain its GPU frame");
                assertTrue(window.view.showsJuliaGpuFrame());
                assertEquals(cpuCalls, window.cpu.calls.get());
                assertEquals(0xff303030, imageView.getImage().getPixelReader().getArgb(1, 1));
                assertEquals(expectedWidth, window.view.exportTarget().width());
                assertEquals(expectedHeight, window.view.exportTarget().height());
                assertEquals(expectedWidth, imageView.getImage().getWidth());
                assertEquals(expectedHeight, imageView.getImage().getHeight());
                assertEquals(surface.getWidth(), imageView.getFitWidth());
                assertEquals(surface.getHeight(), imageView.getFitHeight());
                for (int i = 1; i <= 3; i++) {
                    double previous = dragPositions.get(i - 1);
                    assertEquals(previous + 0.05 * (220 - previous), dragPositions.get(i), 1e-9);
                }
                assertEquals(90, marker.getCenterX(), 1e-9);
                assertMapCoordinates(map, dragged.juliaParameters());
                marker.centerXProperty().addListener((obs, before, after) -> positions.add(after.doubleValue()));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_PRESSED, 169, 110, true));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_DRAGGED, 170, 110, true));
                map.fireEvent(pointer(map, MouseEvent.MOUSE_RELEASED, 170, 110, false));
                return null;
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()
                    && window.view.sceneSnapshot().equals(initial.withJuliaParameters(JuliaExplorer.point(170, 110)))));
            // The path crosses the main cardioid's edge. Each marker position is one advance of
            // the pacing from the previous one, which never covers more than the boundary speed
            // over the longest counted frame, however long the real frame took.
            fx(() -> {
                double length = Math.hypot(80, 35) / 80; // Map points per point along x on this line.
                int nearBoundary = 0;
                double previous = 90;
                for (double x : positions) {
                    var start = JuliaExplorer.point(previous, 75 + (previous - 90) * 35 / 80);
                    double boundary = explorer.boundaryDistance(start);
                    if (boundary < 0.5) {
                        nearBoundary++;
                        double limit = Math.clamp(JuliaFramePacing.SPEED * boundary / JuliaFramePacing.BOUNDARY_RANGE,
                                JuliaFramePacing.BOUNDARY_SPEED, JuliaFramePacing.SPEED) * JuliaFramePacing.MAX_FRAME_SECONDS;
                        assertTrue((x - previous) * length <= limit + 1e-9, "A step " + boundary
                                + " points from the boundary moved " + (x - previous) * length + ", limit " + limit);
                    }
                    previous = x;
                }
                assertTrue(nearBoundary >= 10, "The click must pass close to the boundary, steps: " + nearBoundary);
                return null;
            });
            fx(() -> {
                assertEquals(cpuCalls, window.cpu.calls.get());
                assertTrue(window.view.showsJuliaGpuFrame());
                Image finalGpuFrame = imageView.getImage();
                assertNotNull(lastAnimatedImage.get());
                assertSame(lastAnimatedImage.get(), finalGpuFrame, "Committing the point must retain the published image");
                assertEquals(lastAnimatedGpuCall.get(), window.runtime.calls(),
                        "Committing the point must not dispatch another GPU frame");
                double previous = 90;
                for (double x : positions) {
                    assertTrue(x >= previous - 1e-9, "A click must move steadily toward its target");
                    previous = x;
                }
                assertEquals(170, previous, 1e-9);
                assertMapCoordinates(map, JuliaExplorer.point(170, 110));
                assertTrue(positions.size() >= 5, "A click must advance through visible intermediate positions");
                ((Stage) map.getScene().getWindow()).close();
                assertSame(finalGpuFrame, imageView.getImage());
                assertTrue(window.view.canExportPng());
                assertFalse(window.item("File", "Export PNG…").isDisable());
                window.view.undoScene();
                assertEquals(dragged, window.view.sceneSnapshot());
                window.view.undoScene();
                assertEquals(initial, window.view.sceneSnapshot());
                return null;
            });
        }
    }

    @Test
    void closingParameterMapImmediatelyAfterPublicationCommitsTheVisiblePoint() throws Exception {
        try (var window = new MainWindow()) {
            var initial = fx(window.view::sceneSnapshot);
            var map = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(window.view::isLatencyIdle));
            fx(() -> {
                window.view.setOnRenderingChanged(active -> {
                    // Close after GPU publication, before the animation timer moves the marker.
                    if (!active) map.hide();
                });
                var root = map.map();
                root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, 170, 110, true));
                root.fireEvent(pointer(root, MouseEvent.MOUSE_RELEASED, 170, 110, false));
                return null;
            });
            awaitCondition(() -> fx(() -> !map.isShowing()));
            fx(() -> {
                var committed = window.view.sceneSnapshot();
                assertNotEquals(initial.juliaParameters(), committed.juliaParameters());
                assertTrue(window.view.showsApproximateJuliaPoint(committed.juliaParameters()));
                assertTrue(window.view.canExportPng());
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsupportedColoringKeepsTheVisiblePointAndAddsOneUndoStep(boolean histogram) throws Exception {
        try (var window = new MainWindow()) {
            var initial = fx(window.view::sceneSnapshot);
            int cpuCalls = window.cpu.calls.get();
            var errors = new AtomicReference<Throwable>();
            var map = fx(() -> {
                window.view.setOnRenderError(errors::set);
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.showsJuliaGpuFrame()));
            fx(() -> {
                window.view.setOnRenderingChanged(active -> {
                    if (active) return;
                    window.view.setOnRenderingChanged(ignored -> {});
                    if (histogram) {
                        var item = (CheckMenuItem) window.item("Color", "Histogram Coloring");
                        item.setSelected(true);
                        item.fire();
                    } else {
                        window.view.setOrbitTrap(OrbitTrap.POINT);
                    }
                });
                var root = map.map();
                root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, 170, 110, true));
                root.fireEvent(pointer(root, MouseEvent.MOUSE_RELEASED, 170, 110, false));
                return null;
            });
            awaitCondition(() -> fx(() -> !map.isShowing() && window.view.isLatencyIdle()));
            fx(() -> {
                assertNull(errors.get());
                assertTrue(window.view.hasExportableFrame());
                assertFalse(window.view.showsJuliaGpuFrame());
                var surface = (FractalSurface) window.view.getChildrenUnmodifiable().getFirst();
                var scene = window.view.sceneSnapshot();
                assertEquals(histogram, scene.coloring().histogramColoring());
                assertEquals(histogram ? OrbitTrap.NONE : OrbitTrap.POINT, scene.coloring().orbitTrap());
                assertNotEquals(initial.juliaParameters(), scene.juliaParameters());
                assertEquals(scene, surface.completedRender().scene());
                return null;
            });
            assertTrue(window.cpu.calls.get() > cpuCalls);
            fx(() -> {
                var changed = window.view.sceneSnapshot();
                window.view.undoScene();
                assertEquals(initial, window.view.sceneSnapshot());
                window.view.redoScene();
                assertEquals(changed, window.view.sceneSnapshot());
                return null;
            });
        }
    }

    @Test
    void pressingAgainKeepsTheMarkerAndCoordinatesWithThePublishedFrame() throws Exception {
        try (var window = new MainWindow()) {
            var map = openExplorer(window).map();
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.showsJuliaGpuFrame()));
            JuliaParameters initial = JULIA.juliaParameters();
            var expected = JuliaExplorer.interpolate(initial, JuliaExplorer.point(220, 90), 0.05);
            int started = window.runtime.started();
            window.runtime.setGated(true);
            try {
                fx(() -> {
                    map.fireEvent(pointer(map, MouseEvent.MOUSE_PRESSED, 70, 60, true));
                    map.fireEvent(pointer(map, MouseEvent.MOUSE_DRAGGED, 220, 90, true));
                    return null;
                });
                awaitCondition(() -> window.runtime.started() > started);
                fx(() -> {
                    map.fireEvent(pointer(map, MouseEvent.MOUSE_PRESSED, 180, 100, true));
                    assertPublishedMapPoint(window, map, initial);
                    return null;
                });
            } finally {
                window.runtime.setGated(false);
                window.runtime.release();
            }
            awaitCondition(() -> fx(window.view::isLatencyIdle));
            fx(() -> {
                assertPublishedMapPoint(window, map, expected);
                assertEquals(JULIA, window.view.sceneSnapshot(), "The second press is still held");
                return null;
            });
        }
    }

    @Test
    void undoAndRedoKeepTheMarkerAndCoordinatesWithThePublishedFrame() throws Exception {
        try (var window = new MainWindow()) {
            var map = openExplorer(window).map();
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.showsJuliaGpuFrame()));
            var initial = fx(window.view::sceneSnapshot);
            var next = new JuliaParameters(-0.25, -0.5);
            fx(() -> {
                window.view.applyScene(initial.withJuliaParameters(next));
                return null;
            });
            awaitCondition(() -> fx(window.view::isLatencyIdle));
            assertMapHistoryRender(window, map, next, initial.juliaParameters(), window.view::undoScene);
            assertMapHistoryRender(window, map, initial.juliaParameters(), next, window.view::redoScene);
        }
    }

    private static void assertMapHistoryRender(MainWindow window, Pane map, JuliaParameters previous,
                                               JuliaParameters next, Runnable restore) throws Exception {
        int started = window.runtime.started();
        window.runtime.setGated(true);
        try {
            fx(() -> { restore.run(); return null; });
            awaitCondition(() -> window.runtime.started() > started);
            fx(() -> {
                assertEquals(next, window.view.sceneSnapshot().juliaParameters());
                assertPublishedMapPoint(window, map, previous);
                return null;
            });
        } finally {
            window.runtime.setGated(false);
            window.runtime.release();
        }
        awaitCondition(() -> fx(window.view::isLatencyIdle));
        fx(() -> { assertPublishedMapPoint(window, map, next); return null; });
    }

    private static void assertPublishedMapPoint(MainWindow window, Pane map, JuliaParameters expected) {
        assertTrue(window.view.showsApproximateJuliaPoint(expected), "The canvas must show the expected Julia point");
        var marker = (javafx.scene.shape.Circle) map.getChildrenUnmodifiable().get(1);
        assertTrue(marker.isVisible());
        var marked = JuliaExplorer.point(marker.getCenterX(), marker.getCenterY());
        assertEquals(expected.real(), marked.real(), 1e-12);
        assertEquals(expected.imaginary(), marked.imaginary(), 1e-12);
        assertMapCoordinates(map, expected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoringASceneWithTheSameConstantCancelsTheMapDrag(boolean histogram) throws Exception {
        try (var window = new MainWindow()) {
            var initial = fx(window.view::sceneSnapshot);
            var replacement = initial.withColoring(new ColoringSettings(
                    PalettePreset.FIRE, PalettePreset.FIRE.stops(), initial.coloring().colorScale(),
                    initial.coloring().offset(), histogram, initial.coloring().orbitTrap()));
            var map = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.showsJuliaGpuFrame()));
            var root = fx(() -> map.map());
            var marker = fx(() -> (javafx.scene.shape.Circle) root.getChildrenUnmodifiable().get(1));
            double initialX = fx(marker::getCenterX);
            fx(() -> {
                root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, initialX, marker.getCenterY(), true));
                root.fireEvent(pointer(root, MouseEvent.MOUSE_DRAGGED, 220, 90, true));
                return null;
            });
            awaitCondition(() -> fx(() -> marker.getCenterX() > initialX));
            fx(() -> {
                assertEquals(initial, window.view.sceneSnapshot(), "The drag must still be uncommitted");
                var published = JuliaExplorer.point(marker.getCenterX(), marker.getCenterY());
                window.view.applyScene(replacement);
                assertEquals(replacement, window.view.sceneSnapshot());
                assertEquals(!histogram, map.isShowing());
                if (!histogram) {
                    assertEquals(published, JuliaExplorer.point(marker.getCenterX(), marker.getCenterY()));
                    assertMapCoordinates(root, published);
                    root.fireEvent(pointer(root, MouseEvent.MOUSE_RELEASED, 220, 90, false));
                }
                return null;
            });
            awaitCondition(() -> fx(window.view::isLatencyIdle));
            fx(() -> {
                var surface = (FractalSurface) window.view.getChildrenUnmodifiable().getFirst();
                assertEquals(replacement, window.view.sceneSnapshot());
                if (histogram) {
                    assertEquals(replacement, surface.completedRender().scene());
                } else {
                    assertEquals(initialX, marker.getCenterX());
                    assertTrue(window.view.showsApproximateJuliaPoint(replacement.juliaParameters()));
                    assertEquals(FakeJuliaPreviewRuntime.expectedColor(
                            replacement.coloring(), replacement.juliaParameters().real()), displayed(surface)[0]);
                }
                return null;
            });
        }
    }

    @Test
    void gpuRefusalOfAMainWindowRenderFallsBackWhileTheMapIsOpen() throws Exception {
        try (var window = new MainWindow()) {
            int cpuCalls = window.cpu.calls.get();
            var errors = new AtomicReference<Throwable>();
            var map = fx(() -> {
                window.view.setOnRenderError(errors::set);
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.showsJuliaGpuFrame()));
            var iterations = new IterationSettings(123, 0);
            window.runtime.setEmptyResult(true);
            fx(() -> {
                window.view.setIterations(iterations);
                return null;
            });
            awaitCondition(() -> fx(() -> !map.isShowing() && window.view.isLatencyIdle()));
            fx(() -> {
                assertNull(errors.get());
                assertFalse(window.view.juliaGpu());
                var surface = (FractalSurface) window.view.getChildrenUnmodifiable().getFirst();
                assertEquals(iterations, surface.completedRender().scene().iterations());
                return null;
            });
            assertTrue(window.cpu.calls.get() > cpuCalls);
        }
    }

    @Test
    void parameterMapClosesOnGpuRefusalWithoutRenderingOnCpu() throws Exception {
        try (var window = new MainWindow()) {
            int cpuCalls = window.cpu.calls.get();
            var errors = new AtomicReference<Throwable>();
            var map = fx(() -> {
                window.view.setOnRenderError(errors::set);
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.showsJuliaGpuFrame()));
            var initial = fx(window.view::sceneSnapshot);
            window.runtime.setEmptyResult(true);
            fx(() -> {
                var root = map.map();
                root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, 170, 110, true));
                root.fireEvent(pointer(root, MouseEvent.MOUSE_RELEASED, 170, 110, false));
                return null;
            });
            awaitCondition(() -> fx(() -> !map.isShowing() && window.view.isLatencyIdle() && errors.get() != null));
            assertEquals(cpuCalls, window.cpu.calls.get());
            assertEquals(initial, fx(window.view::sceneSnapshot));
            assertTrue(fx(() -> window.item("Render", JuliaExplorer.TITLE).isDisable()));
        }
    }

    private static void assertMapCoordinates(Node map, JuliaParameters expected) {
        var scene = map.getScene();
        var real = (Label) scene.lookup("#julia-coordinate-real");
        var imaginary = (Label) scene.lookup("#julia-coordinate-imaginary");
        assertEquals(String.format(Locale.ROOT, "%.3f", expected.real()), real.getText());
        assertEquals(String.format(Locale.ROOT, "%.3f", expected.imaginary()), imaginary.getText());
    }

    private static MouseEvent pointer(Node map, javafx.event.EventType<MouseEvent> type,
                                      double x, double y, boolean down) {
        var scenePoint = map.localToScene(x, y);
        return new MouseEvent(type, scenePoint.getX(), scenePoint.getY(), x, y,
                MouseButton.PRIMARY, 1, false, false, false, false,
                down, false, false, false, false, true, null);
    }

    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    void mainViewRenderPreservesTheFilteredMapFrame(boolean resize, boolean editing) throws Exception {
        try (var window = new MainWindow()) {
            window.runtime.showIsolatedBrightPixel();
            var initial = fx(window.view::sceneSnapshot);
            var map = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()));
            var root = fx(() -> map.map());
            var marker = fx(() -> (javafx.scene.shape.Circle) root.getChildrenUnmodifiable().get(1));
            var surface = fx(() -> (FractalSurface) window.view.getChildrenUnmodifiable().getFirst());
            var imageView = fx(() -> (ImageView) surface.getChildrenUnmodifiable().getFirst());
            double initialX = fx(marker::getCenterX);
            double targetX = editing ? 220 : 143;
            var targetPoint = JuliaExplorer.point(targetX, 90);
            fx(() -> {
                root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, initialX, marker.getCenterY(), true));
                if (editing) root.fireEvent(pointer(root, MouseEvent.MOUSE_DRAGGED, targetX, 90, true));
                root.fireEvent(pointer(root, MouseEvent.MOUSE_RELEASED, targetX, 90, false));
                return null;
            });
            if (editing) {
                awaitCondition(() -> fx(() -> marker.getCenterX() > initialX));
            } else {
                awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()
                        && window.view.sceneSnapshot().juliaParameters().equals(targetPoint)));
            }
            var committedBeforeResize = fx(() -> window.view.sceneSnapshot().juliaParameters());
            var firstUpdatedPixel = new AtomicInteger();
            var firstUpdatedFrameUsesCommittedPoint = new AtomicReference<Boolean>();
            fx(() -> {
                int oldWidth = surface.renderWidth();
                imageView.imageProperty().addListener((obs, before, after) -> {
                    if (after != null && (!resize || after.getWidth() > oldWidth) && firstUpdatedFrameUsesCommittedPoint.get() == null) {
                        firstUpdatedPixel.set(after.getPixelReader().getArgb(1, 1));
                        firstUpdatedFrameUsesCommittedPoint.set(window.view.showsApproximateJuliaPoint(committedBeforeResize));
                    }
                });
                if (resize) {
                    window.stage.setWidth(window.stage.getWidth() * 1.5);
                } else {
                    window.view.setIterations(new IterationSettings(123, 0));
                }
                return null;
            });
            awaitCondition(() -> firstUpdatedFrameUsesCommittedPoint.get() != null);
            fx(() -> {
                assertEquals(!editing, firstUpdatedFrameUsesCommittedPoint.get());
                assertEquals(0xff303030, firstUpdatedPixel.get());
                assertTrue(map.isShowing());
                if (editing) {
                    assertEquals(initial.juliaParameters(), window.view.sceneSnapshot().juliaParameters());
                } else {
                    assertTrue(window.view.canExportPng());
                }
                return null;
            });
            if (editing) {
                awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()
                        && window.view.sceneSnapshot().juliaParameters().equals(targetPoint)));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void escapeBeforeResizeDispatchKeepsTheFilteredFrameAndPng(boolean editing) throws Exception {
        try (var window = new MainWindow()) {
            window.runtime.showIsolatedBrightPixel();
            var initialPoint = fx(() -> window.view.sceneSnapshot().juliaParameters());
            var map = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()));
            var root = fx(() -> map.map());
            var marker = fx(() -> (javafx.scene.shape.Circle) root.getChildrenUnmodifiable().get(1));
            var surface = fx(() -> (FractalSurface) window.view.getChildrenUnmodifiable().getFirst());
            double initialX = fx(marker::getCenterX);
            if (editing) {
                fx(() -> {
                    root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, initialX, marker.getCenterY(), true));
                    root.fireEvent(pointer(root, MouseEvent.MOUSE_DRAGGED, 220, 90, true));
                    return null;
                });
                awaitCondition(() -> fx(() -> marker.getCenterX() > initialX));
            }
            var pointOnClose = new AtomicReference<JuliaParameters>();
            int oldWidth = fx(() -> {
                int width = surface.renderWidth();
                window.view.widthProperty().addListener((obs, before, after) -> {
                    if (!map.isShowing()) return;
                    // The resize listener has scheduled work, but the debounce has not dispatched it.
                    root.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
                    pointOnClose.set(window.view.sceneSnapshot().juliaParameters());
                });
                window.stage.setWidth(window.stage.getWidth() * 1.5);
                return width;
            });
            awaitCondition(() -> fx(() -> !map.isShowing() && window.view.isLatencyIdle()
                    && surface.renderWidth() > oldWidth));
            fx(() -> {
                var image = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
                assertEquals(surface.renderWidth(), image.getWidth());
                assertEquals(0xff303030, image.getPixelReader().getArgb(1, 1));
                assertEquals(!editing, initialPoint.equals(pointOnClose.get()));
                assertEquals(pointOnClose.get(), window.view.sceneSnapshot().juliaParameters());
                assertTrue(window.view.showsApproximateJuliaPoint(pointOnClose.get()));
                assertTrue(window.view.canExportPng());
                return null;
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, false", "true, true"})
    void escapeBeforeQueuedNavigationKeepsTheMapPointAndPng(boolean zoom, boolean exceedsGpuLimit) throws Exception {
        try (var window = new MainWindow()) {
            window.runtime.showIsolatedBrightPixel();
            if (exceedsGpuLimit) {
                fx(() -> { window.view.setIterations(new IterationSettings(9990, 50)); return null; });
                awaitCondition(() -> fx(window.view::isLatencyIdle));
            } else if (!zoom) {
                // The home viewport cannot pan past the preset bounds.
                fx(() -> { window.view.zoom(true); return null; });
                awaitCondition(() -> fx(window.view::isLatencyIdle));
            }
            var initial = fx(window.view::sceneSnapshot);
            var errors = new AtomicReference<Throwable>();
            var map = fx(() -> {
                window.view.setOnRenderError(errors::set);
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()));
            var root = fx(() -> map.map());
            var marker = fx(() -> (javafx.scene.shape.Circle) root.getChildrenUnmodifiable().get(1));
            double initialX = fx(marker::getCenterX);
            fx(() -> {
                root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, initialX, marker.getCenterY(), true));
                root.fireEvent(pointer(root, MouseEvent.MOUSE_DRAGGED, 220, 90, true));
                return null;
            });
            awaitCondition(() -> fx(() -> marker.getCenterX() > initialX));
            var pointOnClose = fx(() -> {
                if (zoom) {
                    window.view.zoom(true);
                } else {
                    window.view.fireEvent(new ScrollEvent(ScrollEvent.SCROLL, 150, 100, 150, 100,
                            false, false, false, false, false, false, 25, 0, 25, 0,
                            ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                            ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
                }
                root.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
                assertFalse(map.isShowing());
                assertNotEquals(initial.viewport(), window.view.sceneSnapshot().viewport());
                return window.view.sceneSnapshot().juliaParameters();
            });
            awaitCondition(() -> fx(window.view::isLatencyIdle));
            fx(() -> {
                assertNull(errors.get());
                var surface = (FractalSurface) window.view.getChildrenUnmodifiable().getFirst();
                var rendered = window.view.sceneSnapshot();
                assertNotEquals(initial.juliaParameters(), pointOnClose);
                assertEquals(pointOnClose, rendered.juliaParameters());
                assertTrue(window.view.canExportPng());
                if (exceedsGpuLimit) {
                    assertEquals(rendered, surface.completedRender().scene());
                } else {
                    assertTrue(window.view.showsApproximateJuliaPoint(pointOnClose));
                    var image = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
                    assertEquals(0xff303030, image.getPixelReader().getArgb(1, 1));
                }
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void gpuRefusalOnResizeRendersTheVisibleMapPointOnCpu(boolean escape) throws Exception {
        try (var window = new MainWindow()) {
            var initial = fx(window.view::sceneSnapshot);
            var errors = new AtomicReference<Throwable>();
            var map = fx(() -> {
                window.view.setOnRenderError(errors::set);
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()));
            var root = fx(() -> map.map());
            var marker = fx(() -> (javafx.scene.shape.Circle) root.getChildrenUnmodifiable().get(1));
            double initialX = fx(marker::getCenterX);
            fx(() -> {
                root.fireEvent(pointer(root, MouseEvent.MOUSE_PRESSED, initialX, marker.getCenterY(), true));
                root.fireEvent(pointer(root, MouseEvent.MOUSE_DRAGGED, 220, 90, true));
                return null;
            });
            awaitCondition(() -> fx(() -> marker.getCenterX() > initialX));
            int oldWidth = fx(() -> {
                var surface = (FractalSurface) window.view.getChildrenUnmodifiable().getFirst();
                int width = surface.renderWidth();
                window.runtime.refuseFramesWiderThan(width);
                if (escape) window.runtime.gateFramesWiderThan(width);
                window.stage.setWidth(window.stage.getWidth() * 1.5);
                return width;
            });
            if (escape) {
                try {
                    awaitCondition(() -> window.runtime.lastStartedWidth() > oldWidth);
                    fx(() -> {
                        root.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                                "", "", javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
                        assertFalse(map.isShowing());
                        return null;
                    });
                } finally {
                    window.runtime.gateFramesWiderThan(Integer.MAX_VALUE);
                    window.runtime.release();
                }
            }
            awaitCondition(() -> fx(() -> !map.isShowing() && window.view.isLatencyIdle()));
            fx(() -> {
                assertNull(errors.get());
                var surface = (FractalSurface) window.view.getChildrenUnmodifiable().getFirst();
                var restored = window.view.sceneSnapshot();
                assertNotEquals(initial.juliaParameters(), restored.juliaParameters());
                assertEquals(restored, surface.completedRender().scene());
                assertTrue(window.view.canExportPng());
                return null;
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    void gpuRefusalWhenChangingPaletteFallsBackToCpu(boolean mapOpen, boolean custom) throws Exception {
        try (var window = new MainWindow()) {
            var errors = new AtomicReference<Throwable>();
            var map = fx(() -> {
                window.view.setOnRenderError(errors::set);
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()));
            fx(() -> {
                if (!mapOpen) map.hide();
                window.runtime.setEmptyResult(true);
                if (custom) window.view.setPaletteStops(PalettePreset.FIRE.stops());
                else window.view.setPalette(PalettePreset.FIRE);
                return null;
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && !window.view.juliaGpu()
                    && (window.view.hasExportableFrame() || errors.get() != null)));
            fx(() -> {
                assertNull(errors.get());
                assertFalse(map.isShowing());
                var surface = (FractalSurface) window.view.getChildrenUnmodifiable().getFirst();
                assertEquals(PalettePreset.FIRE.stops(), window.view.sceneSnapshot().coloring().paletteStops());
                assertEquals(window.view.sceneSnapshot(), surface.completedRender().scene());
                return null;
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void palettePublicationAfterMapCloseReenablesTheExportCommand(boolean custom) throws Exception {
        try (var window = new MainWindow()) {
            var map = fx(() -> {
                window.item("Render", JuliaExplorer.TITLE).fire();
                return javafx.stage.Window.getWindows().stream().filter(JuliaExplorer.class::isInstance)
                        .map(JuliaExplorer.class::cast).findFirst().orElseThrow();
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.canExportPng()));
            fx(() -> {
                map.hide();
                if (custom) window.view.setPaletteStops(PalettePreset.FIRE.stops());
                else window.view.setPalette(PalettePreset.FIRE);
                return null;
            });
            awaitCondition(() -> fx(window.view::canExportPng));
            fx(() -> {
                var export = window.item("File", "Export PNG…");
                assertNotNull(export.getAccelerator());
                assertFalse(export.isDisable(), "Publishing the palette must re-enable the export shortcut");
                return null;
            });
        }
    }

    @Test
    void switchingJuliaGpuKeepsTheIterationLimitAResizePreserved() throws Exception {
        try (var window = new MainWindow()) {
            FractalSurface surface = fx(() -> (FractalSurface) window.view.getChildrenUnmodifiable().stream()
                    .filter(FractalSurface.class::isInstance).findFirst().orElseThrow());
            for (int i = 0; i < 2; i++) {
                fx(() -> { window.view.zoom(true); return null; });
                awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.hasExportableFrame()));
            }
            int widthBefore = fx(() -> surface.completedRender().frame().request().width());
            fx(() -> {
                window.stage.setWidth(window.stage.getWidth() * 1.5);
                window.stage.setHeight(window.stage.getHeight() * 1.5);
                return null;
            });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && surface.completedRender() != null
                    && surface.completedRender().frame().request().width() > widthBefore));
            int preserved = fx(() -> surface.completedRender().frame().request().maxIterations());
            int recalculated = fx(() -> {
                FractalScene scene = window.view.sceneSnapshot();
                Viewport defaultViewport = new FractalCamera(FractalPreset.JULIA)
                        .defaultViewport((int) window.view.getWidth(), (int) window.view.getHeight());
                return scene.iterations().maxIterations(defaultViewport.scaleExact(), scene.viewport().scaleExact());
            });
            assertNotEquals(recalculated, preserved, "The fixture must change the recalculated limit");

            window.enableJuliaGpu();
            assertEquals(preserved, window.runtime.lastMaxIterations(), "Turning Julia GPU on changed the limit");
            CheckMenuItem juliaGpu = window.juliaGpuItem();
            fx(() -> { juliaGpu.setSelected(false); juliaGpu.fire(); return null; });
            awaitCondition(() -> fx(() -> window.view.isLatencyIdle() && window.view.hasExportableFrame()));
            assertEquals(preserved, (int) fx(() -> surface.completedRender().frame().request().maxIterations()),
                    "Turning Julia GPU off changed the limit");
        }
    }

    @Test
    void stoppingAnimationKeepsAPaletteChosenWhileItsFrameRuns() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            fixture.controller(controller -> controller.setColorCycling(true, JULIA.coloring()));
            fixture.runtime.setGated(true);
            ColoringSettings tick = new ColoringSettings(PalettePreset.ICE,
                    com.shangin.fractal.coloring.SmoothPaletteColoring.DEFAULT_COLOR_SCALE, 0.25);
            ColoringSettings fire = new ColoringSettings(PalettePreset.FIRE);
            fixture.controller(controller -> controller.recolor(tick));
            awaitCondition(() -> fixture.runtime.started() == 2);
            // The user picks Fire, then stops the animation while frames are still running:
            // the same calls FractalView makes for a palette choice and for stopping.
            fixture.controller(controller -> {
                controller.cancelRecolor();
                controller.recolor(fire);
                controller.cancelRecolor();
                controller.setColorCycling(false, fire);
            });
            fixture.runtime.setGated(false);
            fixture.runtime.release();
            int expected = FakeJuliaPreviewRuntime.expectedColor(fire, -0.8);
            awaitCondition(() -> fixture.displayed().length > 0 && fixture.displayed()[0] == expected);
            assertSolid(expected, fixture.displayed());
        }
    }

    @Test
    void stoppingAnimationAfterAPaletteChoiceShowsTheScenesPalette() throws Exception {
        try (var window = new MainWindow()) {
            window.enableJuliaGpu();
            FractalSurface surface = fx(() -> (FractalSurface) window.view.getChildrenUnmodifiable().stream()
                    .filter(FractalSurface.class::isInstance).findFirst().orElseThrow());
            CheckMenuItem animation = (CheckMenuItem) fx(() -> window.item("Color", "Animate Palette"));
            MenuItem fire = fx(() -> ((Menu) window.item("Color", "Palette")).getItems().stream()
                    .filter(item -> PalettePreset.FIRE.toString().equals(item.getText())).findFirst().orElseThrow());
            int before = window.runtime.calls();
            fx(() -> { animation.setSelected(true); animation.fire(); return null; });
            awaitCondition(() -> window.runtime.calls() >= before + 2);
            window.runtime.setGated(true);
            int started = window.runtime.started();
            awaitCondition(() -> window.runtime.started() > started);
            fx(() -> { ((javafx.scene.control.RadioMenuItem) fire).setSelected(true); fire.fire(); return null; });
            fx(() -> { animation.setSelected(false); animation.fire(); return null; });
            window.runtime.setGated(false);
            window.runtime.release();
            awaitCondition(() -> fx(() -> {
                int[] shown = displayed(surface);
                return shown.length > 0 && shown[0] == FakeJuliaPreviewRuntime.expectedColor(
                        window.view.sceneSnapshot().coloring(), -0.8);
            }));
            FractalScene scene = fx(window.view::sceneSnapshot);
            assertEquals(PalettePreset.FIRE, scene.coloring().palette());
            int expected = FakeJuliaPreviewRuntime.expectedColor(scene.coloring(), -0.8);
            int[] shown = fx(() -> displayed(surface));
            for (int pixel : shown) assertEquals(expected, pixel, "The frame does not show the scene's palette");
        }
    }

    private static int[] displayed(FractalSurface surface) {
        Image image = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
        if (image == null) return new int[0];
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return pixels;
    }

    /** A main window around the fake GPU runtime, showing Julia. */
    private static final class MainWindow implements AutoCloseable {
        final FakeJuliaPreviewRuntime runtime = new FakeJuliaPreviewRuntime();
        final CountingBackend cpu = new CountingBackend();
        Stage stage;
        FractalView view;
        MainMenuBar menuBar;

        MainWindow() throws Exception {
            var bookmarks = java.nio.file.Files.createTempDirectory("fractallens-julia-gpu-bookmarks");
            fx(() -> {
                stage = new Stage();
                view = new FractalView(JULIA, false, new FractalRenderService(cpu, runtime));
                menuBar = new MainMenuBar(stage, view, JULIA, () -> {},
                        new com.shangin.fractal.app.BookmarkStore(bookmarks));
                BorderPane root = new BorderPane(view);
                root.setTop(menuBar);
                stage.setScene(new Scene(root, 320, 240));
                stage.show();
                return null;
            });
            awaitCondition(() -> fx(() -> view.hasExportableFrame() && view.isLatencyIdle()));
        }

        MenuItem item(String menu, String title) {
            return JuliaGpuModeFxTest.item(menuBar, menu, title);
        }

        CheckMenuItem juliaGpuItem() throws Exception {
            return (CheckMenuItem) fx(() -> item("Render", MainMenuBar.JULIA_GPU_TITLE));
        }

        void enableJuliaGpu() throws Exception {
            CheckMenuItem juliaGpu = juliaGpuItem();
            fx(() -> { juliaGpu.setSelected(true); juliaGpu.fire(); return null; });
            awaitCondition(() -> fx(() -> view.showsJuliaGpuFrame() && view.isLatencyIdle()));
        }

        @Override public void close() throws Exception {
            fx(() -> {
                if (menuBar != null) menuBar.close();
                if (view != null) view.close();
                if (stage != null) stage.close();
                return null;
            });
        }
    }

    @Test
    void aCompletedCpuJuliaViewWarmsTheKernelOnce() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.render(FractalScene.create(FractalPreset.MANDELBROT, PalettePreset.ICE));
            fixture.render(JULIA);
            awaitCondition(() -> fixture.runtime.warmUps() == 1);
            assertEquals(16, fixture.runtime.lastWarmUpWidth());
            assertEquals(16, fixture.runtime.lastWarmUpHeight());
            assertEquals(fixture.lastIterations.get(), fixture.runtime.lastWarmUpMaxIterations());
            assertTrue(fixture.runtime.lastWarmUpFilterBrightPoints());
            assertEquals(0, fixture.runtime.calls(), "the warm-up shows no frame");

            fixture.render(JULIA.withJuliaParameters(new JuliaParameters(-0.123, 0.745)));
            // The worker runs in order, so this frame follows any warm-up queued before it.
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            assertEquals(1, fixture.runtime.calls());
            assertEquals(1, fixture.runtime.warmUps());
        }
    }

    @Test
    void aJuliaGpuFrameLeavesNoWarmUpForLaterCpuViews() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            fixture.controller(controller -> controller.setJuliaGpu(false));
            fixture.render(JULIA.withJuliaParameters(new JuliaParameters(-0.123, 0.745)));
            fixture.controller(controller -> controller.setJuliaGpu(true));
            fixture.render(JULIA);
            assertEquals(2, fixture.runtime.calls());
            assertEquals(0, fixture.runtime.warmUps());
        }
    }

    @Test
    void renderMenuTogglesJuliaGpuOnlyForJuliaAndDisablesExport() throws Exception {
        var runtime = new FakeJuliaPreviewRuntime();
        var backend = new CountingBackend();
        var bookmarks = java.nio.file.Files.createTempDirectory("fractallens-julia-gpu-bookmarks");
        Stage[] stage = new Stage[1];
        FractalView[] view = new FractalView[1];
        MainMenuBar[] menuBar = new MainMenuBar[1];
        try {
            fx(() -> {
                stage[0] = new Stage();
                view[0] = new FractalView(JULIA, false, new FractalRenderService(backend, runtime));
                menuBar[0] = new MainMenuBar(stage[0], view[0], JULIA, () -> {},
                        new com.shangin.fractal.app.BookmarkStore(bookmarks));
                BorderPane root = new BorderPane(view[0]);
                root.setTop(menuBar[0]);
                stage[0].setScene(new Scene(root, 320, 240));
                stage[0].show();
                return null;
            });
            awaitCondition(() -> fx(() -> view[0].hasExportableFrame()) && !fx(() -> item(menuBar[0],
                    "Render", MainMenuBar.JULIA_GPU_TITLE).isDisable()));
            CheckMenuItem juliaGpu = (CheckMenuItem) fx(() -> item(menuBar[0], "Render", MainMenuBar.JULIA_GPU_TITLE));
            MenuItem export = fx(() -> item(menuBar[0], "File", "Export PNG…"));
            assertFalse(fx(juliaGpu::isSelected));
            assertFalse(fx(export::isDisable));

            fx(() -> { juliaGpu.setSelected(true); juliaGpu.fire(); return null; });
            awaitCondition(() -> fx(() -> view[0].showsJuliaGpuFrame() && view[0].isLatencyIdle()));
            assertTrue(fx(juliaGpu::isSelected));
            assertTrue(fx(export::isDisable), "Approximate GPU frames cannot be exported");
            assertTrue(runtime.calls() >= 1);

            fx(() -> { juliaGpu.setSelected(false); juliaGpu.fire(); return null; });
            awaitCondition(() -> fx(() -> view[0].hasExportableFrame() && view[0].isLatencyIdle()));
            assertFalse(fx(export::isDisable));

            fx(() -> { view[0].setFractal(FractalPreset.MANDELBROT); return null; });
            awaitCondition(() -> fx(() -> view[0].hasExportableFrame() && view[0].isLatencyIdle()));
            assertTrue(fx(juliaGpu::isDisable), "Julia GPU applies only to Julia");

            // Without a usable GPU the switch is unavailable even for Julia.
            runtime.setAvailable(false);
            fx(() -> { view[0].setFractal(FractalPreset.JULIA); return null; });
            awaitCondition(() -> fx(() -> view[0].hasExportableFrame() && view[0].isLatencyIdle()));
            assertTrue(fx(juliaGpu::isDisable), "Julia GPU needs a usable GPU runtime");
        } finally {
            fx(() -> {
                if (menuBar[0] != null) menuBar[0].close();
                if (view[0] != null) view[0].close();
                if (stage[0] != null) stage[0].close();
                return null;
            });
        }
    }

    private static MenuItem item(MainMenuBar menuBar, String menu, String title) {
        return menuBar.getMenus().stream().filter(candidate -> menu.equals(candidate.getText()))
                .findFirst().orElseThrow().getItems().stream()
                .filter(candidate -> title.equals(candidate.getText()))
                .findFirst().orElseThrow();
    }

    private static void assertSolid(int expected, int[] pixels) {
        assertEquals(WIDTH * HEIGHT, pixels.length);
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] != expected) fail("Pixel " + i + " is " + Integer.toHexString(pixels[i])
                    + ", expected " + Integer.toHexString(expected));
        }
    }

    /** Controller and surface around a counting CPU backend and the fake GPU runtime. */
    private static final class Fixture implements AutoCloseable {
        final FakeJuliaPreviewRuntime runtime = new FakeJuliaPreviewRuntime();
        final CountingBackend backend = new CountingBackend();
        final FractalSurface surface;
        final FractalRenderController controller;
        final AtomicReference<RenderStatus> status = new AtomicReference<>();
        final AtomicReference<Integer> lastIterations = new AtomicReference<>();

        Fixture() throws Exception {
            surface = fx(FractalSurface::new);
            controller = fx(() -> new FractalRenderController(surface, new FractalRenderService(backend, runtime)));
            fx(() -> {
                controller.setWidePreviewSettings(WidePreviewSettings.DISABLED);
                controller.setOnRenderStatusChanged(status::set);
                controller.setOnIterationsChanged(lastIterations::set);
                surface.resize(WIDTH, HEIGHT);
                surface.resizeBuffer(WIDTH, HEIGHT);
                return null;
            });
        }

        void controller(Consumer<FractalRenderController> action) throws Exception {
            fx(() -> { action.accept(controller); return null; });
        }

        void startRender(FractalScene scene) throws Exception {
            startRender(scene, WIDTH, HEIGHT, scene.fractal().defaultViewport(), false);
        }

        void startRender(FractalScene scene, int width, int height, Viewport defaultViewport,
                         boolean preserveIterationLimit) throws Exception {
            fx(() -> {
                controller.cancelCurrent();
                controller.render(scene, new RenderTarget(width, height, RenderPriority.center()),
                        defaultViewport, preserveIterationLimit);
                return null;
            });
        }

        void render(FractalScene scene) throws Exception {
            startRender(scene);
            awaitCondition(() -> status.get().state() == RenderStatus.State.COMPLETE);
            fx(() -> null);
        }

        int[] displayed() throws Exception {
            return fx(() -> {
                Image image = ((ImageView) surface.getChildrenUnmodifiable().getFirst()).getImage();
                if (image == null) return new int[0];
                int width = (int) image.getWidth(), height = (int) image.getHeight();
                int[] pixels = new int[width * height];
                image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(),
                        pixels, 0, width);
                return pixels;
            });
        }

        @Override public void close() throws Exception {
            fx(() -> { controller.close(); return null; });
        }
    }

    /** Direct CPU rendering that counts calculated frames. */
    private static final class CountingBackend implements RenderBackend {
        private final DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled, Consumer<RenderRegion> region,
                                  Consumer<TileTimingStats> timings) throws InterruptedException {
            calls.incrementAndGet();
            return direct.render(frame, cancelled, region, timings);
        }

        @Override public boolean supports(RenderJob job) { return direct.supports(job); }
        @Override public void close() { direct.close(); }
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
}
