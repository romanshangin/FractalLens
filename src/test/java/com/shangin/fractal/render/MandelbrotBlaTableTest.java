package com.shangin.fractal.render;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
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

    @Test
    void neverSkipsTheEscapeTestWhereTheReferenceIsOnTheRadius() {
        // A block does not test escape at its iterations, where a pixel next to a reference on
        // the radius may escape.
        double[] real = new double[18];
        Arrays.fill(real, 0.5);
        real[9] = -2.0;
        MandelbrotBlaTable table = MandelbrotBlaTable.create(
                real, new double[18], 17, 1e-30, () -> false);

        assertNotNull(table);
        assertEquals(8, table.lookup(1, 0.0, 100).length());
        assertNull(table.lookup(9, 0.0, 100));

        // The orbit of c = -2 stays on the radius.
        double[] tip = new double[18];
        Arrays.fill(tip, 2.0);
        tip[1] = -2.0;
        assertNull(MandelbrotBlaTable.create(tip, new double[18], 17, 1e-30, () -> false));
    }

    @Test
    void errorFactorsBoundEveryBlockAgainstExactPerturbation() {
        MathContext context = new MathContext(80);
        // Inside the main cardioid with a fixed-point multiplier of modulus 0.985: a slowly
        // converging spiral orbit.
        BigDecimal cReal = new BigDecimal("-0.5");
        BigDecimal cImaginary = new BigDecimal("0.5");
        int iterations = 300;
        BigDecimal[] exactReal = new BigDecimal[iterations + 1];
        BigDecimal[] exactImaginary = new BigDecimal[iterations + 1];
        double[] real = new double[iterations + 1];
        double[] imaginary = new double[iterations + 1];
        exactReal[0] = BigDecimal.ZERO;
        exactImaginary[0] = BigDecimal.ZERO;
        for (int n = 0; n < iterations; n++) {
            BigDecimal zr = exactReal[n];
            BigDecimal zi = exactImaginary[n];
            exactReal[n + 1] = zr.multiply(zr).subtract(zi.multiply(zi)).add(cReal).round(context);
            exactImaginary[n + 1] = zr.multiply(zi).multiply(BigDecimal.TWO).add(cImaginary).round(context);
            real[n + 1] = exactReal[n + 1].doubleValue();
            imaginary[n + 1] = exactImaginary[n + 1].doubleValue();
        }
        double maximumDeltaC = 1e-24;
        MandelbrotBlaTable table = MandelbrotBlaTable.create(
                real, imaginary, iterations, maximumDeltaC, () -> false);
        assertNotNull(table);

        int checkedBlocks = 0;
        for (int start = 1; start < iterations; start += 8) {
            for (int limit = iterations - start; limit >= 8; limit = Math.min(limit - 1, limit / 2)) {
                MandelbrotBlaTable.Step block = table.lookup(start, 0.0, limit);
                if (block == null) break;
                double radius = Math.sqrt(block.radiusSquared());
                for (double angle : new double[]{0.3, 2.1, 4.4}) {
                    double deltaReal = 0.9 * radius * Math.cos(angle);
                    double deltaImaginary = 0.9 * radius * Math.sin(angle);
                    double deltaCReal = maximumDeltaC * Math.cos(angle * 1.7);
                    double deltaCImaginary = maximumDeltaC * Math.sin(angle * 1.7);
                    BigDecimal dr = new BigDecimal(deltaReal);
                    BigDecimal di = new BigDecimal(deltaImaginary);
                    BigDecimal dcr = new BigDecimal(deltaCReal);
                    BigDecimal dci = new BigDecimal(deltaCImaginary);
                    // Exact perturbation from the exact reference.
                    BigDecimal pr = dr;
                    BigDecimal pi = di;
                    for (int n = start; n < start + block.length(); n++) {
                        BigDecimal zr = exactReal[n];
                        BigDecimal zi = exactImaginary[n];
                        BigDecimal nextReal = BigDecimal.TWO.multiply(zr.multiply(pr).subtract(zi.multiply(pi)))
                                .add(pr.multiply(pr)).subtract(pi.multiply(pi)).add(dcr).round(context);
                        BigDecimal nextImaginary = BigDecimal.TWO.multiply(zr.multiply(pi).add(zi.multiply(pr)))
                                .add(BigDecimal.TWO.multiply(pr).multiply(pi)).add(dci).round(context);
                        pr = nextReal;
                        pi = nextImaginary;
                    }
                    // The block with exact arithmetic on its stored coefficients.
                    BigDecimal ar = new BigDecimal(block.aReal());
                    BigDecimal ai = new BigDecimal(block.aImaginary());
                    BigDecimal br = new BigDecimal(block.bReal());
                    BigDecimal bi = new BigDecimal(block.bImaginary());
                    BigDecimal linearReal = ar.multiply(dr).subtract(ai.multiply(di))
                            .add(br.multiply(dcr)).subtract(bi.multiply(dci));
                    BigDecimal linearImaginary = ar.multiply(di).add(ai.multiply(dr))
                            .add(br.multiply(dci)).add(bi.multiply(dcr));
                    double error = Math.hypot(linearReal.subtract(pr).doubleValue(),
                            linearImaginary.subtract(pi).doubleValue());
                    double bound = block.deltaErrorFactor() * Math.hypot(deltaReal, deltaImaginary)
                            + block.deltaCErrorFactor() * Math.hypot(deltaCReal, deltaCImaginary);
                    assertTrue(error <= bound, "block " + start + "+" + block.length()
                            + ": error " + error + " exceeds bound " + bound);
                }
                checkedBlocks++;
            }
        }
        assertTrue(checkedBlocks > 50, "checked " + checkedBlocks + " blocks");
    }
}
