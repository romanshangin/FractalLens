package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.util.Objects;

/** Immutable mathematical and visual state of one reproducible fractal view. */
public record FractalScene(
        FractalPreset fractal,
        Viewport viewport,
        IterationSettings iterations,
        ColoringSettings coloring,
        AntialiasSettings antialiasing,
        JuliaParameters juliaParameters
) {
    public FractalScene {
        Objects.requireNonNull(fractal);
        Objects.requireNonNull(viewport);
        Objects.requireNonNull(iterations);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(antialiasing);
        Objects.requireNonNull(juliaParameters);
    }

    public FractalScene(
            FractalPreset fractal,
            Viewport viewport,
            IterationSettings iterations,
            ColoringSettings coloring
    ) {
        this(fractal, viewport, iterations, coloring, new AntialiasSettings(), new JuliaParameters());
    }

    public FractalScene(
            FractalPreset fractal,
            Viewport viewport,
            IterationSettings iterations,
            ColoringSettings coloring,
            AntialiasSettings antialiasing
    ) {
        this(fractal, viewport, iterations, coloring, antialiasing, new JuliaParameters());
    }

    public static FractalScene create(
            FractalPreset fractal,
            PalettePreset palette
    ) {
        return new FractalScene(
                fractal,
                fractal.defaultViewport(),
                new IterationSettings(),
                new ColoringSettings(palette),
                new AntialiasSettings(),
                new JuliaParameters()
        );
    }

    public FractalScene withViewport(Viewport viewport) {
        return new FractalScene(fractal, viewport, iterations, coloring, antialiasing, juliaParameters);
    }

    public FractalScene withFractal(
            FractalPreset fractal,
            Viewport viewport
    ) {
        return new FractalScene(fractal, viewport, iterations, coloring, antialiasing, juliaParameters);
    }

    public FractalScene withColoring(ColoringSettings coloring) {
        return new FractalScene(fractal, viewport, iterations, coloring, antialiasing, juliaParameters);
    }

    public FractalScene withAntialiasing(AntialiasSettings antialiasing) {
        return new FractalScene(fractal, viewport, iterations, coloring, antialiasing, juliaParameters);
    }

    public FractalScene withIterations(IterationSettings iterations) {
        return new FractalScene(fractal, viewport, iterations, coloring, antialiasing, juliaParameters);
    }

    public FractalScene withJuliaParameters(JuliaParameters parameters) {
        return new FractalScene(fractal, viewport, iterations, coloring, antialiasing, parameters);
    }
}
