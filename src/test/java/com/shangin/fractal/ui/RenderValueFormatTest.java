package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderValueFormatTest {
    @Test
    void showsWholeValuesBelowOneMillion() {
        assertEquals("999999", RenderValueFormat.zoom(new BigDecimal("999999.4")));
        assertEquals("999999", RenderValueFormat.iterations(999_999));
    }

    @Test
    void showsLargeValuesAsPowersOfTenAndMarksRounding() {
        assertEquals("1 × 10^6", RenderValueFormat.zoom(new BigDecimal("999999.5")));
        assertEquals("≈1.23 × 10^12", RenderValueFormat.zoom(new BigDecimal("1234567890123")));
        assertEquals("1 × 10^6", RenderValueFormat.iterations(1_000_000));
        assertEquals("≈2.15 × 10^9", RenderValueFormat.iterations(Integer.MAX_VALUE));
        assertEquals("1 × 10^1000", RenderValueFormat.zoom(new BigDecimal("1E+1000")));
        assertEquals("1 × 10^100000", RenderValueFormat.zoom(new BigDecimal("1E+100000")));
    }

    @Test
    void reservesTheWidestTextForProgressAndDurations() {
        String reserved = "Standard · Rendering 000%";
        assertEquals(reserved, RenderValueFormat.widestText("Standard · Rendering 0%"));
        assertEquals(reserved, RenderValueFormat.widestText("Standard · Rendering 42%"));
        assertEquals(reserved, RenderValueFormat.widestText("Standard · Rendering 100%"));
        assertEquals("Deep Zoom · Complete 0.00 s · Export PNG 000%",
                RenderValueFormat.widestText("Deep Zoom · Complete 7.51 s · Export PNG 5%"));
        assertEquals("Standard · Waiting", RenderValueFormat.widestText("Standard · Waiting"));
    }
}
