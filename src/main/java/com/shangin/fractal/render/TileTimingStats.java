package com.shangin.fractal.render;

public record TileTimingStats(
        int tileCount,
        double minMs,
        double medianMs,
        double maxMs
) {

    public double maxToMedianRatio() {
        if (medianMs == 0.0) {
            return 0.0;
        }

        return maxMs / medianMs;
    }
}