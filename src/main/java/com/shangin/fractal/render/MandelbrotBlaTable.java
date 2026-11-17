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
 *
 * <p>A block also bounds its own error: for every delta within its radius,
 * {@code A * delta + B * deltaC} with exact arithmetic differs from the exact
 * perturbation after {@code length} iterations by at most
 * {@code deltaErrorFactor * |delta| + deltaCErrorFactor * |deltaC|}. A block skips the
 * escape test of its iterations, so no step starts where the reference lies within what
 * a delta inside its radius could move across the escape radius.</p>
 */
final class MandelbrotBlaTable {

    private static final int MIN_LEVEL = 3;
    private static final int MIN_SKIP = 1 << MIN_LEVEL;
    /* Omit the quadratic term only at the relative rounding scale of double. */
    private static final double EPSILON = 0x1.0p-52;
    private static final double UNIT_ROUNDOFF = 0x1p-53;
    /* Relative rounding bound of one complex product, or of a product plus a sum. */
    private static final double PRODUCT_ROUNDING = 4.0 * UNIT_ROUNDOFF;
    /*
     * Within its radius a delta changes |Z|^2 by at most (4 EPSILON + 4 EPSILON^2) |Z|^2 < 20
     * EPSILON for |Z| <= 2; the generous margin also covers the rounding of the stored |Z|^2.
     */
    private static final double NEAR_RADIUS_MARGIN = 64.0 * EPSILON;

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
            double referenceReal = real[index + 1];
            double referenceImaginary = imaginary[index + 1];
            double aReal = 2.0 * referenceReal;
            double aImaginary = 2.0 * referenceImaginary;
            double aMagnitude = Math.hypot(aReal, aImaginary);
            boolean nearRadius = 4.0 - (referenceReal * referenceReal + referenceImaginary * referenceImaginary)
                    <= NEAR_RADIUS_MARGIN;
            double radius = nearRadius ? 0.0 : EPSILON * aMagnitude;
            // Omitting delta^2 errs by at most radius * |delta|; the stored A = 2 Z carries the
            // rounding of Z. B = 1 is exact.
            previous[index] = new Step(aReal, aImaginary, 1.0, 0.0,
                    radius * radius, 1, (EPSILON + UNIT_ROUNDOFF) * aMagnitude, 0.0);
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
        // Magnitudes for the error factors only: a square root of the sum of squares is within a
        // few units in the last place, far inside their slack, and much cheaper than hypot.
        double secondA = Math.sqrt(second.aReal() * second.aReal() + second.aImaginary() * second.aImaginary());
        double secondB = Math.sqrt(second.bReal() * second.bReal() + second.bImaginary() * second.bImaginary());
        double radius = Math.max(0.0, Math.min(Math.sqrt(first.radiusSquared()),
                (Math.sqrt(second.radiusSquared()) - firstB * maximumDeltaC) / firstA));
        // The second block sees the first block's exact result, within its error of the
        // linear one, and amplifies the first block's error by at most |A2|. Computing the
        // merged coefficients rounds them once more.
        double deltaErrorFactor = secondA * first.deltaErrorFactor()
                + second.deltaErrorFactor() * (firstA + first.deltaErrorFactor())
                + PRODUCT_ROUNDING * secondA * firstA;
        double deltaCErrorFactor = secondA * first.deltaCErrorFactor()
                + second.deltaErrorFactor() * (firstB + first.deltaCErrorFactor())
                + second.deltaCErrorFactor()
                + PRODUCT_ROUNDING * (secondA * firstB + secondB);
        if (!Double.isFinite(aReal) || !Double.isFinite(aImaginary)
                || !Double.isFinite(bReal) || !Double.isFinite(bImaginary)
                || !Double.isFinite(radius) || !Double.isFinite(deltaErrorFactor)
                || !Double.isFinite(deltaCErrorFactor)) {
            radius = 0.0;
        }
        return new Step(aReal, aImaginary, bReal, bImaginary,
                radius * radius, first.length() + second.length(),
                deltaErrorFactor, deltaCErrorFactor);
    }

    record Step(
            double aReal,
            double aImaginary,
            double bReal,
            double bImaginary,
            double radiusSquared,
            int length,
            double deltaErrorFactor,
            double deltaCErrorFactor
    ) {}
}
