package com.shangin.fractal.coloring;

import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class GradientPalette implements Palette {

    private static final int LOOKUP_SIZE = 65_536;
    private static final int CACHE_LIMIT = 64;
    private static final Map<List<ColorStop>, GradientPalette> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<ColorStop>, GradientPalette> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private final List<PerceptualStop> stops;
    private final int[] lookup;

    public GradientPalette(List<ColorStop> stops) {
        if (stops.size() < 2) {
            throw new IllegalArgumentException("Palette requires at least two color stops");
        }

        this.stops = stops.stream()
                .sorted(Comparator.comparingDouble(ColorStop::position))
                .map(PerceptualStop::from)
                .toList();

        lookup = createLookup();
    }

    /** Reuses the expensive lookup table while the same immutable stops are recolored. */
    public static synchronized GradientPalette cached(List<ColorStop> stops) {
        List<ColorStop> key = List.copyOf(stops);
        return CACHE.computeIfAbsent(key, GradientPalette::new);
    }

    @Override
    public int color(double position) {
        double clamped = Math.clamp(position, 0.0, 1.0);
        int index = (int) Math.round(clamped * (LOOKUP_SIZE - 1));

        return lookup[index];
    }

    private int[] createLookup() {
        int[] colors = new int[LOOKUP_SIZE];

        for (int index = 0; index < colors.length; index++) {
            double position = (double) index / (colors.length - 1);
            colors[index] = calculateColor(position);
        }

        for (PerceptualStop stop : stops) {
            int index = (int) Math.round(stop.position() * (colors.length - 1));
            colors[index] = calculateColor(stop.position());
        }

        return colors;
    }

    private int calculateColor(double position) {
        double clamped = Math.clamp(position, 0.0, 1.0);

        if (clamped <= stops.getFirst().position()) {
            return stops.getFirst().argb();
        }

        for (int i = 0; i < stops.size() - 1; i++) {
            PerceptualStop left = stops.get(i);
            PerceptualStop right = stops.get(i + 1);

            if (clamped >= left.position() && clamped <= right.position()) {
                double range = right.position() - left.position();
                double t = range == 0.0 ? 0.0 : (clamped - left.position()) / range;

                if (t <= 0.0) {
                    return left.argb();
                }
                if (t >= 1.0) {
                    return right.argb();
                }

                return interpolate(left, right, t);
            }
        }
        return stops.getLast().argb();
    }

    private int interpolate(
            PerceptualStop from,
            PerceptualStop to,
            double t
    ) {
        double lightness = lerp(from.lightness(), to.lightness(), t);
        double greenRed = lerp(from.greenRed(), to.greenRed(), t);
        double blueYellow = lerp(from.blueYellow(), to.blueYellow(), t);
        int alpha = Math.clamp(
                (int) Math.round(lerp(from.alpha(), to.alpha(), t)),
                0,
                255
        );

        double lRoot = lightness + 0.3963377774 * greenRed + 0.2158037573 * blueYellow;
        double mRoot = lightness - 0.1055613458 * greenRed - 0.0638541728 * blueYellow;
        double sRoot = lightness - 0.0894841775 * greenRed - 1.2914855480 * blueYellow;

        double l = lRoot * lRoot * lRoot;
        double m = mRoot * mRoot * mRoot;
        double s = sRoot * sRoot * sRoot;

        double red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

        return alpha << 24
                | toSrgb(red) << 16
                | toSrgb(green) << 8
                | toSrgb(blue);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static double toLinear(int channel) {
        double srgb = channel / 255.0;
        return srgb <= 0.04045
                ? srgb / 12.92
                : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    private static int toSrgb(double linear) {
        double clamped = Math.clamp(linear, 0.0, 1.0);
        double srgb = clamped <= 0.0031308
                ? clamped * 12.92
                : 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;

        return Math.clamp((int) Math.round(srgb * 255.0), 0, 255);
    }

    private record PerceptualStop(
            double position,
            int argb,
            int alpha,
            double lightness,
            double greenRed,
            double blueYellow
    ) {
        static PerceptualStop from(ColorStop stop) {
            int color = stop.color();
            double red = toLinear((color >>> 16) & 0xFF);
            double green = toLinear((color >>> 8) & 0xFF);
            double blue = toLinear(color & 0xFF);

            double l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue;
            double m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue;
            double s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue;

            double lRoot = Math.cbrt(l);
            double mRoot = Math.cbrt(m);
            double sRoot = Math.cbrt(s);

            return new PerceptualStop(
                    stop.position(),
                    color,
                    (color >>> 24) & 0xFF,
                    0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
                    1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
                    0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot
            );
        }
    }
}
