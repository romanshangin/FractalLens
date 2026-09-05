package com.shangin.fractal.gpu;

import com.shangin.fractal.math.Viewport;
import com.shangin.fractal.render.RenderGrid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MandelbrotPrecisionGateTest {
    @Test
    void packsIntervalsContainingOriginalCoordinatesIncludingUnderflow() {
        for (double value : new double[]{0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, 1e-40, -1e-40,
                Float.MIN_NORMAL, -Float.MIN_NORMAL, 0.1, -0.743643887037151, 4, -4}) {
            float[] input = new float[8];
            MandelbrotPrecisionGate.pack(input, 0, value, -value);
            assertTrue(input[2] <= value && input[3] >= value, "Real interval " + value);
            assertTrue(input[4] <= -value && input[5] >= -value, "Imaginary interval " + value);
            for (int i = 2; i < 6; i++) assertTrue(input[i] == 0 || Math.abs(input[i]) >= Float.MIN_NORMAL);
        }
        assertThrows(IllegalArgumentException.class, () -> MandelbrotPrecisionGate.pack(new float[8], 0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> MandelbrotPrecisionGate.pack(new float[8], 0, 5, 0));
    }

    @Test
    void rejectsUnrepresentableGridBeforePerPixelAcceptance() {
        assertTrue(MandelbrotPrecisionGate.supportsGrid(RenderGrid.from(new Viewport(-0.75, 0, 2.4), 3024, 1964), 3024, 1964));
        assertFalse(MandelbrotPrecisionGate.supportsGrid(RenderGrid.from(new Viewport(-0.743643887037151, 0.13182590420533, 0.00024),
                3024, 1964), 3024, 1964));
        assertFalse(MandelbrotPrecisionGate.supportsGrid(RenderGrid.from(new Viewport("-0.75", "0", "1e-80"),
                384, 256), 384, 256));
    }

    @Test
    void requiresUnambiguousMatchingEscapeAndBoundedSmoothValue() {
        int[] output = new int[12];
        output[0] = 2; output[1] = 1;
        output[2] = Float.floatToRawIntBits(6);
        output[4] = 1; output[5] = 2;
        output[6] = Float.floatToRawIntBits(35.9999f);
        output[7] = Float.floatToRawIntBits(36.0001f);
        assertTrue(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, false));
        output[5] = 3;
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        output[5] = 2; output[4] = 0;
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        output[4] = 1; output[6] = Float.floatToRawIntBits(4);
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        output[6] = Float.floatToRawIntBits(30);
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        output[6] = Float.floatToRawIntBits(35.9999f); output[7] = Float.floatToRawIntBits(Float.NaN);
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
    }

    @Test
    void unescapedCertificateRequiresMatchingBudget() {
        int[] output = new int[12];
        output[0] = 300; output[4] = 2; output[5] = 300;
        assertTrue(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 301, true));
        output[1] = 1;
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
        output[1] = 0; output[2] = Float.floatToRawIntBits(Float.POSITIVE_INFINITY);
        assertFalse(MandelbrotPrecisionGate.accepts(output, 0, 300, true));
    }

    @Test
    void certifiesAndStagesTheSameSmoothValueWithoutRecalculation() {
        int[] output = new int[12];
        output[0] = 2; output[1] = 1;
        output[2] = Float.floatToRawIntBits(6); output[3] = Float.floatToRawIntBits(0.25f);
        output[4] = 1; output[5] = 2;
        output[6] = Float.floatToRawIntBits(36.0624f);
        output[7] = Float.floatToRawIntBits(36.0626f);
        var staging = new MandelbrotStaging();
        assertTrue(MandelbrotPrecisionGate.certify(output, 0, 300, true, staging));
        assertFalse(staging.rejected(0));
        assertTrue(staging.escaped(0));
        assertEquals(2, staging.iterations[0]);
        assertEquals(MandelbrotPrecisionGate.sample(output, 0).smoothIterations(), staging.smoothIterations[0]);

        output[5] = 3;
        assertFalse(MandelbrotPrecisionGate.certify(output, 0, 300, true, staging));
        assertTrue(staging.rejected(0), "A reused staging slot must not retain an earlier certificate");
    }
}
