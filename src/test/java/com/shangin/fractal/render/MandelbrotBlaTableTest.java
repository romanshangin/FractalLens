package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MandelbrotBlaTableTest {

    @Test
    void composesBlocksAndChoosesTheLongestAlignedSkipWithinTheIterationLimit() {
        double[] real = new double[18];
        Arrays.fill(real, 0.5); // A = 1, B = 1 for each synthetic reference step.
        MandelbrotBlaTable table = MandelbrotBlaTable.create(
                real, new double[18], 17, 1e-20, () -> false);

        assertNotNull(table);
        MandelbrotBlaTable.Step longest = table.lookup(1, 0.0, 100);
        assertNotNull(longest);
        assertEquals(16, longest.length());
        assertEquals(1.0, longest.aReal());
        assertEquals(0.0, longest.aImaginary());
        assertEquals(16.0, longest.bReal());
        assertEquals(0.0, longest.bImaginary());
        assertEquals(8, table.lookup(1, 0.0, 15).length());
        assertEquals(8, table.lookup(9, 0.0, 8).length());
        assertNull(table.lookup(1, 0.0, 7));
        assertNull(table.lookup(0, 0.0, 100));
        assertNull(table.lookup(2, 0.0, 100));
        assertNull(table.lookup(17, 0.0, 100));
        assertNull(table.lookup(1, 1.0, 100));
    }

    @Test
    void refusesDeltasOutsideTheRadiusUsedToBuildTheTable() {
        double[] real = new double[18];
        Arrays.fill(real, 0.5);
        MandelbrotBlaTable table = MandelbrotBlaTable.create(
                real, new double[18], 17, 1e-20, () -> false);

        assertNotNull(table);
        assertTrue(table.supportsDelta(1e-20, 0.0));
        assertFalse(table.supportsDelta(1e-20, 1e-20));
        assertNull(MandelbrotBlaTable.create(
                real, new double[18], 17, 1.0, () -> false));
    }

    @Test
    void cancelsConstructionAndDoesNotPublishAPartialTable() {
        double[] real = new double[257];
        Arrays.fill(real, 0.5);
        AtomicInteger checks = new AtomicInteger();

        assertNull(MandelbrotBlaTable.create(real, new double[257], 256, 1e-30,
                () -> checks.incrementAndGet() > 20));
        assertTrue(checks.get() <= 21);
    }

    @Test
    void rejectsNonFiniteMergedCoefficients() {
        double[] real = new double[18];
        Arrays.fill(real, Double.MAX_VALUE);
        assertNull(MandelbrotBlaTable.create(
                real, new double[18], 17, 1e-30, () -> false));
    }

    @Test
    void neverSkipsAcrossANonlinearCriticalPoint() {
        double[] real = new double[18];
        Arrays.fill(real, 0.5);
        real[9] = 0.0;
        MandelbrotBlaTable table = MandelbrotBlaTable.create(
                real, new double[18], 17, 1e-30, () -> false);

        assertNotNull(table);
        assertEquals(8, table.lookup(1, 0.0, 100).length());
        assertNull(table.lookup(9, 0.0, 100));
    }
}
