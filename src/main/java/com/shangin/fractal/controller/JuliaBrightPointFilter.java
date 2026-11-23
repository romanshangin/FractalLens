package com.shangin.fractal.controller;

/** Dims isolated near-white pixels in the animated Julia preview. */
final class JuliaBrightPointFilter {
    private static final int WHITE_CHANNEL_THRESHOLD = 224;
    private static final int ISOLATION_CONTRAST = 32;
    private static final int RETAINED_CONTRAST = 16;

    private JuliaBrightPointFilter() {}

    static void apply(int[] source, int[] destination, int width, int height) {
        // Read the original neighborhood even when another pixel has already been dimmed.
        System.arraycopy(source, 0, destination, 0, source.length);
        for (int y = 1; y < height - 1; y++) {
            int end = y * width + width - 1;
            for (int index = y * width + 1; index < end; index++) {
                int color = source[index];
                int red = (color >>> 16) & 255;
                int green = (color >>> 8) & 255;
                int blue = color & 255;
                if (red < WHITE_CHANNEL_THRESHOLD || green < WHITE_CHANNEL_THRESHOLD
                        || blue < WHITE_CHANNEL_THRESHOLD) continue;

                int center = luminance(color);
                int brightestNeighbor = luminance(source[index - width - 1]);
                brightestNeighbor = Math.max(brightestNeighbor, luminance(source[index - width]));
                brightestNeighbor = Math.max(brightestNeighbor, luminance(source[index - width + 1]));
                brightestNeighbor = Math.max(brightestNeighbor, luminance(source[index - 1]));
                brightestNeighbor = Math.max(brightestNeighbor, luminance(source[index + 1]));
                brightestNeighbor = Math.max(brightestNeighbor, luminance(source[index + width - 1]));
                brightestNeighbor = Math.max(brightestNeighbor, luminance(source[index + width]));
                brightestNeighbor = Math.max(brightestNeighbor, luminance(source[index + width + 1]));
                if (center - brightestNeighbor < ISOLATION_CONTRAST) continue;

                int limit = brightestNeighbor + RETAINED_CONTRAST;
                destination[index] = (color & 0xff000000)
                        | (red * limit / center << 16)
                        | (green * limit / center << 8)
                        | (blue * limit / center);
            }
        }
    }

    private static int luminance(int color) {
        return (54 * ((color >>> 16) & 255) + 183 * ((color >>> 8) & 255) + 19 * (color & 255)) >>> 8;
    }
}
