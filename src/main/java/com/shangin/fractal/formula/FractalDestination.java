package com.shangin.fractal.formula;

import com.shangin.fractal.math.Viewport;
import java.util.List;

/** Curated views. Scale is the vertical complex-plane span, not a renderer-specific zoom. */
public record FractalDestination(String name, Viewport viewport) {
    private static FractalDestination point(String name, String real, String imaginary, String span) {
        return new FractalDestination(name, new Viewport(real, imaginary, span));
    }

    /** Sources and framing decisions are documented in docs/FRACTAL_DESTINATIONS.md. */
    public static List<FractalDestination> forPreset(FractalPreset preset) {
        return switch (preset) {
            case MANDELBROT -> List.of(
                    point("Seahorse Valley", "-0.75", "0.1", "0.15"),
                    point("Elephant Valley", "0.271787", "-0.005557", "0.0021"),
                    point("Seahorse", "-0.741444", "0.168525", "0.016"));
            case JULIA -> List.of(
                    point("Dragon — Lower Branch", "0", "-0.6", "0.4"),
                    point("Dragon — Spiral", "-0.1", "-0.45", "0.14"),
                    point("Dragon — Spiral Detail", "-0.035", "-0.493", "0.014"));
            case MULTIBROT_CUBIC -> List.of(
                    point("Right Cusp", "0.3849001794597505", "0", "0.12"),
                    point("Upper Period-2 Island", "0", "1", "0.5"),
                    point("Upper Antenna", "0", "1.08", "0.08"));
            case BURNING_SHIP -> List.of(
                    point("Little Ship", "-1.75", "0.035", "0.09"),
                    point("Ship Detail", "-1.7443359375", "0.017451171875", "0.008"),
                    point("Coastal Ridges", "-0.75", "1.15", "0.3"));
            case TRICORN -> List.of(
                    point("Western Branch", "-1.25", "0", "0.625"),
                    point("Western Miniature", "-1.756", "0", "0.1"),
                    point("Antenna Detail", "-1.9412", "0", "0.008333333333333333"),
                    point("Off-axis Branch", "-0.85", "0.145", "0.08333333333333333"));
        };
    }
}
