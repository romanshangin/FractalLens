package com.shangin.fractal.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JuliaExplorerTest {
    private static final long FRAME_120_HZ = 8_333_333;

    @Test void shortAndLongMovesCruiseAtTheSameSpeed() {
        for (double distance : new double[] {30, 72, 300}) {
            var pacing = new JuliaFramePacing();
            pacing.reset();
            double remaining = distance;
            for (int i = 0; i < 7; i++) remaining -= pacing.advance(remaining, FRAME_120_HZ);
            assertEquals(JuliaFramePacing.SPEED * FRAME_120_HZ * 1e-9, pacing.advance(remaining, FRAME_120_HZ), 1e-12,
                    "Cruise speed must not depend on the distance " + distance);
        }
    }

    @Test void travelTimeGrowsInProportionToTheDistance() {
        double accelerationSeconds = JuliaFramePacing.SPEED / JuliaFramePacing.ACCELERATION;
        for (double distance : new double[] {72, 144, 300}) {
            double seconds = arrivalFrames(distance, FRAME_120_HZ) * FRAME_120_HZ * 1e-9;
            assertEquals(distance / JuliaFramePacing.SPEED + accelerationSeconds, seconds, 0.02,
                    "Travel time for " + distance + " points");
        }
    }

    @Test void travelTimeDoesNotDependOnTheFrameRate() {
        double at60 = arrivalFrames(100, 16_666_667) * 16_666_667e-9;
        double at120 = arrivalFrames(100, FRAME_120_HZ) * FRAME_120_HZ * 1e-9;
        assertEquals(at60, at120, 0.017);
    }

    @Test void motionAcceleratesGraduallyAndBrakesWithoutAFinalJump() {
        var pacing = new JuliaFramePacing();
        pacing.reset();
        double cruise = JuliaFramePacing.SPEED * FRAME_120_HZ * 1e-9;
        double remaining = 40;
        double previous = 0;
        boolean braking = false;
        int frames = 0;
        while (remaining > 0) {
            double advance = pacing.advance(remaining, FRAME_120_HZ);
            assertTrue(advance > 0, "Every frame before arrival must move");
            assertTrue(advance <= cruise + 1e-12);
            if (advance < previous - 1e-12) braking = true;
            else if (braking) fail("Braking must not speed up again, including the final frame");
            if (frames == 0) assertTrue(advance < cruise / 2, "The first frame must not start at full speed");
            remaining -= advance;
            previous = advance;
            frames++;
        }
        assertEquals(0, remaining);
        assertTrue(braking);
        assertEquals(0, pacing.advance(0, FRAME_120_HZ));
    }

    @Test void changingFramesSlowTheCruiseGraduallyButNotBelowTheMinimumSpeed() {
        var pacing = new JuliaFramePacing();
        pacing.reset();
        int[] colors = new int[64 * 40];
        pacing.observe(colors, 64, 40);
        double remaining = 1_000;
        for (int i = 0; i < 20; i++) remaining -= pacing.advance(remaining, FRAME_120_HZ);
        double cruise = JuliaFramePacing.SPEED * FRAME_120_HZ * 1e-9;
        java.util.Arrays.fill(colors, 0xff555555);
        pacing.observe(colors, 64, 40);
        assertEquals(cruise, pacing.advance(remaining, FRAME_120_HZ), 1e-12, "A uniform color change has no moving edges");

        double previous = cruise;
        for (int frame = 0; frame < 120; frame++) {
            for (int i = 0; i < colors.length; i++) colors[i] = (i + frame) % 2 == 0 ? 0xffffffff : 0xff000000;
            pacing.observe(colors, 64, 40);
            double step = pacing.advance(remaining, FRAME_120_HZ);
            remaining -= step;
            assertTrue(step <= previous + 1e-12, "Busy frames must not speed the point up");
            assertTrue(step >= previous * 0.85, "The slowdown must be gradual");
            assertTrue(step >= JuliaFramePacing.MIN_SPEED * FRAME_120_HZ * 1e-9 - 1e-12);
            previous = step;
        }
        assertEquals(JuliaFramePacing.MIN_SPEED * FRAME_120_HZ * 1e-9, previous, 1e-4);

        for (int frame = 0; frame < 240; frame++) {
            pacing.observe(colors, 64, 40); // Unchanged frames: quiet again.
            double step = pacing.advance(remaining, FRAME_120_HZ);
            remaining -= step;
            assertTrue(step >= previous - 1e-12, "Quiet frames must restore the speed");
            previous = step;
        }
        assertEquals(cruise, previous, cruise * 0.01);
    }

    @Test void theBoundarySlowsThePointInProportionToItsDistance() {
        for (double boundary : new double[] {0, 0.2, 2, 4, JuliaFramePacing.BOUNDARY_RANGE, 20}) {
            var pacing = new JuliaFramePacing();
            pacing.reset();
            double remaining = 1_000;
            for (int i = 0; i < 20; i++) remaining -= pacing.advance(remaining, FRAME_120_HZ, boundary);
            double expected = Math.clamp(JuliaFramePacing.SPEED * boundary / JuliaFramePacing.BOUNDARY_RANGE,
                    JuliaFramePacing.BOUNDARY_SPEED, JuliaFramePacing.SPEED);
            assertEquals(expected * FRAME_120_HZ * 1e-9, pacing.advance(remaining, FRAME_120_HZ, boundary), 1e-12,
                    "Cruise " + boundary + " points from the boundary");
        }
    }

    @Test void aStalledFrameDoesNotJump() {
        var pacing = new JuliaFramePacing();
        pacing.reset();
        double remaining = 200;
        for (int i = 0; i < 20; i++) remaining -= pacing.advance(remaining, FRAME_120_HZ);
        assertEquals(JuliaFramePacing.SPEED * 0.05, pacing.advance(remaining, 300_000_000), 1e-9);
    }

    @Test void resetStartsTheNextMotionFromRest() {
        var pacing = new JuliaFramePacing();
        pacing.reset();
        for (int i = 0; i < 20; i++) pacing.advance(100, FRAME_120_HZ);
        pacing.reset();
        assertTrue(pacing.advance(100, FRAME_120_HZ) < JuliaFramePacing.SPEED * FRAME_120_HZ * 1e-9 / 2);
    }

    private static int arrivalFrames(double distance, long frame) {
        var pacing = new JuliaFramePacing();
        pacing.reset();
        double remaining = distance;
        int frames = 0;
        while (remaining > 0) {
            remaining -= pacing.advance(remaining, frame);
            frames++;
        }
        return frames;
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
