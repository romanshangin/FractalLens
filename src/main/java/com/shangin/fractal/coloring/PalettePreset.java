package com.shangin.fractal.coloring;

import java.util.List;

public enum PalettePreset {

    OCEAN(
            "Ocean",
            new GradientPalette(
                    List.of(
                            new ColorStop(0.00, 0xFF020024),
                            new ColorStop(0.30, 0xFF090979),
                            new ColorStop(0.65, 0xFF00A8CC),
                            new ColorStop(1.00, 0xFFFFFFFF)
                    )
            )
    ),

    FIRE(
            "Fire",
            new GradientPalette(
                    List.of(
                            new ColorStop(0.00, 0xFF100000),
                            new ColorStop(0.30, 0xFF8A0000),
                            new ColorStop(0.60, 0xFFFF6600),
                            new ColorStop(0.85, 0xFFFFCC00),
                            new ColorStop(1.00, 0xFFFFFFFF)
                    )
            )
    ),

    ICE(
            "Ice",
            new GradientPalette(
                    List.of(
                            new ColorStop(0.00, 0xFF001020),
                            new ColorStop(0.35, 0xFF005080),
                            new ColorStop(0.70, 0xFF66DDEE),
                            new ColorStop(1.00, 0xFFFFFFFF)
                    )
            )
    ),

    PLASMA(
            "Plasma",
            new GradientPalette(
                    List.of(
                            new ColorStop(0.00, 0xFF150050),
                            new ColorStop(0.30, 0xFF720090),
                            new ColorStop(0.60, 0xFFDD3050),
                            new ColorStop(0.85, 0xFFFFA020),
                            new ColorStop(1.00, 0xFFFFFF80)
                    )
            )
    ),

    FOREST(
            "Forest",
            new GradientPalette(
                    List.of(
                            new ColorStop(0.00, 0xFF001008),
                            new ColorStop(0.35, 0xFF075A30),
                            new ColorStop(0.70, 0xFF70B030),
                            new ColorStop(1.00, 0xFFFFE090)
                    )
            )
    ),

    GRAYSCALE(
            "Grayscale",
            new GradientPalette(
                    List.of(
                            new ColorStop(0.00, 0xFF000000),
                            new ColorStop(1.00, 0xFFFFFFFF)
                    )
            )
    );

    private final String displayName;
    private final Palette palette;

    PalettePreset(
            String displayName,
            Palette palette
    ) {
        this.displayName = displayName;
        this.palette = palette;
    }

    public Palette palette() {
        return palette;
    }

    @Override
    public String toString() {
        return displayName;
    }
}