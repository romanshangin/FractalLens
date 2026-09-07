package com.shangin.fractal.render;

import com.shangin.fractal.coloring.*;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.scene.IterationSettings;
import com.shangin.fractal.scene.SamplingPattern;

import java.math.BigDecimal;
import java.util.*;

/** Versioned, decimal-authoritative workloads shared by the manifest and runner. */
final class BaselineFixtures {
    static final String VERSION = "9.1-v1";
    static final String HEADER = "matrix,fixture,step,action,formula,parameters,center_real,center_imaginary,scale,width,height,iteration_policy,base_iterations,iterations_per_zoom,actual_cap,trap,coloring,palette,palette_offset,aa,aa_grid,pattern,accuracy,base_cache,aa_cache,cancel_trigger";
    private static final String DEEP_REAL = "-0.8317528516858322713653476366999";
    private static final String DEEP_IMAGINARY = "0.207813754242134522471317257011028";

    enum Action { FRESH, REUSE, CANCEL_FIRST_REGION }
    enum Aa { NONE, EMPTY, SAME_FRAME }
    enum Colors { SMOOTH, HISTOGRAM, TRAP }

    record Step(String name, Action action, RenderJob job, String iterationPolicy,
                Aa aa, SamplingPattern pattern, Colors colors) {
        ColoringStrategy coloring(RenderFrame frame) {
            return switch (colors) {
                case SMOOTH -> new SmoothPaletteColoring(PalettePreset.ICE.palette());
                case HISTOGRAM -> new HistogramPaletteColoring(PalettePreset.ICE.palette(), 0, frame.samplePlane());
                case TRAP -> new OrbitTrapColoring(PalettePreset.ICE.palette(), 0);
            };
        }

        String csv(String fixture) {
            var v = job.viewport();
            String parameters = new TreeMap<>(job.formula().parameters()).entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ";" + b).orElse("none");
            return String.join(",", VERSION, fixture, name, action.name(), job.formula().id(), parameters,
                    v.center().real().toString(), v.center().imaginary().toString(), v.scaleExact().toString(),
                    "" + job.width(), "" + job.height(), iterationPolicy,
                    iterationPolicy.equals("fixed") ? "" + job.maxIterations() : "300",
                    iterationPolicy.equals("fixed") ? "0" : "50", "" + job.maxIterations(),
                    job.formula().orbitTrap().name(), colors.name(), "ICE", "0", aa.name(), aaGrid(), pattern.name(),
                    job.sampleAccuracy().name(), action == Action.REUSE ? "active_then_retained" : "empty",
                    aa == Aa.SAME_FRAME ? "prepared_same_frame" : aa == Aa.EMPTY ? "empty" : "disabled",
                    action == Action.CANCEL_FIRST_REGION ? "first_backend_region" : "none");
        }

        private String aaGrid() {
            if (aa == Aa.NONE) return "none";
            return job.viewport().hasSufficientPrecision(job.width(), job.height(), job.formula().preset().minimumUlpsPerPixel())
                    ? "candidate_4x4" : "candidate_2x2_then_4x4";
        }
    }

    record Fixture(String id, List<Step> steps) {
        Fixture { steps = List.copyOf(steps); }
    }

    static List<Fixture> matrix(int width, int height) {
        if (width < 65 || height < 65 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Matrix dimensions must be 65..4096");
        }
        List<Fixture> fixtures = new ArrayList<>();
        for (FractalPreset preset : FractalPreset.values()) {
            fixtures.add(single(preset.name().toLowerCase(Locale.ROOT) + "-overview",
                    step("render", preset, preset.defaultViewport(), width, height, 300)));
        }
        fixtures.add(single("exterior", step("render", FractalPreset.MANDELBROT,
                new Viewport("1", "1", "0.2"), width, height, 300)));
        Viewport seahorse = new Viewport("-0.743643887037151", "0.13182590420533", "0.024");
        fixtures.add(single("seahorse-fixed", step("render", FractalPreset.MANDELBROT, seahorse, width, height, 300)));
        fixtures.add(single("seahorse-adaptive", adaptive("render", seahorse, width, height)));
        for (SamplingPattern pattern : SamplingPattern.values()) {
            for (FractalPreset preset : List.of(FractalPreset.MANDELBROT, FractalPreset.JULIA)) {
                Step base = step("render", preset, preset.defaultViewport(), width, height, 300);
                fixtures.add(single(preset.name().toLowerCase(Locale.ROOT) + "-aa-" + pattern.name().toLowerCase(Locale.ROOT),
                        features(base, Aa.EMPTY, pattern, Colors.SMOOTH)));
            }
        }
        fixtures.add(single("julia-aa-retained", features(step("render", FractalPreset.JULIA,
                FractalPreset.JULIA.defaultViewport(), width, height, 300), Aa.SAME_FRAME, SamplingPattern.REGULAR, Colors.SMOOTH)));
        fixtures.add(single("mandelbrot-histogram-aa", features(step("render", FractalPreset.MANDELBROT,
                seahorse, width, height, 300), Aa.EMPTY, SamplingPattern.REGULAR, Colors.HISTOGRAM)));
        RenderJob trapJob = new RenderJob(FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.CROSS),
                seahorse, width, height, 300);
        fixtures.add(single("mandelbrot-trap-aa", new Step("render", Action.FRESH, trapJob,
                "fixed", Aa.EMPTY, SamplingPattern.REGULAR, Colors.TRAP)));

        Viewport glitch = new Viewport(DEEP_REAL, DEEP_IMAGINARY, "1.6e-13");
        fixtures.add(single("deep-glitch", adaptive("render", glitch, width, height)));
        fixtures.add(single("deep-glitch-aa", features(adaptive("render", glitch, width, height),
                Aa.EMPTY, SamplingPattern.REGULAR, Colors.SMOOTH)));
        fixtures.add(single("deep-bla", step("render", FractalPreset.MANDELBROT,
                new Viewport(DEEP_REAL, DEEP_IMAGINARY, "1e-30"), width, height, 2700)));
        fixtures.add(single("deep-boundary", step("render", FractalPreset.MANDELBROT,
                new Viewport("0", "1", "1e-30"), width, height, 137)));
        fixtures.add(single("scaled-exponent", step("render", FractalPreset.MANDELBROT,
                new Viewport("2", "0", "1e-400"), width, height, 137)));

        Step reverse = adaptive("reverse", new Viewport(DEEP_REAL, DEEP_IMAGINARY, "1e-9"), width, height);
        fixtures.add(new Fixture("direct-deep-reverse", List.of(
                adaptive("direct", new Viewport(DEEP_REAL, DEEP_IMAGINARY, "1e-9"), width, height),
                adaptive("deep", glitch, width, height),
                new Step(reverse.name(), Action.REUSE, reverse.job(), reverse.iterationPolicy(),
                        reverse.aa(), reverse.pattern(), reverse.colors()))));
        for (int percent : new int[]{5, 25, 90}) {
            Step start = adaptive("source", seahorse, width, height);
            int pixels = Math.max(1, width * percent / 100);
            Viewport panned = panPixels(seahorse, height, pixels, 0);
            fixtures.add(new Fixture("pan-" + percent, List.of(start,
                    reuse("pan", start, panned, width, height))));
        }
        Step origin = adaptive("source", seahorse, width, height);
        fixtures.add(new Fixture("pan-reverse", List.of(origin,
                reuse("out", origin, panPixels(seahorse, height, width / 4, 0), width, height),
                reuse("back", origin, seahorse, width, height))));
        int resizedWidth = width + 128, resizedHeight = height + 72;
        Viewport resized = new Viewport(seahorse.center(), seahorse.imaginaryUnitsPerPixelExact(height)
                .multiply(BigDecimal.valueOf(resizedHeight - 1L), seahorse.mathContext()));
        fixtures.add(new Fixture("resize-then-drag", List.of(origin,
                reuse("resize", origin, resized, resizedWidth, resizedHeight),
                reuse("drag", origin, panPixels(resized, resizedHeight, 17, -11), resizedWidth, resizedHeight))));
        for (Step start : List.of(origin, adaptive("source", glitch, width, height))) {
            fixtures.add(single(start == origin ? "cancel-direct" : "cancel-deep",
                    new Step("cancel", Action.CANCEL_FIRST_REGION, start.job(), start.iterationPolicy(),
                            Aa.NONE, SamplingPattern.REGULAR, Colors.SMOOTH)));
        }
        return List.copyOf(fixtures);
    }

    private static Fixture single(String id, Step step) { return new Fixture(id, List.of(step)); }

    private static Step step(String name, FractalPreset preset, Viewport viewport, int width, int height, int cap) {
        return new Step(name, Action.FRESH, new RenderJob(FormulaDefinition.forPreset(preset, OrbitTrap.NONE),
                viewport, width, height, cap), "fixed", Aa.NONE, SamplingPattern.REGULAR, Colors.SMOOTH);
    }

    private static Step adaptive(String name, Viewport viewport, int width, int height) {
        int cap = new IterationSettings().maxIterations(FractalPreset.MANDELBROT.defaultViewport().scaleExact(), viewport.scaleExact());
        Step fixed = step(name, FractalPreset.MANDELBROT, viewport, width, height, cap);
        return new Step(name, fixed.action(), fixed.job(), "adaptive", fixed.aa(), fixed.pattern(), fixed.colors());
    }

    private static Step features(Step base, Aa aa, SamplingPattern pattern, Colors colors) {
        return new Step(base.name(), base.action(), base.job(), base.iterationPolicy(), aa, pattern, colors);
    }

    private static Step reuse(String name, Step source, Viewport viewport, int width, int height) {
        return new Step(name, Action.REUSE, new RenderJob(source.job().formula(), viewport, width, height,
                source.job().maxIterations()), "preserved_adaptive", Aa.NONE, SamplingPattern.REGULAR, Colors.SMOOTH);
    }

    private static Viewport panPixels(Viewport source, int height, int x, int y) {
        BigDecimal unit = source.imaginaryUnitsPerPixelExact(height);
        return source.pan(unit.multiply(BigDecimal.valueOf(x)), unit.multiply(BigDecimal.valueOf(y)));
    }
}
