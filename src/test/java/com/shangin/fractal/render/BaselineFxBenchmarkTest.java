package com.shangin.fractal.render;

import com.shangin.fractal.export.InteractiveAntialiasService;
import com.shangin.fractal.scene.InteractiveRenderMode;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.*;
import java.lang.ref.WeakReference;

import static com.shangin.fractal.render.BaselineFxBenchmark.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PixelBuffer publication and epoch/retention regressions; opt in with graphical access. */
@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class BaselineFxBenchmarkTest {
    @BeforeAll static void startFx() throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
        started.get(15, TimeUnit.SECONDS);
    }
    @AfterAll static void stopFx() { Platform.exit(); }

    @ParameterizedTest @EnumSource(InteractiveRenderMode.class)
    void visibleMilestonesDistinguishHiddenRefinedBaseFromFastBase(InteractiveRenderMode mode) throws Exception {
        var backend = new TimedBackend();
        try (var ui = fx(Ui::new); var service = new FractalRenderService(backend); var aa = new InteractiveAntialiasService()) {
            var result = measure(ui, fixture("julia-aa-regular", 96, 66).steps().getFirst(), mode, service, backend, aa, null, null);
            verify(result);
            assertEquals(mode, result.effective);
            assertTrue(result.baseComplete >= result.backendEnd);
            assertTrue(result.firstAa >= result.aaStart);
            assertTrue(result.fullPublish >= result.firstAa);
            assertTrue(result.firstPostLayout >= result.firstVisible);
            assertTrue(result.fullPostLayout >= result.fullPublish);
            if (mode == InteractiveRenderMode.REFINED) {
                assertEquals(-1, result.firstBase);
                assertEquals(-1, result.baseFull);
                assertEquals(result.firstAa, result.firstVisible);
            } else {
                assertTrue(result.firstBase >= result.start);
                assertTrue(result.baseFull >= result.firstBase);
                assertTrue(result.firstVisible <= result.firstAa);
            }
        }
    }

    @Test void reverseZoomPublishesRetainedSamplesWithoutInventingANewRegion() throws Exception {
        var backend = new TimedBackend();
        try (var ui = fx(Ui::new); var service = new FractalRenderService(backend); var aa = new InteractiveAntialiasService()) {
            var steps = fixture("direct-deep-reverse", 480, 270).steps();
            var direct = measure(ui, steps.get(0), InteractiveRenderMode.FAST, service, backend, aa, null, null);
            var deep = measure(ui, steps.get(1), InteractiveRenderMode.FAST, service, backend, aa, direct.frame, null);
            assertEquals("MandelbrotPerturbationRenderBackend", deep.backend);
            var reverse = measure(ui, steps.get(2), InteractiveRenderMode.FAST, service, backend, aa, deep.frame, direct.frame);
            verify(reverse);
            assertEquals("retained", reverse.reuseSource);
            assertEquals(480 * 270, reverse.reused);
            assertEquals(-1, reverse.firstRegion.get());
            assertTrue(reverse.firstBase >= reverse.start);
            assertNotEquals(deep.frame.job().maxIterations(), reverse.frame.job().maxIterations());
            assertArrayEquals(direct.pixels, reverse.pixels);
        }
    }

    @Test void cancelledGenerationCannotPublishIntoTheNextRequest() throws Exception {
        var backend = new TimedBackend();
        try (var ui = fx(Ui::new); var service = new FractalRenderService(backend); var aa = new InteractiveAntialiasService()) {
            var cancelled = measure(ui, fixture("cancel-deep", 480, 270).steps().getFirst(), InteractiveRenderMode.FAST,
                    service, backend, aa, null, null);
            assertEquals("MandelbrotPerturbationRenderBackend", cancelled.backend);
            assertTrue(cancelled.cancel.get() >= cancelled.start);
            assertTrue(cancelled.backendEnd >= cancelled.cancel.get());
            assertEquals(-1, cancelled.fullPublish);
            assertEquals(-1, cancelled.firstVisible);
            assertNull(cancelled.pixels);
            var next = measure(ui, fixture("julia-aa-regular", 96, 66).steps().getFirst(), InteractiveRenderMode.REFINED,
                    service, backend, aa, null, null);
            verify(next);
            assertTrue(next.request > cancelled.request);
            assertTrue(next.firstVisible >= next.start);
            assertEquals(-1, cancelled.fullPublish);
        }
    }

    @Test void retainedAaPreparesItsCacheWithoutPublishingThePreparation() throws Exception {
        var backend = new TimedBackend();
        try (var ui = fx(Ui::new); var service = new FractalRenderService(backend); var aa = new InteractiveAntialiasService()) {
            var r = measure(ui, fixture("julia-aa-retained", 96, 66).steps().getFirst(), InteractiveRenderMode.REFINED,
                    service, backend, aa, null, null);
            verify(r);
            assertTrue(r.aaPrepare > 0);
            assertTrue(r.firstAa >= r.aaStart);
            assertEquals(r.firstAa, r.firstVisible);
        }
    }

    @Test void histogramAndResizedPixelsMatchHeadlessControl() throws Exception {
        var backend = new TimedBackend();
        try (var ui = fx(Ui::new); var service = new FractalRenderService(backend); var aa = new InteractiveAntialiasService()) {
            verify(measure(ui, fixture("mandelbrot-histogram-aa", 96, 66).steps().getFirst(), InteractiveRenderMode.FAST,
                    service, backend, aa, null, null));
            fx(() -> { ui.reset(); return null; });
            RenderFrame active = null, retained = null;
            for (var step : fixture("resize-then-drag", 96, 66).steps()) {
                var r = measure(ui, step, InteractiveRenderMode.FAST, service, backend, aa, active, retained);
                verify(r);
                if (active != null) assertTrue(r.reused > 0);
                assertEquals(step.job().width(), fx(() -> ui.surface.renderWidth()));
                assertEquals(step.job().height(), fx(() -> ui.surface.renderHeight()));
                retained = active; active = r.frame;
            }
        }
    }

    @Test void detachedSurfaceDoesNotFollowItsOldScenesWindow() throws Exception {
        try (var ui = fx(Ui::new)) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            fx(() -> {
                var removed = ui.surface;
                removed.setOnOutputScaleChanged(calls::incrementAndGet);
                ui.reset();
                // Moving the still-live old scene must not reattach a removed surface.
                var oldScene = ui.stage.getScene();
                ui.stage.setScene(null);
                ui.stage.setScene(oldScene);
                assertEquals(0, calls.get());
                ui.root.getChildren().setAll(removed);
                assertEquals(1, calls.get(), "Reattaching the surface must restore its scale subscription");
                ui.stage.setScene(null);
                ui.stage.setScene(oldScene);
                assertEquals(2, calls.get(), "One active subscription must survive a window change");
                return null;
            });
        }
    }

    @Test void detachedSurfacesCanBeCollectedWhileTheirWindowStaysOpen() throws Exception {
        try (var ui = fx(Ui::new)) {
            WeakReference<com.shangin.fractal.ui.FractalSurface> removed = fx(() -> {
                var reference = new WeakReference<>(ui.surface);
                ui.reset();
                return reference;
            });
            var settled = new CompletableFuture<Void>();
            fx(() -> {
                var scene = ui.stage.getScene();
                var pulses = new java.util.concurrent.atomic.AtomicInteger();
                Runnable[] observer = new Runnable[1];
                observer[0] = () -> {
                    if (pulses.incrementAndGet() == 2) {
                        scene.removePostLayoutPulseListener(observer[0]);
                        settled.complete(null);
                    } else Platform.requestNextPulse();
                };
                scene.addPostLayoutPulseListener(observer[0]);
                Platform.requestNextPulse();
                return null;
            });
            settled.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 20 && !removed.refersTo(null); i++) {
                System.gc();
                Thread.sleep(25);
            }
            assertTrue(removed.refersTo(null), "Scene/window scale listeners must not retain detached surfaces and their frames");
        }
    }

    private static BaselineFixtures.Fixture fixture(String id, int width, int height) {
        return BaselineFixtures.matrix(width, height).stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }
}
