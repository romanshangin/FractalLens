package com.shangin.fractal.scene;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.PalettePreset;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.HistogramPaletteColoring;
import com.shangin.fractal.coloring.ColorStop;
import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.coloring.OrbitTrapColoring;
import com.shangin.fractal.render.FractalData;

import java.util.Objects;

/** Immutable parameters used to construct a coloring strategy. */
public record ColoringSettings(
        PalettePreset palette,
        java.util.List<ColorStop> paletteStops,
        double colorScale,
        double offset,
        boolean histogramColoring,
        OrbitTrap orbitTrap
) {
    public static final double DEFAULT_COLOR_SCALE = SmoothPaletteColoring.DEFAULT_COLOR_SCALE;

    public ColoringSettings {
        Objects.requireNonNull(palette);
        paletteStops = java.util.List.copyOf(paletteStops);
        Objects.requireNonNull(orbitTrap);
        if (paletteStops.size() < 2) {
            throw new IllegalArgumentException("Palette requires at least two color stops");
        }

        if (!Double.isFinite(colorScale) || colorScale <= 0.0) {
            throw new IllegalArgumentException("Color scale must be positive and finite");
        }
        if (!Double.isFinite(offset)) {
            throw new IllegalArgumentException("Color offset must be finite");
        }
    }

    public ColoringSettings(PalettePreset palette) {
        this(palette, palette.stops(), DEFAULT_COLOR_SCALE, 0.0, false, OrbitTrap.NONE);
    }

    public ColoringSettings(PalettePreset palette, double colorScale, double offset) {
        this(palette, palette.stops(), colorScale, offset, false, OrbitTrap.NONE);
    }

    public ColoringSettings(
            PalettePreset palette, double colorScale, double offset, boolean histogramColoring
    ) {
        this(palette, palette.stops(), colorScale, offset, histogramColoring, OrbitTrap.NONE);
    }

    private GradientPalette createPalette() {
        return GradientPalette.cached(paletteStops);
    }

    public ColoringStrategy createStrategy() {
        if (orbitTrap != OrbitTrap.NONE) {
            return new OrbitTrapColoring(createPalette(), offset);
        }
        return new SmoothPaletteColoring(
                createPalette(),
                colorScale,
                offset
        );
    }

    /** Builds the optional second-pass mapping once the whole frame is available. */
    public ColoringStrategy createStrategy(FractalData data) {
        return orbitTrap == OrbitTrap.NONE && histogramColoring
                ? new HistogramPaletteColoring(createPalette(), offset, data)
                : createStrategy();
    }
}
