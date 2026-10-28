package com.shangin.fractal.ui;

import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FractalDialogsTest {

    @Test
    void coordinateHintShowsCompactZoom() {
        assertEquals("Move the center without changing zoom. Zoom: 1",
                FractalDialogs.coordinateHint(new BigDecimal("0.96504383940932216428241608952468830960"), false));
        assertEquals("High-precision deep zoom is active. Zoom: ≈1.23 × 10^30",
                FractalDialogs.coordinateHint(new BigDecimal("1234567890123456789012345678901.5"), true));
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
