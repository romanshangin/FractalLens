package com.shangin.fractal.render;

/** Conservative planning estimate for preview frame/display storage, not a process RSS limit. */
final class WidePreviewMemory {
    private WidePreviewMemory() {}

    static long estimate(long width, long height, boolean orbitTrap) {
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) return Long.MAX_VALUE;
        long samples = width * height;
        // Two CPU sample planes during reuse, rounded-up masks, and ten ARGB raster allowances:
        // displayed/in-flight/queued/being-colored snapshots, upload, old/new images and texture allowance.
        long perSample = 2L * (Integer.BYTES + Double.BYTES + 1 + (orbitTrap ? Double.BYTES : 0))
                + 2 + 10L * Integer.BYTES;
        if (samples > Integer.MAX_VALUE) return Long.MAX_VALUE;
        // Axis storage includes deep decimal/scaled coordinates up to the preview precision limit.
        long axes = (width + height) * 1024L;
        long tiles = ((width + 31) / 32) * ((height + 31) / 32) * 1024L;
        return 4L * 1024 * 1024 + samples * perSample + axes + tiles;
    }
}
