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
        ColoringSettings coloring
) {
    public FractalScene {
        Objects.requireNonNull(fractal);
        Objects.requireNonNull(viewport);
        Objects.requireNonNull(iterations);
        Objects.requireNonNull(coloring);
    }

    public static FractalScene create(
            FractalPreset fractal,
            PalettePreset palette
    ) {
        return new FractalScene(
                fractal,
                fractal.defaultViewport(),
                new IterationSettings(),
                new ColoringSettings(palette)
        );
    }

    public FractalScene withViewport(Viewport viewport) {
        return new FractalScene(fractal, viewport, iterations, coloring);
    }

    public FractalScene withFractal(
            FractalPreset fractal,
            Viewport viewport
    ) {
        return new FractalScene(fractal, viewport, iterations, coloring);
    }

    public FractalScene withColoring(ColoringSettings coloring) {
        return new FractalScene(fractal, viewport, iterations, coloring);
    }
}
