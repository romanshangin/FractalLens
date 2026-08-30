package com.shangin.fractal.coloring;

/** Per-animation-frame lookup for quantized palette-independent smooth phases. */
public final class SmoothColorLookup {

    public static final int PHASE_LEVELS = 1 << 16;
    private static final double[] SRGB_TO_LINEAR = createLinearLookup();

    private final int[] colors = new int[PHASE_LEVELS];
    private final float[] linearRed = new float[PHASE_LEVELS];
    private final float[] linearGreen = new float[PHASE_LEVELS];
    private final float[] linearBlue = new float[PHASE_LEVELS];

    public SmoothColorLookup() {}

    public SmoothColorLookup(SmoothPaletteColoring coloring) {
        update(coloring);
    }

    public void update(SmoothPaletteColoring coloring) {
        for (int code = 0; code < PHASE_LEVELS; code++) {
            int color = coloring.colorFromBasePhase(decode(code));
            colors[code] = color;
            linearRed[code] = (float) SRGB_TO_LINEAR[color >>> 16 & 0xff];
            linearGreen[code] = (float) SRGB_TO_LINEAR[color >>> 8 & 0xff];
            linearBlue[code] = (float) SRGB_TO_LINEAR[color & 0xff];
        }
    }

    public int color(short phase) {
        return colors[Short.toUnsignedInt(phase)];
    }

    public float linearRed(short phase) {
        return linearRed[Short.toUnsignedInt(phase)];
    }

    public float linearGreen(short phase) {
        return linearGreen[Short.toUnsignedInt(phase)];
    }

    public float linearBlue(short phase) {
        return linearBlue[Short.toUnsignedInt(phase)];
    }

    public static short encode(double phase) {
        return (short) Math.clamp(
                (int) Math.round(phase * PHASE_LEVELS / 2.0),
                0,
                PHASE_LEVELS - 1);
    }

    public static double decode(int code) {
        return code * 2.0 / PHASE_LEVELS;
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
