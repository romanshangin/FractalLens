package com.shangin.fractal.math;

import java.util.Objects;

public record Viewport(
        double centerReal,
        double centerImaginary,
        double scale
) {
    public Viewport {
        if (!Double.isFinite(centerReal) || !Double.isFinite(centerImaginary)) {
            throw new IllegalArgumentException("Viewport center must be finite");
        }

        if (!Double.isFinite(scale) || scale <= 0.0) {
            throw new IllegalArgumentException("Scale must be positive and finite");
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Width and height must be at least 2");
        }
    }

    public double visibleHeight() {
        return scale;
    }

    public double visibleWidth(int width, int height) {
        validateDimensions(width, height);

        return scale * (double) width / height;
    }

    public double minReal(
            int width,
            int height
    ) {
        return centerReal - visibleWidth(width, height) / 2.0;
    }

    public double maxReal(
            int width,
            int height
    ) {
        return centerReal + visibleWidth(width, height) / 2.0;
    }

    public double minImaginary() {
        return centerImaginary - scale / 2.0;
    }

    public double maxImaginary() {
        return centerImaginary + scale / 2.0;
    }

    public double realAt(
            int x,
            int width,
            int height
    ) {
        validateDimensions(width, height);

        double position = (double) x / (width - 1);

        return minReal(width, height) + position * visibleWidth(width, height);
    }

    public double imaginaryAt(
            int y,
            int height
    ) {
        if (height < 2) {
            throw new IllegalArgumentException("Height must be at least 2");
        }

        double position = (double) y / (height - 1);

        return maxImaginary() - position * visibleHeight();
    }

    public double xAt(
            double real,
            int width,
            int height
    ) {
        return (real - minReal(width, height)) / realUnitsPerPixel(width, height);
    }

    public double yAt(
            double imaginary,
            int height
    ) {
        return (maxImaginary() - imaginary) / imaginaryUnitsPerPixel(height);
    }

    public Viewport zoom(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("Zoom factor must be positive and finite");
        }

        return new Viewport(centerReal, centerImaginary, scale * factor);
    }

    public Viewport pan(
            double deltaReal,
            double deltaImaginary
    ) {
        return new Viewport(
                centerReal + deltaReal,
                centerImaginary + deltaImaginary,
                scale);
    }

    public Viewport fit(
            double contentWidth,
            double contentHeight,
            int pixelWidth,
            int pixelHeight
    ) {
        double windowAspect = (double) pixelWidth / pixelHeight;

        double contentAspect = contentWidth / contentHeight;

        double fittedScale;

        if (windowAspect < contentAspect) {
            fittedScale = contentWidth / windowAspect;
        } else {
            fittedScale = contentHeight;
        }

        return new Viewport(
                centerReal,
                centerImaginary,
                fittedScale);
    }

    public double realAt(
            double x,
            int width,
            int height
    ) {
        validateDimensions(width, height);

        double clampedX = Math.clamp(x, 0.0, width - 1.0);

        double position = clampedX / (width - 1.0);

        return minReal(width, height) + position * visibleWidth(width, height);
    }

    public double imaginaryAt(
            double y,
            int height
    ) {
        if (height < 2) {
            throw new IllegalArgumentException("Height must be at least 2");
        }

        double clampedY = Math.clamp(y, 0.0, height - 1.0);

        double position = clampedY / (height - 1.0);

        return maxImaginary() - position * visibleHeight();
    }

    public Viewport zoomAt(
            double x,
            double y,
            int width,
            int height,
            double factor
    ) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("Zoom factor must be positive and finite");
        }

        double targetReal = realAt(x, width, height);
        double targetImaginary = imaginaryAt(y, height);

        double newCenterReal = targetReal + (centerReal - targetReal) * factor;
        double newCenterImaginary = targetImaginary + (centerImaginary - targetImaginary) * factor;

        return new Viewport(newCenterReal, newCenterImaginary, scale * factor);
    }

    public double realUnitsPerPixel(
            int width,
            int height
    ) {
        validateDimensions(width, height);

        return visibleWidth(width, height) / (width - 1.0);
    }

    public double imaginaryUnitsPerPixel(int height) {
        if (height < 2) {
            throw new IllegalArgumentException("Height must be at least 2");
        }

        return visibleHeight() / (height - 1.0);
    }

    public boolean hasSufficientPrecision(
            int width,
            int height,
            double minUlpsPerPixel
    ) {
        validateDimensions(width, height);

        double realUlp = Math.max(
                Math.ulp(minReal(width, height)),
                Math.ulp(maxReal(width, height))
        );

        double imaginaryUlp = Math.max(
                Math.ulp(minImaginary()),
                Math.ulp(maxImaginary())
        );

        double realUlpsPerPixel =
                realUnitsPerPixel(width, height) / realUlp;

        double imaginaryUlpsPerPixel =
                imaginaryUnitsPerPixel(height) / imaginaryUlp;

        return realUlpsPerPixel >= minUlpsPerPixel
                && imaginaryUlpsPerPixel >= minUlpsPerPixel;
    }

    // test
    public Viewport shiftedByPixels(
            int shiftX,
            int shiftY,
            int width,
            int height
    ) {
        validateDimensions(width, height);

        double realShift =
                shiftX
                        * realUnitsPerPixel(
                        width,
                        height
                );

        double imaginaryShift =
                shiftY
                        * imaginaryUnitsPerPixel(
                        height
                );

        return new Viewport(
                centerReal - realShift,
                centerImaginary + imaginaryShift,
                scale
        );
    }

    public Viewport snapToPixelGrid(
            Viewport reference,
            int width,
            int height
    ) {
        Objects.requireNonNull(reference);

        if (Double.compare(
                reference.scale(),
                scale
        ) != 0) {
            return this;
        }

        double rawShiftX =
                (reference.centerReal() - centerReal)
                        / reference.realUnitsPerPixel(
                        width,
                        height
                );

        double rawShiftY =
                (centerImaginary
                        - reference.centerImaginary())
                        / reference.imaginaryUnitsPerPixel(
                        height
                );

        int shiftX =
                Math.toIntExact(
                        Math.round(rawShiftX)
                );

        int shiftY =
                Math.toIntExact(
                        Math.round(rawShiftY)
                );

        return reference.shiftedByPixels(
                shiftX,
                shiftY,
                width,
                height
        );
    }
}