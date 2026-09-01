package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoomIndicatorTest {

    @Test
    void retainsTwelveSignificantFractionDigitsForDeepZoom() {
        assertEquals("1.134049123457e+13",
                ZoomFormat.format(new BigDecimal("11340491234567")));
    }
}
