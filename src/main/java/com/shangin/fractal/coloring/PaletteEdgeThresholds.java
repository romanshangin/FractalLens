package com.shangin.fractal.coloring;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Base-phase differences that select smooth-palette AA candidates without looking at
 * displayed colors.
 *
 * <p>Smooth coloring maps base phase to palette position through a triangle wave of
 * slope one, so two phases within cyclic distance {@code d} show palette colors from one
 * position interval no longer than {@code d}. A palette whose every such interval keeps
 * each linear-light channel within the color contrast threshold can never show an edge
 * there, whatever the offset.
 *
 * <p>Every palette at least as smooth as the built-in ones shares {@link #sharedThreshold},
 * so switching among them keeps one candidate set. A steeper palette, such as an edited
 * gradient with a sharp stop, gets the smaller threshold its own gradient needs, bounded
 * below so that a discontinuous stop cannot turn the whole frame into candidates.
 */
public final class PaletteEdgeThresholds {

    /** Linear-light channel contrast that marks an edge in standard AA. */
    public static final double DIRECT_CONTRAST = 0.10;
    /** Linear-light channel contrast that marks an edge in deep-zoom AA. */
    public static final double DEEP_CONTRAST = 0.20;
    /**
     * Upper bound on the contrast slope of every built-in palette per unit of palette
     * position. The steepest measured gradient is about 10.3 (Fire near white).
     */
    public static final double MAX_PRESET_CONTRAST_SLOPE = 11.0;
    /**
     * The smallest threshold, as a fraction of the shared one. It covers a black-to-white
     * stop about 0.01 of the palette wide; a sharper stop gets this threshold and may lose
     * AA where neighboring phases straddle it closely, instead of supersampling most of the
     * frame (about 14% of an overview and 23% of a seahorse frame are candidates here).
     */
    public static final double MIN_THRESHOLD_FRACTION = 1.0 / 32;

    private static final int LEVELS = 65_536;
    // One index of palette lookup rounding at either end, plus 16-bit phase quantization.
    private static final int QUANTIZATION_SLACK = 3;
    private static final double[] SRGB_TO_LINEAR = createLinearLookup();
    private static final Map<Palette, double[]> THRESHOLDS = new WeakHashMap<>();

    private PaletteEdgeThresholds() {
    }

    /** The base-phase difference shared by all palettes at least as smooth as the built-in ones. */
    public static double sharedThreshold(boolean deepZoom) {
        return contrast(deepZoom) / MAX_PRESET_CONTRAST_SLOPE;
    }

    /** The base-phase difference above which {@code palette} may show an AA edge. */
    public static double phaseThreshold(Palette palette, boolean deepZoom) {
        double[] thresholds;
        synchronized (THRESHOLDS) {
            thresholds = THRESHOLDS.get(palette);
        }
        if (thresholds == null) {
            thresholds = new double[] {calculate(palette, false), calculate(palette, true)};
            synchronized (THRESHOLDS) {
                THRESHOLDS.put(palette, thresholds);
            }
        }
        return thresholds[deepZoom ? 1 : 0];
    }

    private static double contrast(boolean deepZoom) {
        return deepZoom ? DEEP_CONTRAST : DIRECT_CONTRAST;
    }

    private static double calculate(Palette palette, boolean deepZoom) {
        double shared = sharedThreshold(deepZoom);
        double minimum = shared * MIN_THRESHOLD_FRACTION;
        int maxSteps = LEVELS - 1;
        int needed = (int) Math.ceil(shared * maxSteps) + QUANTIZATION_SLACK;
        double[][] channels = linearChannels(palette);
        double contrast = contrast(deepZoom);
        if (fitsWindow(channels, needed, contrast)) {
            return shared;
        }
        int low = 0;
        int high = needed;
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (fitsWindow(channels, middle, contrast)) low = middle;
            else high = middle;
        }
        return Math.max(minimum, (double) (low - QUANTIZATION_SLACK) / maxSteps);
    }

    private static double[][] linearChannels(Palette palette) {
        double[][] channels = new double[3][LEVELS];
        for (int index = 0; index < LEVELS; index++) {
            int color = palette.color((double) index / (LEVELS - 1));
            channels[0][index] = SRGB_TO_LINEAR[color >>> 16 & 0xff];
            channels[1][index] = SRGB_TO_LINEAR[color >>> 8 & 0xff];
            channels[2][index] = SRGB_TO_LINEAR[color & 0xff];
        }
        return channels;
    }

    /** True when every run of {@code span + 1} lookup entries stays within {@code contrast} per channel. */
    private static boolean fitsWindow(double[][] channels, int span, double contrast) {
        if (span <= 0) return true;
        for (double[] channel : channels) {
            int[] maxQueue = new int[LEVELS];
            int[] minQueue = new int[LEVELS];
            int maxHead = 0, maxTail = 0, minHead = 0, minTail = 0;
            for (int index = 0; index < LEVELS; index++) {
                while (maxTail > maxHead && channel[maxQueue[maxTail - 1]] <= channel[index]) maxTail--;
                maxQueue[maxTail++] = index;
                while (minTail > minHead && channel[minQueue[minTail - 1]] >= channel[index]) minTail--;
                minQueue[minTail++] = index;
                int start = index - span;
                if (maxQueue[maxHead] < start) maxHead++;
                if (minQueue[minHead] < start) minHead++;
                if (channel[maxQueue[maxHead]] - channel[minQueue[minHead]] > contrast) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double[] createLinearLookup() {
        double[] lookup = new double[256];
        for (int channel = 0; channel < lookup.length; channel++) {
            double srgb = channel / 255.0;
            lookup[channel] = srgb <= 0.04045
                    ? srgb / 12.92
                    : Math.pow((srgb + 0.055) / 1.055, 2.4);
        }
        return lookup;
    }
}
