package com.shangin.fractal.coloring;

import java.util.Comparator;
import java.util.List;

public class GradientPalette implements Palette {

    private final List<ColorStop> stops;

    public GradientPalette(List<ColorStop> stops) {
        if (stops.size() < 2) {
            throw new IllegalArgumentException("Palette requires at least two color stops");
        }

        this.stops = stops.stream().sorted(Comparator.comparingDouble(ColorStop::position)).toList();
    }

    @Override
    public int color(double position) {
        double clamped = Math.clamp(position, 0.0, 1.0);

        for (int i = 0; i < stops.size() - 1; i++) {
            ColorStop left = stops.get(i);
            ColorStop right = stops.get(i + 1);

            if (clamped >= left.position() && clamped <= right.position()) {
                double range = right.position() - left.position();
                double t = range == 0.0 ? 0.0 : (clamped - left.position()) / range;
                return interpolate(left.color(), right.color(), t);
            }
        }
        return stops.getLast().color();
    }

    private int interpolate(
            int from,
            int to,
            double t
    ) {
        int r1 = (from >> 16) & 0xFF;
        int g1 = (from >> 8) & 0xFF;
        int b1 = from & 0xFF;

        int r2 = (to >> 16) & 0xFF;
        int g2 = (to >> 8) & 0xFF;
        int b2 = to & 0xFF;

        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);

        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
