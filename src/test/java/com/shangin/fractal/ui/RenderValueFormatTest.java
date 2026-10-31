package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderValueFormatTest {
    @Test
    void showsIterationsWholeBelowOneMillionAndAsPowersOfTenAbove() {
        assertEquals("999999", RenderValueFormat.iterations(999_999));
        assertEquals("1 × 10^6", RenderValueFormat.iterations(1_000_000));
        assertEquals("≈2.15 × 10^9", RenderValueFormat.iterations(Integer.MAX_VALUE));
    }

    @Test
    void showsZoomWithTwelveSignificantDigitsAndScientificFromOneTrillion() {
        assertEquals("1", RenderValueFormat.zoom(BigDecimal.ONE));
        assertEquals("1.25", RenderValueFormat.zoom(new BigDecimal("1.2500")));
        assertEquals("999999.4", RenderValueFormat.zoom(new BigDecimal("999999.4")));
        assertEquals("1234567.89012", RenderValueFormat.zoom(new BigDecimal("1234567.890123456")));
        assertEquals("999999999999", RenderValueFormat.zoom(new BigDecimal("999999999999.4")));
        // The notation is chosen after rounding.
        assertEquals("1E+12", RenderValueFormat.zoom(new BigDecimal("999999999999.5")));
        assertEquals("1E+12", RenderValueFormat.zoom(new BigDecimal("1E12")));
        assertEquals("1.23456789012E+12", RenderValueFormat.zoom(new BigDecimal("1234567890123")));
        assertEquals("2.71828182846E+80",
                RenderValueFormat.zoom(new BigDecimal("2.718281828459045235360287E80")));
        assertEquals("1E+100000", RenderValueFormat.zoom(new BigDecimal("1E+100000")));
    }

    @Test
    void zoomTextParsesBackWithinFiveTimesTenToTheMinusTwelve() {
        BigDecimal bound = new BigDecimal("5E-12");
        for (String value : new String[] {"1.0000000000004999", "3.14159265358979323846", "999999999999.5",
                "1234567890123.456", "7.77777777777777777E+300", "0.333333333333333333", "0.96504383940932216"}) {
            BigDecimal zoom = new BigDecimal(value);
            BigDecimal parsed = new BigDecimal(RenderValueFormat.zoom(zoom));
            BigDecimal relative = parsed.subtract(zoom).abs().divide(zoom, java.math.MathContext.DECIMAL64);
            assertTrue(relative.compareTo(bound) <= 0, value + " → " + parsed);
        }
    }

    @Test
    void zoomBelowOneIsNeverShownAsOne() {
        assertEquals("0.5", RenderValueFormat.zoom(new BigDecimal("0.5")));
        assertEquals("0.965043839409", RenderValueFormat.zoom(new BigDecimal("0.96504383940932216428")));
        assertEquals("0.999999999999", RenderValueFormat.zoom(new BigDecimal("0.99999999999999")));
        assertEquals("1", RenderValueFormat.zoom(new BigDecimal("1.0000000000001")));
    }

    @Test
    void coordinatesRoundToAThousandthOfThePixelStepWithoutNegativeZero() {
        // 0.001 of a 3.5 pixel step is 0.0035, so values round to thousandths.
        BigDecimal step = new BigDecimal("3.5");
        assertEquals("-0.75", RenderValueFormat.coordinate(new BigDecimal("-0.75"), step));
        assertEquals("-0.743", RenderValueFormat.coordinate(new BigDecimal("-0.7434"), step));
        assertEquals("0", RenderValueFormat.coordinate(BigDecimal.ZERO, step));
        assertEquals("0", RenderValueFormat.coordinate(new BigDecimal("-0.0004"), step));
        assertEquals("0", RenderValueFormat.coordinate(new BigDecimal("0E-40"), step));
        assertEquals("-0.001", RenderValueFormat.coordinate(new BigDecimal("-0.0005000001"), step));
        // Half-even at the rounding digit.
        assertEquals("0.002", RenderValueFormat.coordinate(new BigDecimal("0.0025"), step));
        // Steps of exactly a power of ten use that power: 0.001 of 1e-3 is 1e-6.
        assertEquals("0.123456", RenderValueFormat.coordinate(new BigDecimal("0.1234565"), new BigDecimal("0.001")));
        assertEquals("0.123458", RenderValueFormat.coordinate(new BigDecimal("0.1234575"), new BigDecimal("0.001")));
        // Coarse steps round to whole tens without exponent notation.
        assertEquals("120", RenderValueFormat.coordinate(new BigDecimal("123.4"), new BigDecimal("50000")));
        String deep = RenderValueFormat.coordinate(
                new BigDecimal("-0.74364388703715100000000000000000000000000000001234567"), new BigDecimal("1E-45"));
        assertEquals("-0.743643887037151000000000000000000000000000000012", deep);
        assertEquals("1.000000000000000000000000000000000000000000000001", RenderValueFormat.coordinate(
                new BigDecimal("1.0000000000000000000000000000000000000000000000012"), new BigDecimal("1E-45")));
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
