package com.shangin.fractal.coloring;

import java.util.List;

public enum PalettePreset {

    ICE(
            "Ice",
            List.of(
                            new ColorStop(0.00, 0xFF001020),
                            new ColorStop(0.18, 0xFF003050),
                            new ColorStop(0.35, 0xFF005080),
                            new ColorStop(0.70, 0xFF66DDEE),
                            new ColorStop(0.88, 0xFFB8F0F5),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    ),

    OCEAN(
            "Ocean",
            List.of(
                            new ColorStop(0.00, 0xFF020024),
                            new ColorStop(0.15, 0xFF06104F),
                            new ColorStop(0.30, 0xFF090979),
                            new ColorStop(0.65, 0xFF00A8CC),
                            new ColorStop(0.85, 0xFF70E0E8),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    ),

    FIRE(
            "Fire",
            List.of(
                            new ColorStop(0.00, 0xFF100000),
                            new ColorStop(0.15, 0xFF450010),
                            new ColorStop(0.30, 0xFF8A0000),
                            new ColorStop(0.60, 0xFFFF6600),
                            new ColorStop(0.85, 0xFFFFCC00),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    ),

    PLASMA(
            "Plasma",
            List.of(
                            new ColorStop(0.00, 0xFF150050),
                            new ColorStop(0.15, 0xFF3A0878),
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
                            new ColorStop(0.18, 0xFF023020),
                            new ColorStop(0.35, 0xFF075A30),
                            new ColorStop(0.70, 0xFF70B030),
                            new ColorStop(0.88, 0xFFC8D860),
                            new ColorStop(1.00, 0xFFFFE090)
            )
    ),

    GRAYSCALE(
            "Grayscale",
            List.of(
                            new ColorStop(0.00, 0xFF000000),
                            new ColorStop(0.20, 0xFF202020),
                            new ColorStop(0.45, 0xFF606060),
                            new ColorStop(0.70, 0xFFA0A0A0),
                            new ColorStop(0.88, 0xFFD8D8D8),
                            new ColorStop(1.00, 0xFFFFFFFF)
            )
    ),

    AURORA(
            "Aurora",
            List.of(
                            new ColorStop(0.00, 0xFF04071F),
                            new ColorStop(0.20, 0xFF191B58),
                            new ColorStop(0.40, 0xFF146780),
                            new ColorStop(0.60, 0xFF12B58D),
                            new ColorStop(0.80, 0xFFA4EAAF),
                            new ColorStop(1.00, 0xFFFAFFD6)
            )
    ),

    SUNSET(
            "Sunset",
            List.of(
                            new ColorStop(0.00, 0xFF100725),
                            new ColorStop(0.20, 0xFF49104F),
                            new ColorStop(0.40, 0xFFB82065),
                            new ColorStop(0.60, 0xFFF65B3E),
                            new ColorStop(0.80, 0xFFFFB74D),
                            new ColorStop(1.00, 0xFFFFF7CF)
            )
    ),

    TWILIGHT(
            "Twilight",
            List.of(
                            new ColorStop(0.00, 0xFF04091C),
                            new ColorStop(0.20, 0xFF192A50),
                            new ColorStop(0.40, 0xFF4B438D),
                            new ColorStop(0.60, 0xFFA46BC0),
                            new ColorStop(0.80, 0xFFE6ADD4),
                            new ColorStop(1.00, 0xFFFFF7F0)
            )
    ),

    CORAL(
            "Coral",
            List.of(
                            new ColorStop(0.00, 0xFF03131F),
                            new ColorStop(0.20, 0xFF0B4058),
                            new ColorStop(0.40, 0xFF1695A3),
                            new ColorStop(0.60, 0xFFF56E75),
                            new ColorStop(0.80, 0xFFFFC0A0),
                            new ColorStop(1.00, 0xFFFFF9E6)
            )
    ),

    DESERT(
            "Desert",
            List.of(
                            new ColorStop(0.00, 0xFF160B20),
                            new ColorStop(0.20, 0xFF4D1F3A),
                            new ColorStop(0.40, 0xFFA64D34),
                            new ColorStop(0.60, 0xFFDB8B3D),
                            new ColorStop(0.80, 0xFFF0CF82),
                            new ColorStop(1.00, 0xFFFFF9E0)
            )
    ),

    EMERALD(
            "Emerald",
            List.of(
                            new ColorStop(0.00, 0xFF011016),
                            new ColorStop(0.20, 0xFF053333),
                            new ColorStop(0.40, 0xFF07805F),
                            new ColorStop(0.60, 0xFF26C47A),
                            new ColorStop(0.80, 0xFFAFEFA8),
                            new ColorStop(1.00, 0xFFF8FFE3)
            )
    ),

    AMETHYST(
            "Amethyst",
            List.of(
                            new ColorStop(0.00, 0xFF0A041C),
                            new ColorStop(0.20, 0xFF28124F),
                            new ColorStop(0.40, 0xFF65259E),
                            new ColorStop(0.60, 0xFFB54BC6),
                            new ColorStop(0.80, 0xFFECA6E8),
                            new ColorStop(1.00, 0xFFFFF7FE)
            )
    ),

    COPPER(
            "Copper",
            List.of(
                            new ColorStop(0.00, 0xFF0C060A),
                            new ColorStop(0.20, 0xFF341820),
                            new ColorStop(0.40, 0xFF80352B),
                            new ColorStop(0.60, 0xFFC66C38),
                            new ColorStop(0.80, 0xFFEFB271),
                            new ColorStop(1.00, 0xFFFFF2D5)
            )
    ),

    BLUE_GOLD(
            "Blue & Gold",
            List.of(
                            new ColorStop(0.00, 0xFF000764),
                            new ColorStop(0.16, 0xFF1248B5),
                            new ColorStop(0.36, 0xFF69BDE8),
                            new ColorStop(0.52, 0xFFF5F8D5),
                            new ColorStop(0.64, 0xFFFFD45A),
                            new ColorStop(0.76, 0xFFFF9900),
                            new ColorStop(0.90, 0xFF6A250C),
                            new ColorStop(1.00, 0xFF000764)
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
