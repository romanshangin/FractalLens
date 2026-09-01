package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoomIndicatorTest {

    @Test
    void retainsAllAvailableDigitsWithoutScientificNotation() {
        assertEquals("11340491234567.123456789",
                ZoomFormat.format(new BigDecimal("1.1340491234567123456789e+13")));
    }
}
