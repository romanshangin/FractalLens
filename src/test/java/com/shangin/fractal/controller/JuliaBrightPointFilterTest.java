package com.shangin.fractal.controller;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JuliaBrightPointFilterTest {

    @Test
    void dimsAnIsolatedSpotWithoutChangingNeighboringPixelsOrTheSource() {
        int[] source = new int[25];
        Arrays.fill(source, 0xff202020);
        source[12] = 0xffffffff;
        int[] filtered = new int[source.length];

        JuliaBrightPointFilter.apply(source, filtered, 5, 5);

        assertEquals(0xffffffff, source[12]);
        int[] expected = source.clone();
        expected[12] = 0xff303030;
        assertArrayEquals(expected, filtered);
    }

    @Test
    void preservesConnectedBrightDetailsAndImageBorders() {
        int[] source = new int[25];
        Arrays.fill(source, 0xff202020);
        source[4] = source[6] = source[12] = 0xffffffff;
        source[17] = 0xfff0f0f0;
        int[] filtered = new int[source.length];

        JuliaBrightPointFilter.apply(source, filtered, 5, 5);

        assertArrayEquals(source, filtered);
    }
}
