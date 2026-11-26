package com.shangin.fractal.ui;

/**
 * Moves the map point toward its target at a speed that does not depend on the distance.
 * The speed is lower where the published Julia frames change quickly, within a bounded range,
 * and lower still close to the Mandelbrot set's boundary, where the Julia set changes most for
 * the smallest step. The motion accelerates briefly at the start and brakes smoothly to a stop.
 */
final class JuliaFramePacing {
    /** Cruise speed in quiet areas, in logical map points per second. */
    static final double SPEED = 40;
    /** Lowest cruise speed, where the frames change most, in logical map points per second. */
    static final double MIN_SPEED = 10;
    /** Speed on the Mandelbrot set's boundary, in logical map points per second. */
    static final double BOUNDARY_SPEED = 3;
    /** Distance from the boundary, in logical map points, at which the boundary stops slowing the point. */
    static final int BOUNDARY_RANGE = 8;
    /** Acceleration and braking in logical map points per second squared: full speed in 50 ms. */
    static final double ACCELERATION = SPEED / 0.05;
    private static final double MIN_PACE = MIN_SPEED / SPEED;
    // Changes slow the point within about 0.1 s; quiet frames restore the speed over about 0.4 s.
    private static final double SLOWDOWN_SECONDS = 0.1;
    private static final double RECOVERY_SECONDS = 0.4;
    /** A longer frame advances as if it had taken this long, so a stall does not become a jump. */
    static final double MAX_FRAME_SECONDS = 0.05;
    private static final int COLUMNS = 64;
    private static final int ROWS = 40;
    // Visibly changed edge samples between two frames that the pace aims for.
    private static final double TARGET_CHANGED = COLUMNS * ROWS * 0.025;
    private int[] previous = new int[COLUMNS * ROWS];
    private int[] current = new int[COLUMNS * ROWS];
    private int previousWidth;
    private int previousHeight;
    private boolean moved;
    private double pace = 1;
    private double targetPace = 1;
    private double speed;

    void reset() {
        previousWidth = 0;
        moved = false;
        pace = targetPace = 1;
        speed = 0;
    }

    /** Compares a published frame with the previous one and sets the pace that the motion approaches. */
    void observe(int[] pixels, int width, int height) {
        boolean comparable = previousWidth == width && previousHeight == height;
        int changed = 0;
        for (int row = 0; row < ROWS; row++) {
            int y = (int) ((row + 0.5) * height / ROWS);
            for (int column = 0; column < COLUMNS; column++) {
                int x = (int) ((column + 0.5) * width / COLUMNS);
                current[row * COLUMNS + column] = pixels[y * width + x];
            }
        }
        if (comparable) {
            for (int i = 0; i < current.length; i++) {
                if (different(previous[i], current[i]) && (nearEdge(previous, i) || nearEdge(current, i))) {
                    changed++;
                }
            }
        }
        int[] swap = previous;
        previous = current;
        current = swap;
        previousWidth = width;
        previousHeight = height;
        if (comparable && moved) {
            // The number of changed samples grows with the step, so scale the pace toward the target.
            targetPace = Math.clamp(pace * TARGET_CHANGED / Math.max(1, changed), MIN_PACE, 1);
        }
    }

    private static boolean nearEdge(int[] colors, int index) {
        int horizontal = index % COLUMNS == COLUMNS - 1 ? index - 1 : index + 1;
        int vertical = index >= colors.length - COLUMNS ? index - COLUMNS : index + COLUMNS;
        return different(colors[index], colors[horizontal]) || different(colors[index], colors[vertical]);
    }

    private static boolean different(int first, int second) {
        return Math.abs((first & 255) - (second & 255)) >= 20
                || Math.abs(((first >> 8) & 255) - ((second >> 8) & 255)) >= 20
                || Math.abs(((first >> 16) & 255) - ((second >> 16) & 255)) >= 20;
    }

    /** Returns how far to move this frame away from the Mandelbrot set's boundary. */
    double advance(double remaining, long elapsedNanos) {
        return advance(remaining, elapsedNanos, Double.POSITIVE_INFINITY);
    }

    /**
     * Returns how far to move this frame, never more than the remaining distance.
     * {@code boundaryDistance} is the point's distance from the Mandelbrot set's boundary in map
     * points, even when {@code remaining} uses a coarser gesture scale: within
     * {@link #BOUNDARY_RANGE} map points it scales the cruise speed down to the boundary speed.
     */
    double advance(double remaining, long elapsedNanos, double boundaryDistance) {
        if (remaining <= 0 || elapsedNanos <= 0) return 0;
        double seconds = Math.min(elapsedNanos * 1e-9, MAX_FRAME_SECONDS);
        moved = true;
        double settle = targetPace < pace ? SLOWDOWN_SECONDS : RECOVERY_SECONDS;
        pace += (targetPace - pace) * -Math.expm1(-seconds / settle);
        // The speed falls in proportion to the distance from the boundary, so the point approaches
        // it gradually and crosses it at the boundary speed.
        double nearBoundary = Math.clamp(SPEED * boundaryDistance / BOUNDARY_RANGE, BOUNDARY_SPEED, SPEED);
        // The braking limit lets the point stop exactly at the target with the same deceleration.
        speed = Math.min(Math.min(Math.min(SPEED * pace, nearBoundary), speed + ACCELERATION * seconds),
                Math.sqrt(2 * ACCELERATION * remaining));
        double step = speed * seconds;
        if (step >= remaining) {
            speed = 0;
            return remaining;
        }
        return step;
    }

    /** Fraction of the quiet-area speed currently in use. */
    double pace() {
        return pace;
    }
}
