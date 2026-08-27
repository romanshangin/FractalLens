package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Optional;

public final class PixelShiftCalculator {

    private static final double INTEGER_EPSILON = 1e-6;

    private PixelShiftCalculator() {
    }

    public static Optional<PixelShift> calculate(
            Viewport source,
            Viewport target,
            int width,
            int height
    ) {
        double shiftX = target.xAt(
                source.realAt(
                        0.0,
                        width,
                        height),
                width,
                height);

        double shiftY = target.yAt(
                source.imaginaryAt(
                        0.0,
                        height),
                height);

        int integerX = nearestInteger(shiftX);

        int integerY = nearestInteger(shiftY);

        if (integerX == Integer.MIN_VALUE || integerY == Integer.MIN_VALUE) {
            return Optional.empty();
        }

        PixelShift shift = new PixelShift(
                integerX,
                integerY);

        if (!coordinatesMatchExactly(
                source,
                target,
                width,
                height,
                shift)) {
            return Optional.empty();
        }

        return Optional.of(shift);
    }

    private static int nearestInteger(double value) {
        long rounded = Math.round(value);

        if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            return Integer.MIN_VALUE;
        }

        if (Math.abs(value - rounded) > INTEGER_EPSILON) {
            return Integer.MIN_VALUE;
        }

        return (int) rounded;
    }

    private static boolean coordinatesMatchExactly(
            Viewport source,
            Viewport target,
            int width,
            int height,
            PixelShift shift
    ) {
        int sourceXFrom = Math.max(0, -shift.dx());

        int sourceXTo = Math.min(width, width - shift.dx());

        int sourceYFrom = Math.max(0, -shift.dy());

        int sourceYTo = Math.min(height, height - shift.dy());

        if (sourceXFrom >= sourceXTo || sourceYFrom >= sourceYTo) {
            return false;
        }

        for (int sourceX = sourceXFrom; sourceX < sourceXTo; sourceX++) {

            int targetX = sourceX + shift.dx();

            double sourceReal = source.realAt(sourceX, width, height);

            double targetReal = target.realAt(targetX, width, height);

            if (Double.doubleToLongBits(sourceReal) != Double.doubleToLongBits(targetReal)) {
                return false;
            }
        }

        for (int sourceY = sourceYFrom; sourceY < sourceYTo; sourceY++) {

            int targetY = sourceY + shift.dy();

            double sourceImaginary = source.imaginaryAt(sourceY, height);

            double targetImaginary = target.imaginaryAt(targetY, height);

            if (Double.doubleToLongBits(sourceImaginary) != Double.doubleToLongBits(targetImaginary)) {
                return false;
            }
        }

        return true;
    }
}