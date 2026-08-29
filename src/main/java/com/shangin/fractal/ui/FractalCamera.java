package com.shangin.fractal.ui;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;

import java.util.Objects;

/**
 * Maintains the current viewport and applies bounded, precision-safe pan,
 * zoom, resize, and grid-snapping operations.
 */
public final class FractalCamera {

    private static final double MIN_ULPS_PER_PIXEL = 16.0;
    private static final double ZOOM_IN_FACTOR = 0.8;
    private static final double ZOOM_OUT_FACTOR = 1.25;

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

    /** Zooms toward a logical screen point if double precision remains sufficient. */
    public boolean zoomIn(
            double x,
            double y,
            int logicalWidth,
            int logicalHeight,
            int renderWidth,
            int renderHeight
    ) {
        Viewport candidate = viewport.zoomAt(x, y, logicalWidth, logicalHeight, ZOOM_IN_FACTOR);

        if (!candidate.hasSufficientPrecision(renderWidth, renderHeight, MIN_ULPS_PER_PIXEL)) {
            return false;
        }

        viewport = candidate;
        return true;
    }

    public boolean zoomOut(
            double x,
            double y,
            int logicalWidth,
            int logicalHeight
    ) {
        Viewport defaultViewport = defaultViewport(logicalWidth, logicalHeight);

        if (viewport.scale() >= defaultViewport.scale()) {
            return false;
        }

        double requestedScale = viewport.scale() * ZOOM_OUT_FACTOR;

        if (requestedScale >= defaultViewport.scale()) {
            viewport = defaultViewport;
            return true;
        }

        viewport = viewport.zoomAt(x, y, logicalWidth, logicalHeight, ZOOM_OUT_FACTOR);
        return true;
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
        validateDimensions(width, height);
        if (viewportWidth < 2 || viewportHeight < 2) {
            reset(width, height);
            return;
        }

        if (width == viewportWidth && height == viewportHeight) {
            return;
        }

        Viewport oldDefault = defaultViewport(viewportWidth, viewportHeight);
        Viewport newDefault = defaultViewport(width, height);

        double relativeScale = viewport.scale() / oldDefault.scale();
        relativeScale = Math.min(1.0, relativeScale);
        double newScale = newDefault.scale() * relativeScale;

        viewport = new Viewport(viewport.centerReal(), viewport.centerImaginary(), newScale);
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

        double deltaReal = -deltaX * viewport.visibleWidth(width, height) / (width - 1.0);
        double deltaImaginary = deltaY * viewport.visibleHeight() / (height - 1.0);

        double requestedCenterReal = viewport.centerReal() + deltaReal;
        double requestedCenterImaginary = viewport.centerImaginary() + deltaImaginary;

        double halfWidth = viewport.visibleWidth(width, height) / 2.0;
        double halfHeight = viewport.visibleHeight() / 2.0;

        double minCenterReal = defaultViewport.minReal(width, height) + halfWidth;
        double maxCenterReal = defaultViewport.maxReal(width, height) - halfWidth;

        double minCenterImaginary = defaultViewport.minImaginary() + halfHeight;
        double maxCenterImaginary = defaultViewport.maxImaginary() - halfHeight;

        double newCenterReal = Math.clamp(requestedCenterReal, minCenterReal, maxCenterReal);
        double newCenterImaginary = Math.clamp(requestedCenterImaginary, minCenterImaginary, maxCenterImaginary);

        if (newCenterReal == viewport.centerReal() && newCenterImaginary == viewport.centerImaginary()) {
            return false;
        }

        viewport = new Viewport(newCenterReal, newCenterImaginary, viewport.scale());

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

        if (centerReal == viewport.centerReal()
                && centerImaginary == viewport.centerImaginary()) {
            return false;
        }

        viewport = new Viewport(centerReal, centerImaginary, viewport.scale());
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
