package com.shangin.fractal.ui;

import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractalDialogsTest {

    @Test
    void coordinateHintMentionsZoomAndDeepZoomStatus() {
        assertEquals("Enter the center and the zoom relative to the initial view.",
                FractalDialogs.coordinateHint(false));
        assertEquals("Enter the center and the zoom relative to the initial view. "
                + "High-precision deep zoom is active.", FractalDialogs.coordinateHint(true));
    }

    @Test
    void coordinateTargetKeepsExactCurrentScaleWhenZoomIsUnchanged() {
        BigDecimal current = new BigDecimal("0.96504383940932216428241608952468830960");
        String text = CoordinateTarget.zoomText(current);
        assertEquals("0.9650438394093221642824160895246883096", text);

        CoordinateTarget unchanged = CoordinateTarget.parse(" -0.75 ", "0.1", " " + text + " ", current);
        assertEquals(new PreciseComplex("-0.75", "0.1"), unchanged.center());
        assertNull(unchanged.zoom());

        CoordinateTarget zoomed = CoordinateTarget.parse("-0.75", "0.1", "1.5e30", current);
        assertEquals(0, new BigDecimal("1.5E30").compareTo(zoomed.zoom()));
        assertEquals("1000000000000000000000000000000", CoordinateTarget.zoomText(new BigDecimal("1E+30")));
    }

    @Test
    void coordinateValidationNamesTheFieldToCorrect() {
        Predicate<CoordinateTarget> any = target -> true;
        BigDecimal current = new BigDecimal("2");
        assertEquals(0, FractalDialogs.invalidCoordinateField("-0.75", "0", "1e30", current, any));
        assertEquals(1, FractalDialogs.invalidCoordinateField("NaN", "0", "1", current, any));
        assertEquals(2, FractalDialogs.invalidCoordinateField("0", "", "1", current, any));
        for (String zoom : new String[] {"", "zoom", "0.5", "0", "-3"}) {
            assertEquals(3, FractalDialogs.invalidCoordinateField("0", "0", zoom, current, any), zoom);
        }
        // A current zoom below 1 after a resize stays acceptable while unchanged.
        assertEquals(0, FractalDialogs.invalidCoordinateField("0", "0", "0.9", new BigDecimal("0.90"), any));
        assertEquals(3, FractalDialogs.invalidCoordinateField("0", "0", "1e30", current,
                target -> target.zoom() == null));
        assertEquals(0, FractalDialogs.invalidCoordinateField("0.1", "0", "2.0", current,
                target -> target.zoom() == null));
    }

    @Test
    void helpExplainsTheRenderStatusBadgeAndRenderSettings() {
        String help = FractalDialogs.HELP_TEXT;
        for (String expected : new String[] {
                "\n\nRender status\n\n", "\n\nRender Settings\n\n",
                "Standard (blue dot): standard-precision rendering.",
                "Deep Zoom (coral dot): high-precision rendering for deep magnification",
                "Waiting:", "Rendering N%:", "including any antialiasing pass", "Complete and a time:",
                "Interrupted:", "Failed:", "PNG export", "File → Settings…"}) {
            assertTrue(help.contains(expected), () -> "Help is missing: " + expected);
        }
        for (InteractiveRenderMode mode : InteractiveRenderMode.values()) {
            assertTrue(help.contains("\n" + mode + ": "), () -> "Help does not explain " + mode);
        }
    }
}
