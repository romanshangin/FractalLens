package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.coloring.SmoothPaletteColoring;

/**
 * Integer addressing for the 16-bit phase / 65,536-color lookup.
 * The triangular wave has two affine halves; rounding changes the correction
 * at one threshold per half. Finding those thresholds with the CPU expression
 * preserves its double rounding without shaderFloat64 or rebuilding a lookup.
 * Only these five integers change each animation frame.
 */
public record PaletteOffset(int whole, int ascendingBias, int ascendingLast,
                            int descendingBias, int descendingLast) {
    public static PaletteOffset from(SmoothPaletteColoring coloring) {
        double offset = coloring.offset();
        if (!Double.isFinite(offset) || Math.abs(offset) >= 1_000_000) {
            throw new IllegalArgumentException("Offset outside GPU precision envelope");
        }
        int whole = (int) Math.floor((offset - Math.floor(offset / 2.0) * 2.0) * 32768.0);
        int ascending = index(coloring, -whole);
        int descending = index(coloring, 65535 - whole);
        return new PaletteOffset(whole, ascending, threshold(coloring, whole, ascending, false),
                descending, threshold(coloring, whole, descending, true));
    }

    private static int index(SmoothPaletteColoring coloring, int phase) {
        return GradientPalette.lookupIndex(coloring.palettePositionFromBasePhase(
                SmoothColorLookup.decode(phase & 65535)));
    }

    private static int threshold(SmoothPaletteColoring coloring, int whole, int bias, boolean descending) {
        int lo = 0, hi = 32768;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            int phase = (descending ? 65535 - mid : mid) - whole;
            if (index(coloring, phase) - 2 * mid == bias) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    public int index(int phase) {
        int x = (phase + whole) & 65535;
        if (x < 32768) return 2 * x + ascendingBias - (x > ascendingLast ? 1 : 0);
        int t = 65535 - x;
        return 2 * t + descendingBias - (t > descendingLast ? 1 : 0);
    }
}
