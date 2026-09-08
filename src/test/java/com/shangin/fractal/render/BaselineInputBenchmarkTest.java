package com.shangin.fractal.render;

import com.shangin.fractal.scene.InteractiveRenderMode;
import com.shangin.fractal.ui.BaselineInputAccess;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;

import static com.shangin.fractal.render.BaselineFxBenchmark.fx;
import static com.shangin.fractal.render.BaselineInputBenchmark.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "fractal.fx.tests", matches = "true")
class BaselineInputBenchmarkTest {
    @BeforeAll static void startFx() throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
        started.get(15, TimeUnit.SECONDS);
    }
    @AfterAll static void stopFx() { Platform.exit(); }

    @ParameterizedTest @ValueSource(strings = {"wheel", "wheel-burst", "pinch", "trackpad", "drag"})
    void installedHandlersProduceTraceAndExactOutput(String gesture) throws Exception {
        var fixture = fixture("seahorse-fixed");
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), InteractiveRenderMode.FAST);
            var r = measure(ui, fixture, fixture.steps().getFirst(), InteractiveRenderMode.FAST, gesture, 1);
            verify(r);
            assertTrue(r.changed);
            assertEquals(gesture.equals("wheel") ? 1 : 6, r.lastInput());
            assertTrue(r.time("preview_publish", -1) > 0);
            assertTrue(r.time("complete_post_layout", r.lastInput()) >= r.time("complete", r.lastInput()));
            assertEquals(300, r.frame.job().maxIterations());
            assertNotEquals(fixture.steps().getFirst().job().viewport(), r.frame.job().viewport());
            int pixel = r.pixels[0];
            r.pixels[0] ^= 1;
            assertThrows(IllegalStateException.class, () -> verify(r), "Control must inspect actual pixels, not only a hash");
            r.pixels[0] = pixel;
            if (gesture.equals("pinch")) {
                assertEquals(1, r.events.stream().filter(e -> e.stage().equals("render_start")).count());
                assertTrue(r.time("render_start", r.lastInput()) >= r.time("pinch_finished", r.lastInput()));
            }
        }
    }

    @ParameterizedTest @EnumSource(InteractiveRenderMode.class)
    void refinedPublicationAndActualAaPolicyAreExplicit(InteractiveRenderMode mode) throws Exception {
        var fixture = fixture("julia-aa-deterministic_jitter");
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), mode);
            var r = measure(ui, fixture, fixture.steps().getFirst(), mode, "wheel", 1);
            verify(r);
            assertEquals(BaselineFixtures.Aa.EMPTY, r.actual.aa());
            assertTrue(r.time("aa_publish", r.lastInput()) > 0);
            if (mode == InteractiveRenderMode.REFINED) assertEquals(-1, r.time("base_publish", r.lastInput()));
        }
    }

    @Test void resizeThenDragKeepsPhysicalGridAndIterationBudget() throws Exception {
        var fixture = fixture("resize-then-drag");
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), InteractiveRenderMode.FAST);
            int cap = fx(() -> BaselineInputAccess.surface(ui.view).completedRender().frame().job().maxIterations());
            for (var step : fixture.steps().subList(1, 3)) {
                var r = measure(ui, fixture, step, InteractiveRenderMode.FAST, "sequence", 1);
                verify(r);
                assertEquals(step.job().viewport(), r.actual.job().viewport());
                assertEquals(step.job().width(), r.actual.job().width());
                assertEquals(step.job().height(), r.actual.job().height());
                assertEquals(cap, r.actual.job().maxIterations());
                if (step.name().equals("resize")) assertTrue(r.events.stream().anyMatch(e -> e.stage().equals("input_resize")));
            }
        }
    }

    @Test void reverseNavigationRetainsSourceAndRecomputesZoomBudget() throws Exception {
        var fixture = fixture("direct-deep-reverse");
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), InteractiveRenderMode.FAST);
            var source = fx(() -> BaselineInputAccess.surface(ui.view).completedRender().frame());
            var deep = measure(ui, fixture, fixture.steps().get(1), InteractiveRenderMode.FAST, "sequence", 1);
            verify(deep);
            assertTrue(deep.frame.job().maxIterations() > source.job().maxIterations());
            var back = measure(ui, fixture, fixture.steps().get(2), InteractiveRenderMode.FAST, "sequence", 2);
            verify(back);
            assertEquals(source.job().viewport().center(), back.frame.job().viewport().center());
            // The handler consumes a double factor: preserve the resulting decimal
            // instead of labelling it as the canonical exact reverse-cache hit.
            assertTrue(source.job().viewport().scaleExact().subtract(back.frame.job().viewport().scaleExact()).abs()
                    .compareTo(new java.math.BigDecimal("1e-24")) < 0);
            assertEquals(source.job().maxIterations(), back.frame.job().maxIterations());
        }
    }

    @Test void replacementKeepsGenerationsSeparateAndCompletesLastInput() throws Exception {
        var fixture = fixture("cancel-deep");
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), InteractiveRenderMode.FAST);
            var r = measure(ui, fixture, fixture.steps().getFirst(), InteractiveRenderMode.FAST, "replacement", 1);
            verify(r);
            assertEquals(2, r.lastInput());
            assertEquals(2, r.events.stream().filter(e -> e.stage().equals("render_start")).count());
            assertTrue(r.time("replacement_dispatch", 1) >= r.time("render_submit", 1));
            assertEquals(-1, r.time("complete", 1));
            assertTrue(r.time("complete", 2) > r.time("render_start", 2));
            if (RenderDiagnostics.enabled()) {
                var drains = r.events.stream().filter(e -> e.stage().equals("diagnostic_request_drained")).toList();
                assertEquals(2, drains.size());
                assertEquals(java.util.Set.of(1L, 2L), drains.stream().map(e -> e.input()).collect(java.util.stream.Collectors.toSet()));
                assertTrue(r.events.stream().anyMatch(e -> e.stage().equals("diagnostic_cancel_requested") && e.input() == 1));
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"trackpad", "drag"})
    void scaledExponentClampingCompletesTheActualCameraRequest(String gesture) throws Exception {
        var fixture = fixture("scaled-exponent");
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), InteractiveRenderMode.FAST);
            var r = measure(ui, fixture, fixture.steps().getFirst(), InteractiveRenderMode.FAST, gesture, 1);
            verify(r);
            assertTrue(r.changed);
            assertEquals(fx(() -> BaselineInputAccess.viewport(ui.view)), r.frame.job().viewport());
        }
    }

    @Test void asynchronousFxFailureCannotBecomeASuccessfulNoChangeTrial() throws Exception {
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture("exterior").steps().getFirst(), InteractiveRenderMode.FAST);
            fx(() -> { Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), new IllegalStateException("injected timer failure")); return null; });
            assertThrows(IllegalStateException.class, () -> awaitIdle(ui));
        }
    }

    @Test void deepPanChecksRetainedSamplesAgainstTheSameReferenceContract() throws Exception {
        var fixture = BaselineFixtures.matrix(480, 270).stream().filter(f -> f.id().equals("deep-glitch")).findFirst().orElseThrow();
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), InteractiveRenderMode.FAST);
            var r = measure(ui, fixture, fixture.steps().getFirst(), InteractiveRenderMode.FAST, "trackpad", 1);
            verify(r);
            assertTrue(new FrameReusePlanner().plan(r.source, r.frame.job()).reusedPixels() > 0);
        }
    }

    @Test void directToDeepTransitionRecordsTheActualTransientAaToggle() throws Exception {
        var fixture = fixture("deep-glitch-aa");
        try (var ui = fx(Ui::new)) {
            ui.seed(fixture.steps().getFirst(), InteractiveRenderMode.FAST);
            assertFalse(fx(() -> BaselineInputAccess.deepZoom(ui.view)));
            var r = measure(ui, fixture, fixture.steps().getFirst(), InteractiveRenderMode.FAST, "wheel-burst", 1);
            verify(r);
            assertTrue(fx(() -> BaselineInputAccess.deepZoom(ui.view)));
            assertEquals(BaselineFixtures.Aa.NONE, r.actual.aa());
            assertEquals(-1, r.time("aa_publish", r.lastInput()));
        }
    }

    @Test void clampedHomePanIsRecordedAsNoChangeWithoutInventedCompletion() throws Exception {
        try (var ui = fx(Ui::new)) {
            var template = fixture("mandelbrot-overview").steps().getFirst();
            ui.seed(template, InteractiveRenderMode.FAST);
            var home = fx(() -> new com.shangin.fractal.ui.FractalCamera(template.job().formula().preset())
                    .defaultViewport((int) ui.view.getWidth(), (int) ui.view.getHeight()));
            var step = new BaselineFixtures.Step("home", template.action(), new RenderJob(template.job().formula(), home, 96, 66, 300),
                    "fixed", template.aa(), template.pattern(), template.colors());
            var fixture = new BaselineFixtures.Fixture("home-control", java.util.List.of(step));
            ui.seed(step, InteractiveRenderMode.FAST);
            var r = measure(ui, fixture, step, InteractiveRenderMode.FAST, "trackpad", 1);
            verify(r);
            assertFalse(r.changed);
            assertEquals(6, r.lastInput());
            assertEquals(-1, r.time("complete", -1));
            assertTrue(r.csv("sample", 0).contains(",no_change,"));
        }
    }

    static BaselineFixtures.Fixture fixture(String id) {
        return BaselineFixtures.matrix(96, 66).stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }
}
