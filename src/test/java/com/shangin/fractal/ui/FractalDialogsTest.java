package com.shangin.fractal.ui;

import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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

    private static final CoordinateText SHOWN = new CoordinateText("-0.75", "0.1", "1.5");

    @Test
    void untouchedFieldsAreComparedAsTrimmedTextAndKeepLiveValues() {
        CoordinateTarget unchanged = CoordinateTarget.parse(SHOWN, " -0.75 ", "0.1\t", " 1.5 ");
        assertTrue(unchanged.unchanged());
        var live = new PreciseComplex("-0.7500000000000000000000000000001", "0.0999999999999999999999");
        assertEquals(live, unchanged.center(live));

        // Equal numbers with different text are edits.
        CoordinateTarget zoom = CoordinateTarget.parse(SHOWN, "-0.75", "0.1", "1.50");
        assertNull(zoom.real());
        assertNull(zoom.imaginary());
        assertEquals(new BigDecimal("1.50"), zoom.zoom());
        assertEquals(live, zoom.center(live));
    }

    @Test
    void eachFieldAndCombinationChangesOnlyWhatWasEdited() {
        var live = new PreciseComplex("-0.75000000000000000000001", "0.10000000000000000000002");
        CoordinateTarget real = CoordinateTarget.parse(SHOWN, "-1e-3", "0.1", "1.5");
        assertEquals(new PreciseComplex(new BigDecimal("-0.001"), live.imaginary()), real.center(live));
        assertNull(real.zoom());

        CoordinateTarget imaginary = CoordinateTarget.parse(SHOWN, "-0.75", "0", "1.5");
        assertEquals(new PreciseComplex(live.real(), BigDecimal.ZERO), imaginary.center(live));
        assertNull(imaginary.zoom());

        CoordinateTarget all = CoordinateTarget.parse(SHOWN, "0.25", "-0.5", "2.5e35");
        assertEquals(new PreciseComplex("0.25", "-0.5"), all.center(live));
        assertEquals(0, new BigDecimal("2.5e35").compareTo(all.zoom()));

        CoordinateTarget centerAndZoom = CoordinateTarget.parse(SHOWN, "0.25", "0.1", "3");
        assertEquals(new PreciseComplex(new BigDecimal("0.25"), live.imaginary()), centerAndZoom.center(live));
        assertEquals(new BigDecimal("3"), centerAndZoom.zoom());
    }

    @Test
    void coordinateValidationChecksOnlyChangedFields() {
        assertEquals(0, CoordinateTarget.invalidField(SHOWN, "-0.75", "0.1", "1.5"));
        assertEquals(0, CoordinateTarget.invalidField(SHOWN, "-0.75", "0", "1e30"));
        assertEquals(1, CoordinateTarget.invalidField(SHOWN, "NaN", "0", "1"));
        assertEquals(2, CoordinateTarget.invalidField(SHOWN, "0", "", "1"));
        for (String zoom : new String[] {"", "zoom", "0.5", "0", "-3"}) {
            assertEquals(3, CoordinateTarget.invalidField(SHOWN, "0", "0", zoom), zoom);
        }
        // An untouched zoom below 1, as after restore or resize, is not revalidated.
        var belowOne = new CoordinateText("0", "0", "0.9");
        assertEquals(0, CoordinateTarget.invalidField(belowOne, "0", "0", " 0.9 "));
        assertTrue(CoordinateTarget.parse(belowOne, "0", "0", "0.9").unchanged());
        assertEquals(0, CoordinateTarget.invalidField(belowOne, "0.5", "0", "0.9"));
        assertEquals(3, CoordinateTarget.invalidField(belowOne, "0", "0", "0.90"));
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
