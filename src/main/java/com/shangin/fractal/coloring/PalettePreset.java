package com.shangin.fractal.coloring;

import java.util.List;

public enum PalettePreset {

    OCEAN(
            "Ocean",
            List.of(
                            new ColorStop(0.00, 0xFF020024),
                            new ColorStop(0.30, 0xFF090979),
                            new ColorStop(0.65, 0xFF00A8CC),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    ),

    FIRE(
            "Fire",
            List.of(
                            new ColorStop(0.00, 0xFF100000),
                            new ColorStop(0.30, 0xFF8A0000),
                            new ColorStop(0.60, 0xFFFF6600),
                            new ColorStop(0.85, 0xFFFFCC00),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    ),

    ICE(
            "Ice",
            List.of(
                            new ColorStop(0.00, 0xFF001020),
                            new ColorStop(0.35, 0xFF005080),
                            new ColorStop(0.70, 0xFF66DDEE),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    ),

    PLASMA(
            "Plasma",
            List.of(
                            new ColorStop(0.00, 0xFF150050),
                            new ColorStop(0.30, 0xFF720090),
                            new ColorStop(0.60, 0xFFDD3050),
                            new ColorStop(0.85, 0xFFFFA020),
                            new ColorStop(1.00, 0xFFFFFF80)
            )
    ),

    FOREST(
            "Forest",
            List.of(
                            new ColorStop(0.00, 0xFF001008),
                            new ColorStop(0.35, 0xFF075A30),
                            new ColorStop(0.70, 0xFF70B030),
                            new ColorStop(1.00, 0xFFFFE090)
            )
    ),

    GRAYSCALE(
            "Grayscale",
            List.of(
                            new ColorStop(0.00, 0xFF000000),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    );

    private final String displayName;
    private final List<ColorStop> stops;
    private final Palette palette;

    PalettePreset(
            String displayName,
            List<ColorStop> stops
    ) {
        this.displayName = displayName;
        this.stops = List.copyOf(stops);
        this.palette = new GradientPalette(this.stops);
    }

    public Palette palette() {
        return palette;
    }

    public List<ColorStop> stops() {
        return stops;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
