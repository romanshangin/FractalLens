package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.HistogramPaletteColoring;
import com.shangin.fractal.render.FractalData;

import java.util.Objects;

/** Immutable parameters used to construct a coloring strategy. */
public record ColoringSettings(
        PalettePreset palette,
        double colorScale,
        double offset,
        boolean histogramColoring
) {
    public static final double DEFAULT_COLOR_SCALE = SmoothPaletteColoring.DEFAULT_COLOR_SCALE;

    public ColoringSettings {
        Objects.requireNonNull(palette);

        if (!Double.isFinite(colorScale) || colorScale <= 0.0) {
            throw new IllegalArgumentException("Color scale must be positive and finite");
        }
        if (!Double.isFinite(offset)) {
            throw new IllegalArgumentException("Color offset must be finite");
        }
    }

    public ColoringSettings(PalettePreset palette) {
        this(palette, DEFAULT_COLOR_SCALE, 0.0, false);
    }

    public ColoringSettings(PalettePreset palette, double colorScale, double offset) {
        this(palette, colorScale, offset, false);
    }

    public ColoringStrategy createStrategy() {
        return new SmoothPaletteColoring(
                palette.palette(),
                colorScale,
                offset
        );
    }

    /** Builds the optional second-pass mapping once the whole frame is available. */
    public ColoringStrategy createStrategy(FractalData data) {
        return histogramColoring
                ? new HistogramPaletteColoring(palette.palette(), offset, data)
                : createStrategy();
    }
}
