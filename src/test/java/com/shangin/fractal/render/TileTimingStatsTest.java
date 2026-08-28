package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TileTimingStatsTest {

    @Test
    void maxToMedianRatioShouldCompareSlowestTileWithMedian() {
        TileTimingStats stats = new TileTimingStats(
                3,
                1.0,
                2.0,
                5.0
        );

        assertEquals(2.5, stats.maxToMedianRatio());
    }

    @Test
    void maxToMedianRatioShouldBeZeroWhenMedianIsZero() {
        TileTimingStats stats = new TileTimingStats(
                0,
                0.0,
                0.0,
                0.0
        );

        assertEquals(0.0, stats.maxToMedianRatio());
    }
}
