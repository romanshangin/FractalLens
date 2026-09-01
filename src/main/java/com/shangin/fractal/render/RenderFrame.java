package com.shangin.fractal.render;

import java.util.Objects;

/**
 * Mutable render state for one request: its stable coordinate grid, calculated
 * sample data, and a mask identifying pixels that are ready for display.
 */
public final class RenderFrame {

    private final RenderJob request;
    private final RenderGrid renderGrid;
    private final SamplePlane samplePlane;
    private final ValidityMask validity;

    private RenderFrame(
            RenderJob request,
            RenderGrid renderGrid,
            SamplePlane samplePlane,
            ValidityMask validity
    ) {
        this.request = Objects.requireNonNull(request);
        this.renderGrid = renderGrid;
        this.samplePlane = Objects.requireNonNull(samplePlane);
        this.validity = Objects.requireNonNull(validity);

        if (samplePlane.width() != request.width() || samplePlane.height() != request.height()) {
            throw new IllegalArgumentException("SamplePlane dimensions do not match RenderJob");
        }

        if (validity.width() != request.width() || validity.height() != request.height()) {
            throw new IllegalArgumentException("ValidityMask dimensions do not match RenderJob");
        }
    }

    /** Creates an empty frame using a grid derived from the request viewport. */
    public static RenderFrame create(RenderJob request) {
        Objects.requireNonNull(request);

        return create(
                request,
                RenderGrid.from(
                        request.viewport(),
                        request.width(),
                        request.height()));
    }

    public static RenderFrame create(
            RenderJob request,
            RenderGrid grid
    ) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(grid);

        FractalData fractalData = new FractalData(
                request.width(),
                request.height(),
                request.maxIterations()
        );

        ValidityMask validityMask = new ValidityMask(
                request.width(),
                request.height()
        );

        return new RenderFrame(
                request,
                grid,
                fractalData,
                validityMask
        );
    }

    /** Creates a frame over backend-provided sample storage. */
    public static RenderFrame create(
            RenderJob request,
            RenderGrid grid,
            SamplePlane samplePlane
    ) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(grid);
        Objects.requireNonNull(samplePlane);
        return new RenderFrame(
                request,
                grid,
                samplePlane,
                new ValidityMask(request.width(), request.height())
        );
    }

    public RenderJob request() {
        return request;
    }

    public RenderJob job() {
        return request;
    }

    public RenderGrid renderGrid() {
        return renderGrid;
    }

    public FractalData fractalData() {
        if (!(samplePlane instanceof FractalData fractalData)) {
            throw new IllegalStateException(
                    "This sample plane is not backed by direct CPU arrays");
        }
        return fractalData;
    }

    public SamplePlane samplePlane() {
        return samplePlane;
    }

    public ValidityMask validity() {
        return validity;
    }

    /** Returns whether every pixel in the frame contains a valid sample. */
    public boolean isComplete() {
        return validity.isComplete();
    }
}
