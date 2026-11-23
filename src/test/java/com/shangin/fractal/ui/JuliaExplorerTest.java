package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JuliaExplorerTest {
    @Test void changingFramesReduceClickSpeedAndQuietFramesGraduallyRestoreIt() {
        var pacing = new JuliaFramePacing();
        int[] colors = new int[64 * 40];
        pacing.reset();
        pacing.observe(colors, 64, 40);
        long initial = pacing.step(40_000_000);
        java.util.Arrays.fill(colors, 0xff555555);
        pacing.observe(colors, 64, 40);
        assertEquals(initial, pacing.step(40_000_000), "A uniform color change has no moving branches");
        for (int i = 0; i < colors.length; i += 2) colors[i] = 0xffffffff;
        pacing.observe(colors, 64, 40);
        long slower = pacing.step(40_000_000);
        assertTrue(slower > 0 && slower < initial);
        pacing.observe(colors, 64, 40);
        long recovering = pacing.step(40_000_000);
        assertTrue(recovering > slower && recovering < initial);
        pacing.reset();
        assertEquals(initial, pacing.step(40_000_000));
    }

    @Test void mapMotionCapsFastFramesAndAcceleratesGraduallyAfterASlowFrame() {
        var pacing = new JuliaFramePacing();
        pacing.reset();
        double distance = 0;
        for (int frame = 0; frame < 100; frame++) {
            distance = pacing.advance(100, 16_000_000);
            assertTrue(distance <= 2.52 + 1e-9);
        }
        assertEquals(2.52, distance, 1e-9);
        double afterStall = pacing.advance(100, 200_000_000);
        double next = pacing.advance(100, 8_000_000);
        assertTrue(next / 0.008 <= afterStall / 0.032 + 700 * 0.008 + 1e-9);
        assertTrue(next < 1);
    }

    @Test void approachingTheCursorClosesTheSameGapAtDifferentFrameRates() {
        var slower = new JuliaFramePacing();
        var faster = new JuliaFramePacing();
        slower.reset();
        faster.reset();

        double remainingAt60 = 1;
        double remainingAt120 = 1;
        for (int i = 0; i < 6; i++) {
            remainingAt60 -= slower.advance(remainingAt60, 16_000_000);
            remainingAt120 -= faster.advance(remainingAt120, 8_000_000);
            remainingAt120 -= faster.advance(remainingAt120, 8_000_000);
        }
        assertEquals(Math.exp(-0.096 / 0.325), remainingAt60, 1e-9);
        assertEquals(remainingAt60, remainingAt120, 1e-9);
    }

    @Test void finalStepsKeepGettingSmallerUntilTheyReachTheTarget() {
        var pacing = new JuliaFramePacing();
        pacing.reset();
        double remaining = 0.11;
        double previous = Double.POSITIVE_INFINITY;
        int frames = 0;
        while (remaining > 0 && frames < 60) {
            double advance = pacing.advance(remaining, 16_000_000);
            assertTrue(advance > 0);
            assertTrue(advance <= previous, "The final frame must not jump farther than the previous one");
            remaining -= advance;
            previous = advance;
            frames++;
        }
        assertTrue(frames > 10);
        assertEquals(0, remaining);
    }

    @Test void mapClampsToTheDrawnPlaneAndInterpolatesBothComponents() {
        var topLeft = JuliaExplorer.point(0, 0);
        var bottomRight = JuliaExplorer.point(JuliaExplorer.WIDTH - 1, JuliaExplorer.HEIGHT - 1);
        assertEquals(topLeft, JuliaExplorer.point(-20, -30));
        assertEquals(bottomRight, JuliaExplorer.point(500, 900));
        assertTrue(topLeft.imaginary() > bottomRight.imaginary());
        assertTrue(bottomRight.real() > topLeft.real());
        var middle = JuliaExplorer.interpolate(topLeft, bottomRight, 0.5);
        assertEquals(-0.75, middle.real(), 1e-12);
        assertEquals(0, middle.imaginary(), 1e-12);
        assertEquals(bottomRight, JuliaExplorer.interpolate(topLeft, bottomRight, 1));
    }
}
