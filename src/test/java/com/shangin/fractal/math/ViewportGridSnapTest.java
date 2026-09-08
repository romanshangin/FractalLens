package com.shangin.fractal.math;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ViewportGridSnapTest {
    @Test void scaledExponentNavigationCanSnapOffsetsLargerThanLong() {
        var reference = new Viewport("2", "0", "1e-400");
        for (var requested : new Viewport[]{new Viewport("1", "-1", "1e-400"), new Viewport("3", "1", "1e-400")}) {
            var snapped = requested.snapToPixelGrid(reference, 480, 270);
            var halfPixel = reference.imaginaryUnitsPerPixelExact(270).divide(BigDecimal.TWO);
            assertEquals(0, reference.scaleExact().compareTo(snapped.scaleExact()));
            assertTrue(snapped.center().real().subtract(requested.center().real()).abs().compareTo(halfPixel) <= 0);
            assertTrue(snapped.center().imaginary().subtract(requested.center().imaginary()).abs().compareTo(halfPixel) <= 0);
        }
    }

    @Test void ordinaryIntegerShiftsKeepTheExistingCoordinates() {
        var reference = new Viewport("-0.743643887037151", "0.13182590420533", "0.024");
        var moved = reference.shiftedByPixels(17, -11, 480, 270);
        assertEquals(moved, moved.snapToPixelGrid(reference, 480, 270));
    }
}
