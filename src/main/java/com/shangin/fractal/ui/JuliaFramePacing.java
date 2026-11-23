package com.shangin.fractal.ui;

/** Limits the next map-animation step using colors from the last two published Julia frames. */
final class JuliaFramePacing {
    private static final int COLUMNS = 64;
    private static final int ROWS = 40;
    private static final long MAX_STEP = 12_000_000;
    private static final long MIN_STEP = 125_000;
    private int[] previous = new int[COLUMNS * ROWS];
    private int[] current = new int[COLUMNS * ROWS];
    private int previousWidth;
    private int previousHeight;
    private static final double MAX_SPEED = 210; // Logical map points per second.
    private static final double ACCELERATION = 700; // Logical map points per second squared.
    // A 325 ms time constant closes 5% of the gap at 60 Hz.
    private static final double FOLLOW_TIME_SECONDS = 0.325;
    private static final double SETTLE_DISTANCE = 0.1; // Logical map points.
    private double lastDistance;
    private double lastSpeed;
    private long budget;
    private long lastStep;

    void reset() {
        previousWidth = 0;
        lastStep = 0;
        lastDistance = 0;
        lastSpeed = 0;
        budget = MAX_STEP;
    }

    void observe(int[] pixels, int width, int height) {
        boolean comparable = previousWidth == width && previousHeight == height;
        int changed = 0;
        for (int row = 0; row < ROWS; row++) {
            int y = (int) ((row + 0.5) * height / ROWS);
            for (int column = 0; column < COLUMNS; column++) {
                int x = (int) ((column + 0.5) * width / COLUMNS);
                int index = row * COLUMNS + column;
                current[index] = pixels[y * width + x];
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
        if (comparable && lastStep > 0) {
            // Aim for at most 2.5% visibly changed samples. Recover speed gradually in quiet areas.
            double ratio = Math.clamp(previous.length * 0.025 / Math.max(1, changed), 0.25, 1.07);
            budget = Math.clamp((long) (lastStep * ratio), MIN_STEP, MAX_STEP);
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

    double advance(double remaining, long elapsedNanos) {
        if (remaining <= 0 || elapsedNanos <= 0) return 0;
        double seconds = elapsedNanos * 1e-9;
        double dt = Math.min(seconds, 0.032);
        double previousSpeed = Math.min(lastSpeed, lastDistance / seconds);
        double limit = Math.min((previousSpeed + ACCELERATION * dt) * dt,
                MAX_SPEED * step(elapsedNanos) * 1e-9);
        lastDistance = Math.min(followStep(remaining, dt), limit);
        lastSpeed = lastDistance / dt;
        return lastDistance;
    }

    private static double followStep(double remaining, double seconds) {
        if (remaining > SETTLE_DISTANCE) {
            return remaining * -Math.expm1(-seconds / FOLLOW_TIME_SECONDS);
        }

        // Join the exponential approach at the same speed, then bring the speed to zero
        // in finite time. The last frame follows the same curve as the preceding frames.
        double timeToStop = 2 * FOLLOW_TIME_SECONDS * Math.sqrt(remaining / SETTLE_DISTANCE);
        double progress = seconds / timeToStop;
        if (progress >= 1) return remaining;
        return remaining * progress * (2 - progress);
    }

    long step(long elapsed) {
        lastStep = Math.min(elapsed, budget);
        return lastStep;
    }
}
