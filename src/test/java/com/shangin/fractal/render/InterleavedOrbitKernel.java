package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalSample;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Experimental direct-double Mandelbrot/Julia kernel that advances two or four
 * independent pixel orbits in one loop to expose instruction-level
 * parallelism. When a lane escapes or reaches the iteration cap, its sample is
 * stored and the lane is refilled with the next pixel of the region in raster
 * order.
 *
 * <p>Every lane uses the exact operation order of {@code MandelbrotFormula}
 * and {@code JuliaFormula}, without FMA, so iteration counts, escape flags and
 * smooth values are bit-identical to the scalar kernel. Rows are reported in
 * order once all of their pixels are stored. This class is benchmark-only and
 * is not part of the production render path.
 *
 * <p>The hot loop keeps lane state in locals. Finished lanes are handled by a
 * single shared refill routine that exchanges lane state through small arrays;
 * a per-lane inlined refill multiplied the compiled loop size and regressed the
 * Mandelbrot overview with four lanes.
 */
final class InterleavedOrbitKernel {

    private static final double ESCAPE_RADIUS_SQUARED = 4.0;
    private static final int CANCELLATION_CHECK_INTERVAL = 8;
    private static final int CANCELLED = -1;

    /*
     * An idle lane iterates the fixed point z = 0, c = 0 and counts up from
     * this value, so it never stops the shared loop. Live lanes finish within
     * maxIterations steps after the last refill, long before the counter
     * could reach the cap.
     */
    private static final int IDLE_LANE_ITERATION = Integer.MIN_VALUE;

    private final int lanes;
    private final boolean mandelbrot;
    private final double juliaReal;
    private final double juliaImaginary;

    private InterleavedOrbitKernel(
            int lanes, boolean mandelbrot, double juliaReal, double juliaImaginary
    ) {
        if (lanes != 2 && lanes != 4) {
            throw new IllegalArgumentException("Supported lane counts are 2 and 4");
        }
        this.lanes = lanes;
        this.mandelbrot = mandelbrot;
        this.juliaReal = juliaReal;
        this.juliaImaginary = juliaImaginary;
    }

    static InterleavedOrbitKernel mandelbrot(int lanes) {
        return new InterleavedOrbitKernel(lanes, true, 0.0, 0.0);
    }

    static InterleavedOrbitKernel julia(int lanes, double cReal, double cImaginary) {
        return new InterleavedOrbitKernel(lanes, false, cReal, cImaginary);
    }

    /** Full-frame counterpart of {@link FractalCalculator#calculate}. */
    FractalData calculate(int width, int height, RenderGrid renderGrid, int maxIterations) {
        FractalData fractalData = new FractalData(width, height, maxIterations);
        calculateTile(fractalData, renderGrid, 0, width, 0, height, maxIterations,
                () -> false, (x, y) -> false, ignored -> {});
        return fractalData;
    }

    /** Same contract as the production {@code FractalCalculator.calculateTile}. */
    boolean calculateTile(
            SamplePlane fractalData,
            RenderGrid renderGrid,
            int xFrom,
            int xTo,
            int yFrom,
            int yTo,
            int maxIterations,
            BooleanSupplier cancelled,
            PixelReady ready,
            Consumer<RenderRegion> rowCompleted
    ) {
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(ready);
        Objects.requireNonNull(rowCompleted);
        if (xTo <= xFrom || yTo <= yFrom) {
            throw new IllegalArgumentException("Region must contain at least one pixel");
        }
        Lanes state = new Lanes(
                fractalData, renderGrid, xFrom, xTo, yFrom, yTo, maxIterations,
                cancelled, ready, rowCompleted);
        int live = state.start();
        if (live <= 0) {
            return live == 0;
        }
        return lanes == 2 ? runTwoLanes(state, maxIterations) : runFourLanes(state, maxIterations);
    }

    private static boolean runTwoLanes(Lanes state, int maxIterations) {
        double[] zrs = state.zr;
        double[] zis = state.zi;
        double[] crs = state.cr;
        double[] cis = state.ci;
        int[] its = state.iterations;

        for (;;) {
            double zr0 = zrs[0], zi0 = zis[0], cr0 = crs[0], ci0 = cis[0];
            double zr1 = zrs[1], zi1 = zis[1], cr1 = crs[1], ci1 = cis[1];
            int it0 = its[0], it1 = its[1];

            for (;;) {
                boolean active0 = zr0 * zr0 + zi0 * zi0 <= ESCAPE_RADIUS_SQUARED & it0 < maxIterations;
                boolean active1 = zr1 * zr1 + zi1 * zi1 <= ESCAPE_RADIUS_SQUARED & it1 < maxIterations;
                if (!(active0 & active1)) {
                    break;
                }
                double nextReal0 = zr0 * zr0 - zi0 * zi0 + cr0;
                double nextImaginary0 = 2.0 * zr0 * zi0 + ci0;
                double nextReal1 = zr1 * zr1 - zi1 * zi1 + cr1;
                double nextImaginary1 = 2.0 * zr1 * zi1 + ci1;
                zr0 = nextReal0;
                zi0 = nextImaginary0;
                zr1 = nextReal1;
                zi1 = nextImaginary1;
                it0++;
                it1++;
            }

            zrs[0] = zr0; zis[0] = zi0; its[0] = it0;
            zrs[1] = zr1; zis[1] = zi1; its[1] = it1;
            int live = state.refill(2);
            if (live <= 0) {
                return live == 0;
            }
        }
    }

    private static boolean runFourLanes(Lanes state, int maxIterations) {
        double[] zrs = state.zr;
        double[] zis = state.zi;
        double[] crs = state.cr;
        double[] cis = state.ci;
        int[] its = state.iterations;

        for (;;) {
            double zr0 = zrs[0], zi0 = zis[0], cr0 = crs[0], ci0 = cis[0];
            double zr1 = zrs[1], zi1 = zis[1], cr1 = crs[1], ci1 = cis[1];
            double zr2 = zrs[2], zi2 = zis[2], cr2 = crs[2], ci2 = cis[2];
            double zr3 = zrs[3], zi3 = zis[3], cr3 = crs[3], ci3 = cis[3];
            int it0 = its[0], it1 = its[1], it2 = its[2], it3 = its[3];

            for (;;) {
                boolean active0 = zr0 * zr0 + zi0 * zi0 <= ESCAPE_RADIUS_SQUARED & it0 < maxIterations;
                boolean active1 = zr1 * zr1 + zi1 * zi1 <= ESCAPE_RADIUS_SQUARED & it1 < maxIterations;
                boolean active2 = zr2 * zr2 + zi2 * zi2 <= ESCAPE_RADIUS_SQUARED & it2 < maxIterations;
                boolean active3 = zr3 * zr3 + zi3 * zi3 <= ESCAPE_RADIUS_SQUARED & it3 < maxIterations;
                if (!(active0 & active1 & active2 & active3)) {
                    break;
                }
                double nextReal0 = zr0 * zr0 - zi0 * zi0 + cr0;
                double nextImaginary0 = 2.0 * zr0 * zi0 + ci0;
                double nextReal1 = zr1 * zr1 - zi1 * zi1 + cr1;
                double nextImaginary1 = 2.0 * zr1 * zi1 + ci1;
                double nextReal2 = zr2 * zr2 - zi2 * zi2 + cr2;
                double nextImaginary2 = 2.0 * zr2 * zi2 + ci2;
                double nextReal3 = zr3 * zr3 - zi3 * zi3 + cr3;
                double nextImaginary3 = 2.0 * zr3 * zi3 + ci3;
                zr0 = nextReal0;
                zi0 = nextImaginary0;
                zr1 = nextReal1;
                zi1 = nextImaginary1;
                zr2 = nextReal2;
                zi2 = nextImaginary2;
                zr3 = nextReal3;
                zi3 = nextImaginary3;
                it0++;
                it1++;
                it2++;
                it3++;
            }

            zrs[0] = zr0; zis[0] = zi0; its[0] = it0;
            zrs[1] = zr1; zis[1] = zi1; its[1] = it1;
            zrs[2] = zr2; zis[2] = zi2; its[2] = it2;
            zrs[3] = zr3; zis[3] = zi3; its[3] = it3;
            int live = state.refill(4);
            if (live <= 0) {
                return live == 0;
            }
        }
    }

    /** Copy of the Mandelbrot main-cardioid and period-2-bulb predicate. */
    private static boolean isInMainCardioidOrPeriodTwoBulb(double real, double imaginary) {
        double imaginarySquared = imaginary * imaginary;
        double cardioidX = real - 0.25;
        double q = cardioidX * cardioidX + imaginarySquared;

        if (q * (q + cardioidX) < 0.25 * imaginarySquared) {
            return true;
        }

        double bulbX = real + 1.0;

        return bulbX * bulbX + imaginarySquared < 0.0625;
    }

    /**
     * Lane state exchanged with the hot loop, plus the raster pixel cursor.
     * Samples are stored as lanes finish, and rows are reported in order once
     * all of their pixels have been stored.
     */
    private final class Lanes {

        final double[] zr = new double[lanes];
        final double[] zi = new double[lanes];
        final double[] cr = new double[lanes];
        final double[] ci = new double[lanes];
        final int[] iterations = new int[lanes];
        private final int[] pixelX = new int[lanes];
        private final int[] pixelY = new int[lanes];

        private final SamplePlane fractalData;
        private final RenderGrid renderGrid;
        private final int xFrom;
        private final int xTo;
        private final int yFrom;
        private final int maxIterations;
        private final BooleanSupplier cancelled;
        private final PixelReady ready;
        private final Consumer<RenderRegion> rowCompleted;
        private final int[] remainingInRow;
        private final int rowCount;

        private int nextX;
        private int nextY;
        private int nextRowToReport;
        private double rowImaginary;
        private int live;

        Lanes(
                SamplePlane fractalData,
                RenderGrid renderGrid,
                int xFrom,
                int xTo,
                int yFrom,
                int yTo,
                int maxIterations,
                BooleanSupplier cancelled,
                PixelReady ready,
                Consumer<RenderRegion> rowCompleted
        ) {
            this.fractalData = fractalData;
            this.renderGrid = renderGrid;
            this.xFrom = xFrom;
            this.xTo = xTo;
            this.yFrom = yFrom;
            this.maxIterations = maxIterations;
            this.cancelled = cancelled;
            this.ready = ready;
            this.rowCompleted = rowCompleted;
            this.rowCount = yTo - yFrom;
            this.remainingInRow = new int[rowCount];
            Arrays.fill(remainingInRow, xTo - xFrom);
            this.nextX = xFrom;
            this.nextY = yFrom;
        }

        /** Loads every lane; returns the live lane count or {@link #CANCELLED}. */
        int start() {
            for (int lane = 0; lane < lanes; lane++) {
                if (!load(lane)) {
                    return CANCELLED;
                }
            }
            return live;
        }

        /** Stores and refills every finished lane; returns the live lane count or {@link #CANCELLED}. */
        int refill(int laneCount) {
            for (int lane = 0; lane < laneCount; lane++) {
                int laneIterations = iterations[lane];
                boolean finished = !(zr[lane] * zr[lane] + zi[lane] * zi[lane] <= ESCAPE_RADIUS_SQUARED)
                        || laneIterations >= maxIterations;
                if (!finished) {
                    continue;
                }
                fractalData.set(pixelX[lane], pixelY[lane], new FractalSample(
                        laneIterations, laneIterations < maxIterations, zr[lane], zi[lane]));
                pixelStored(pixelY[lane]);
                live--;
                if (!load(lane)) {
                    return CANCELLED;
                }
            }
            return live;
        }

        /** Loads the next pixel into a lane, or idles it; false only on cancellation. */
        private boolean load(int lane) {
            while (nextY - yFrom < rowCount) {
                int x = nextX;
                int y = nextY;
                if (x == xFrom) {
                    rowImaginary = renderGrid.imaginaryAt(y);
                }
                if (((x - xFrom) & (CANCELLATION_CHECK_INTERVAL - 1)) == 0
                        && cancelled.getAsBoolean()) {
                    return false;
                }
                if (++nextX == xTo) {
                    nextX = xFrom;
                    nextY++;
                }

                if (ready.test(x, y)) {
                    pixelStored(y);
                    continue;
                }

                double real = renderGrid.realAt(x);
                if (mandelbrot) {
                    if (isInMainCardioidOrPeriodTwoBulb(real, rowImaginary)) {
                        fractalData.set(x, y, new FractalSample(maxIterations, false, 0.0, 0.0));
                        pixelStored(y);
                        continue;
                    }
                    zr[lane] = 0.0;
                    zi[lane] = 0.0;
                    cr[lane] = real;
                    ci[lane] = rowImaginary;
                } else {
                    zr[lane] = real;
                    zi[lane] = rowImaginary;
                    cr[lane] = juliaReal;
                    ci[lane] = juliaImaginary;
                }
                iterations[lane] = 0;
                pixelX[lane] = x;
                pixelY[lane] = y;
                live++;
                return true;
            }
            zr[lane] = 0.0;
            zi[lane] = 0.0;
            cr[lane] = 0.0;
            ci[lane] = 0.0;
            iterations[lane] = IDLE_LANE_ITERATION;
            return true;
        }

        private void pixelStored(int y) {
            if (--remainingInRow[y - yFrom] != 0) {
                return;
            }
            while (nextRowToReport < rowCount && remainingInRow[nextRowToReport] == 0) {
                rowCompleted.accept(new RenderRegion(
                        xFrom, yFrom + nextRowToReport, xTo - xFrom, 1));
                nextRowToReport++;
            }
        }
    }
}
