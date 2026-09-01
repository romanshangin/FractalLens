package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        BigDecimal shiftX = target.xAtExact(
                source.realAtExact(BigDecimal.ZERO, width, height), width, height);

        BigDecimal shiftY = target.yAtExact(
                source.imaginaryAtExact(BigDecimal.ZERO, height), height);

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

    private static int nearestInteger(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        if (value.subtract(rounded).abs()
                .compareTo(BigDecimal.valueOf(INTEGER_EPSILON)) > 0) {
            return Integer.MIN_VALUE;
        }
        try {
            return rounded.intValueExact();
        } catch (ArithmeticException exception) {
            return Integer.MIN_VALUE;
        }
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

            BigDecimal sourceReal = source.realAtExact(
                    BigDecimal.valueOf(sourceX), width, height);
            BigDecimal targetReal = target.realAtExact(
                    BigDecimal.valueOf(targetX), width, height);

            if (sourceReal.compareTo(targetReal) != 0) {
                return false;
            }
        }

        for (int sourceY = sourceYFrom; sourceY < sourceYTo; sourceY++) {

            int targetY = sourceY + shift.dy();

            BigDecimal sourceImaginary = source.imaginaryAtExact(
                    BigDecimal.valueOf(sourceY), height);
            BigDecimal targetImaginary = target.imaginaryAtExact(
                    BigDecimal.valueOf(targetY), height);

            if (sourceImaginary.compareTo(targetImaginary) != 0) {
                return false;
            }
        }

        return true;
    }
}
