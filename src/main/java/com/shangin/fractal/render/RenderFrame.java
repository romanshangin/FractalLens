package com.shangin.fractal.render;

import java.util.Objects;

public final class RenderFrame {

    private final RenderRequest request;
    private final RenderGrid renderGrid;
    private final FractalData fractalData;
    private final ValidityMask validity;

    private RenderFrame(
            RenderRequest request,
            RenderGrid renderGrid,
            FractalData fractalData,
            ValidityMask validity
    ) {
        this.request = Objects.requireNonNull(request);
        this.renderGrid = renderGrid;
        this.fractalData = Objects.requireNonNull(fractalData);
        this.validity = Objects.requireNonNull(validity);

        if (fractalData.width() != request.width() || fractalData.height() != request.height()) {
            throw new IllegalArgumentException("FractalData dimensions do not match RenderRequest");
        }

        if (validity.width() != request.width() || validity.height() != request.height()) {
            throw new IllegalArgumentException("ValidityMask dimensions do not match RenderRequest");
        }
    }

    public static RenderFrame create(RenderRequest request) {
        Objects.requireNonNull(request);

        return create(
                request,
                RenderGrid.from(
                        request.viewport(),
                        request.width(),
                        request.height()));
    }

    public static RenderFrame create(
            RenderRequest request,
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

    public RenderRequest request() {
        return request;
    }

    public RenderGrid renderGrid() {
        return renderGrid;
    }

    public FractalData fractalData() {
        return fractalData;
    }

    public ValidityMask validity() {
        return validity;
    }

    public boolean isComplete() {
        return validity.isComplete();
    }
}