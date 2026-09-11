package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.PreciseComplex;
import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Maintains the current viewport and applies bounded, precision-safe pan,
 * zoom, resize, and grid-snapping operations.
 */
public final class FractalCamera {

    private static final BigDecimal ZOOM_IN_FACTOR = new BigDecimal("0.8");
    private static final BigDecimal ZOOM_OUT_FACTOR = new BigDecimal("1.25");

    private Viewport viewport;
    private FractalPreset preset;
    private int viewportWidth = -1;
    private int viewportHeight = -1;

    public FractalCamera(FractalPreset preset) {
        this.preset = Objects.requireNonNull(preset, "Preset must not be null");
        this.viewport = preset.defaultViewport();
    }

    public Viewport viewport() {
        return viewport;
    }

    public void setPreset(
            FractalPreset preset,
            int width,
            int height) {
        this.preset = Objects.requireNonNull(preset, "Preset must not be null");
        reset(width, height);
    }

    /** Replaces center and scale together without passing through a home frame. */
    public void goTo(Viewport destination, int width, int height, int renderWidth, int renderHeight) {
        validateDimensions(width, height);
        validateDimensions(renderWidth, renderHeight);
        Objects.requireNonNull(destination);
        if (mustRemainInDirectPrecision(destination, renderWidth, renderHeight)) {
            throw new IllegalArgumentException("Destination exceeds this fractal's precision limit");
        }
        viewport = destination;
        viewportWidth = width;
        viewportHeight = height;
    }

    public Viewport defaultViewport(
            int width,
            int height
    ) {
        return preset.defaultViewport().fit(
                preset.defaultWidth(),
                preset.defaultHeight(),
                width,
                height);
    }

    public void reset(
            int width,
            int height) {
        viewport = defaultViewport(width, height);
        viewportWidth = width;
        viewportHeight = height;
    }

    /** Zooms toward a logical screen point, entering Mandelbrot deep zoom when needed. */
    public boolean zoomIn(
            double x,
            double y,
            int logicalWidth,
            int logicalHeight,
            int renderWidth,
            int renderHeight
    ) {
        validateDimensions(renderWidth, renderHeight);
        Viewport requestedViewport = viewport.zoomAt(
                BigDecimal.valueOf(x), BigDecimal.valueOf(y),
                logicalWidth, logicalHeight, ZOOM_IN_FACTOR);
        if (mustRemainInDirectPrecision(requestedViewport, renderWidth, renderHeight)) {
            return false;
        }
        viewport = requestedViewport;
        return true;
    }

    public boolean zoomOut(
            double x,
            double y,
            int logicalWidth,
            int logicalHeight
    ) {
        Viewport defaultViewport = defaultViewport(logicalWidth, logicalHeight);

        if (viewport.scaleExact().compareTo(defaultViewport.scaleExact()) >= 0) {
            return false;
        }

        BigDecimal requestedScale = viewport.scaleExact().multiply(
                ZOOM_OUT_FACTOR, viewport.mathContext());

        if (requestedScale.compareTo(defaultViewport.scaleExact()) >= 0) {
            viewport = defaultViewport;
            return true;
        }

        viewport = viewport.zoomAt(
                BigDecimal.valueOf(x), BigDecimal.valueOf(y),
                logicalWidth, logicalHeight, ZOOM_OUT_FACTOR);
        return true;
    }

    /** Applies a continuous scale factor around a logical screen point. */
    public boolean zoomBy(
            double x,
            double y,
            double scaleFactor,
            int logicalWidth,
            int logicalHeight,
            int renderWidth,
            int renderHeight
    ) {
        validateDimensions(logicalWidth, logicalHeight);
        validateDimensions(renderWidth, renderHeight);

        if (!Double.isFinite(scaleFactor) || scaleFactor <= 0.0) {
            throw new IllegalArgumentException("Zoom scale factor must be positive and finite");
        }

        if (scaleFactor == 1.0) {
            return false;
        }

        Viewport defaultViewport = defaultViewport(logicalWidth, logicalHeight);
        BigDecimal exactFactor = BigDecimal.valueOf(scaleFactor);
        BigDecimal requestedScale = viewport.scaleExact().multiply(
                exactFactor, viewport.mathContext());

        if (scaleFactor > 1.0
                && requestedScale.compareTo(defaultViewport.scaleExact()) >= 0) {
            if (viewport.equals(defaultViewport)) {
                return false;
            }
            viewport = defaultViewport;
            return true;
        }

        Viewport requestedViewport = viewport.zoomAt(
                BigDecimal.valueOf(x),
                BigDecimal.valueOf(y),
                logicalWidth,
                logicalHeight,
                exactFactor
        );
        if (scaleFactor < 1.0
                && mustRemainInDirectPrecision(requestedViewport, renderWidth, renderHeight)) {
            return false;
        }
        viewport = requestedViewport;
        return true;
    }

    /** Mandelbrot has a dedicated perturbation backend; other formulas remain direct-only. */
    private boolean mustRemainInDirectPrecision(
            Viewport candidate,
            int renderWidth,
            int renderHeight
    ) {
        return preset != FractalPreset.MANDELBROT
                && !candidate.hasSufficientPrecision(
                renderWidth,
                renderHeight,
                preset.minimumUlpsPerPixel());
    }

    private void validateDimensions(
            int width,
            int height
    ) {
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Dimensions must be at least 2");
        }
    }

    public void resize(
            int width,
            int height
    ) {
        resize(width, height, viewportHeight, height);
    }

    public boolean isDefaultView() {
        return viewportWidth < 2 || viewportHeight < 2
                || viewport.equals(defaultViewport(viewportWidth, viewportHeight));
    }

    /** Keeps sample spacing in a navigated view; fits the preset only at home. */
    public void resize(int width, int height, int oldRenderHeight, int newRenderHeight) {
        validateDimensions(width, height);
        if (viewportWidth < 2 || viewportHeight < 2) {
            reset(width, height);
            return;
        }

        if (width == viewportWidth && height == viewportHeight
                && oldRenderHeight == newRenderHeight) {
            return;
        }

        if (isDefaultView()) {
            viewport = defaultViewport(width, height);
        } else {
            BigDecimal step = viewport.imaginaryUnitsPerPixelExact(oldRenderHeight);
            viewport = new Viewport(viewport.center(), step.multiply(
                    BigDecimal.valueOf(newRenderHeight - 1L), viewport.mathContext()));
        }
        viewportWidth = width;
        viewportHeight = height;
    }

    /** Pans in screen pixels while keeping the viewport within preset bounds. */
    public boolean pan(
            double deltaX,
            double deltaY,
            int width,
            int height
    ) {
        Viewport defaultViewport = defaultViewport(width, height);

        MathContext context = viewport.mathContext();
        BigDecimal deltaReal = viewport.visibleWidthExact(width, height)
                .multiply(BigDecimal.valueOf(-deltaX), context)
                .divide(BigDecimal.valueOf(width - 1L), context);
        BigDecimal deltaImaginary = viewport.visibleHeightExact()
                .multiply(BigDecimal.valueOf(deltaY), context)
                .divide(BigDecimal.valueOf(height - 1L), context);

        BigDecimal requestedCenterReal = viewport.center().real().add(deltaReal, context);
        BigDecimal requestedCenterImaginary = viewport.center().imaginary().add(deltaImaginary, context);

        BigDecimal halfWidth = viewport.visibleWidthExact(width, height)
                .divide(BigDecimal.valueOf(2), context);
        BigDecimal halfHeight = viewport.visibleHeightExact()
                .divide(BigDecimal.valueOf(2), context);

        BigDecimal minCenterReal = defaultViewport.minRealExact(width, height).add(halfWidth, context);
        BigDecimal maxCenterReal = defaultViewport.maxRealExact(width, height).subtract(halfWidth, context);
        BigDecimal minCenterImaginary = defaultViewport.minImaginaryExact().add(halfHeight, context);
        BigDecimal maxCenterImaginary = defaultViewport.maxImaginaryExact().subtract(halfHeight, context);

        BigDecimal newCenterReal = requestedCenterReal.max(minCenterReal).min(maxCenterReal);
        BigDecimal newCenterImaginary = requestedCenterImaginary.max(minCenterImaginary).min(maxCenterImaginary);

        PreciseComplex newCenter = new PreciseComplex(newCenterReal, newCenterImaginary);
        if (newCenter.equals(viewport.center())) {
            return false;
        }

        viewport = new Viewport(newCenter, viewport.scaleExact());

        return true;
    }

    /** Moves the viewport center to explicit complex-plane coordinates. */
    public boolean setCenter(
            double centerReal,
            double centerImaginary,
            int width,
            int height
    ) {
        validateDimensions(width, height);

        if (!Double.isFinite(centerReal) || !Double.isFinite(centerImaginary)) {
            throw new IllegalArgumentException("Center coordinates must be finite");
        }

        return setCenter(PreciseComplex.of(centerReal, centerImaginary), width, height);
    }

    public boolean setCenter(
            PreciseComplex center,
            int width,
            int height
    ) {
        validateDimensions(width, height);
        Objects.requireNonNull(center);
        if (center.equals(viewport.center())) {
            return false;
        }

        viewport = new Viewport(center, viewport.scaleExact());
        return true;
    }

    /** Aligns the current viewport with an earlier frame for exact pixel reuse. */
    public void snapToRenderGrid(
            Viewport reference,
            int renderWidth,
            int renderHeight
    ) {
        viewport =
                viewport.snapToPixelGrid(
                        reference,
                        renderWidth,
                        renderHeight
                );
    }


}
