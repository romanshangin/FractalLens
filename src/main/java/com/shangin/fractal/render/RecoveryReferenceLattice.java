package com.shangin.fractal.render;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Recovery references for samples their primary reference cannot resolve, built at fixed
 * lattice points around that primary reference. The lattice point serving a sample is a
 * function of the sample's coordinate alone, and a reference depends only on its exact
 * lattice point, so a sample's value never depends on worker scheduling or on which samples
 * were taken first. The bounded cache only saves rebuilding; evicting an entry changes timing,
 * never results.
 *
 * @param <R> reference type; a builder returns {@code null} only when cancelled
 */
final class RecoveryReferenceLattice<R> {

    /** Lattice spacing in pixels of the frame's grid. */
    static final int SPACING_PIXELS = 16;

    @FunctionalInterface
    interface Builder<R> {
        /** Builds the reference starting at the exact lattice point, or returns {@code null} when cancelled. */
        R build(BigDecimal real, BigDecimal imaginary, BooleanSupplier cancelled);
    }

    private final BigDecimal originReal;
    private final BigDecimal originImaginary;
    private final BigDecimal spacingReal;
    private final BigDecimal spacingImaginary;
    private final double spacingRealValue;
    private final double spacingImaginaryValue;
    private final MathContext mathContext;
    private final Builder<R> builder;
    private final LongAdder builds = new LongAdder();
    private final Map<Long, Slot<R>> slots;

    RecoveryReferenceLattice(BigDecimal originReal, BigDecimal originImaginary,
                             BigDecimal realStep, BigDecimal imaginaryStep, MathContext mathContext,
                             int capacity, Builder<R> builder) {
        if (capacity < 1) throw new IllegalArgumentException("Positive lattice cache capacity required");
        this.originReal = Objects.requireNonNull(originReal);
        this.originImaginary = Objects.requireNonNull(originImaginary);
        this.mathContext = Objects.requireNonNull(mathContext);
        BigDecimal spacing = BigDecimal.valueOf(SPACING_PIXELS);
        this.spacingReal = realStep.multiply(spacing, mathContext);
        this.spacingImaginary = imaginaryStep.multiply(spacing, mathContext);
        this.spacingRealValue = spacingReal.doubleValue();
        this.spacingImaginaryValue = spacingImaginary.doubleValue();
        this.builder = Objects.requireNonNull(builder);
        this.slots = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Slot<R>> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * The reference at the lattice point nearest to {@code real + i imaginary}, whose offset
     * from the lattice origin is {@code deltaReal + i deltaImaginary}; the offset must be the
     * exact difference rounded to double, so it too is a function of the coordinate. Returns
     * {@code null} when cancelled or when the offset has no lattice point.
     */
    R reference(double deltaReal, double deltaImaginary, BooleanSupplier cancelled) {
        double cellReal = Math.rint(deltaReal / spacingRealValue);
        double cellImaginary = Math.rint(deltaImaginary / spacingImaginaryValue);
        if (!(Math.abs(cellReal) <= Integer.MAX_VALUE) || !(Math.abs(cellImaginary) <= Integer.MAX_VALUE)) {
            return null;
        }
        int x = (int) cellReal;
        int y = (int) cellImaginary;
        Slot<R> slot;
        synchronized (slots) {
            slot = slots.computeIfAbsent(((long) x << 32) | (y & 0xffffffffL), ignored -> new Slot<>());
        }
        // Workers needing the same point wait for one exact orbit instead of each building it.
        synchronized (slot) {
            if (slot.reference == null) {
                slot.reference = builder.build(
                        originReal.add(spacingReal.multiply(BigDecimal.valueOf(x), mathContext), mathContext),
                        originImaginary.add(spacingImaginary.multiply(BigDecimal.valueOf(y), mathContext), mathContext),
                        cancelled);
                if (slot.reference != null) builds.increment();
            }
            return slot.reference;
        }
    }

    /** References built so far, counting rebuilds after eviction. */
    long buildCount() {
        return builds.sum();
    }

    private static final class Slot<R> {
        private R reference;
    }
}
