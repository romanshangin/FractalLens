package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoomIndicatorTest {

    @Test
    void displaysZoomAsARoundedInteger() {
        assertEquals("11340491234567",
                ZoomFormat.format(new BigDecimal("1.1340491234567123456789e+13")));
        assertEquals("2", ZoomFormat.format(new BigDecimal("1.5")));
    }

    @Test
    void retainsTheCompleteIntegerWhileItFits() {
        assertEquals("3362436547623629823456075193684",
                ZoomFormat.format(
                        new BigDecimal("3362436547623629823456075193684"),
                        value -> value.length() <= 31));
    }

    @Test
    void usesTheMaximumScientificPrecisionThatFits() {
        assertEquals("3.362437E+30",
                ZoomFormat.format(
                        new BigDecimal("3362436547623629823456075193684"),
                        value -> value.length() <= 12));
    }

    @Test
    void keepsRequestedSignificantZerosWhenScientificRoundingCarries() {
        assertEquals("1.00E+8",
                ZoomFormat.format(new BigDecimal("99950000"), value -> value.length() <= 7));
    }
}
