package com.shangin.fractal.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Generation-local bivariate linear approximations of a Mandelbrot reference.
 * A block maps a perturbation delta to {@code A * delta + B * deltaC}.
 *
 * <p>The coefficients and radius composition follow
 * https://mathr.co.uk/web/deep-zoom.html#bivariate-linear-approximation.
 * Radii are evaluated for an upper bound on every pixel's {@code |deltaC|};
 * the table must not be used outside that bound.</p>
 */
final class MandelbrotBlaTable {

    private static final int MIN_LEVEL = 3;
    private static final int MIN_SKIP = 1 << MIN_LEVEL;
    /* Omit the quadratic term only at the relative rounding scale of double. */
    private static final double EPSILON = 0x1.0p-52;

    private final Step[][] levels;
    private final double maximumDeltaC;
    private final int referenceLimit;

    private MandelbrotBlaTable(Step[][] levels, double maximumDeltaC, int referenceLimit) {
        this.levels = levels;
        this.maximumDeltaC = maximumDeltaC;
        this.referenceLimit = referenceLimit;
    }

    /** Returns {@code null} for cancellation or when no useful block is available. */
    static MandelbrotBlaTable create(
            double[] real,
            double[] imaginary,
            int lastBoundedIteration,
            double maximumDeltaC,
            BooleanSupplier cancelled
    ) {
        if (lastBoundedIteration <= MIN_SKIP || !(maximumDeltaC >= 0.0)
                || !Double.isFinite(maximumDeltaC)) {
            return null;
        }

        // Step zero is nonlinear after rebasing: Z[0] == 0. Start at step one.
        Step[] previous = new Step[lastBoundedIteration - 1];
        for (int index = 0; index < previous.length; index++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                return null;
            }
            double aReal = 2.0 * real[index + 1];
            double aImaginary = 2.0 * imaginary[index + 1];
            double radius = EPSILON * Math.hypot(aReal, aImaginary);
            previous[index] = new Step(aReal, aImaginary, 1.0, 0.0,
                    radius * radius, 1);
        }

        List<Step[]> retained = new ArrayList<>();
        boolean useful = false;
        for (int level = 1; previous.length > 1; level++) {
            Step[] next = new Step[(previous.length + 1) / 2];
            for (int index = 0; index < next.length; index++) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return null;
                }
                int first = index * 2;
                next[index] = first + 1 < previous.length
                        ? merge(previous[first], previous[first + 1], maximumDeltaC)
                        : previous[first];
                useful |= level >= MIN_LEVEL && next[index].length() >= MIN_SKIP
                        && next[index].radiusSquared() > 0.0;
            }
            if (level >= MIN_LEVEL) {
                retained.add(next);
            }
            previous = next;
        }
        return useful
                ? new MandelbrotBlaTable(retained.toArray(Step[][]::new),
                        maximumDeltaC, lastBoundedIteration)
                : null;
    }

    boolean supportsDelta(double real, double imaginary) {
        return Math.hypot(real, imaginary) <= maximumDeltaC;
    }

    Step lookup(int referenceIteration, double deltaMagnitudeSquared, int remainingIterations) {
        int offset = referenceIteration - 1;
        if (offset < 0 || (offset & (MIN_SKIP - 1)) != 0
                || referenceIteration >= referenceLimit || remainingIterations < MIN_SKIP) {
            return null;
        }
        for (int level = levels.length - 1; level >= 0; level--) {
            int shift = level + MIN_LEVEL;
            int index = offset >>> shift;
            if ((index << shift) != offset || index >= levels[level].length) {
                continue;
            }
            Step step = levels[level][index];
            if (step.length() >= MIN_SKIP && step.length() <= remainingIterations
                    && deltaMagnitudeSquared < step.radiusSquared()) {
                return step;
            }
        }
        return null;
    }

    private static Step merge(Step first, Step second, double maximumDeltaC) {
        double aReal = second.aReal() * first.aReal()
                - second.aImaginary() * first.aImaginary();
        double aImaginary = second.aReal() * first.aImaginary()
                + second.aImaginary() * first.aReal();
        double bReal = second.aReal() * first.bReal()
                - second.aImaginary() * first.bImaginary() + second.bReal();
        double bImaginary = second.aReal() * first.bImaginary()
                + second.aImaginary() * first.bReal() + second.bImaginary();
        double firstA = Math.hypot(first.aReal(), first.aImaginary());
        double firstB = Math.hypot(first.bReal(), first.bImaginary());
        double radius = Math.max(0.0, Math.min(Math.sqrt(first.radiusSquared()),
                (Math.sqrt(second.radiusSquared()) - firstB * maximumDeltaC) / firstA));
        if (!Double.isFinite(aReal) || !Double.isFinite(aImaginary)
                || !Double.isFinite(bReal) || !Double.isFinite(bImaginary)
                || !Double.isFinite(radius)) {
            radius = 0.0;
        }
        return new Step(aReal, aImaginary, bReal, bImaginary,
                radius * radius, first.length() + second.length());
    }

    record Step(
            double aReal,
            double aImaginary,
            double bReal,
            double bImaginary,
            double radiusSquared,
            int length
    ) {}
}
