package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Objects;
import java.util.Optional;

/**
 * Builds render frames for new requests and reuses compatible pixel data from
 * an earlier frame when the viewport movement maps to an integer pixel shift.
 */
public final class FrameReusePlanner {

    private static final double INTEGER_SHIFT_EPSILON = 1e-6;

    /**
     * Creates a frame for the target request, reusing compatible source data
     * when possible.
     */
    public RenderFrame createFrame(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        return plan(
                sourceFrame,
                targetRequest
        ).frame();
    }

    /**
     * Plans frame reuse and returns both the target frame and reuse metadata.
     */
    public FrameReuseResult plan(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        Objects.requireNonNull(
                targetRequest,
                "Target request must not be null"
        );

        if (sourceFrame == null
                || !canReuse(
                sourceFrame,
                targetRequest
        )) {

            return FrameReuseResult.fresh(
                    RenderFrame.create(
                            targetRequest
                    )
            );
        }

        Optional<PixelShift> shift =
                calculatePixelShift(
                        sourceFrame,
                        targetRequest
                );

        if (shift.isEmpty()) {
            return FrameReuseResult.fresh(
                    RenderFrame.create(
                            targetRequest
                    )
            );
        }

        PixelShift pixelShift =
                shift.get();

        /*
         * Extend the source render grid through integer offsets instead of
         * rebuilding it from the target viewport. This preserves bit-for-bit
         * coordinates for every reused pixel.
         */
        RenderGrid targetGrid =
                sourceFrame.renderGrid()
                        .shifted(
                                pixelShift
                        );

        RenderFrame targetFrame =
                RenderFrame.create(
                        targetRequest,
                        targetGrid
                );

        int reusedPixels;

        if (sourceFrame.isComplete()) {

            reusedPixels =
                    copyReusableRectangle(
                            sourceFrame,
                            targetFrame,
                            pixelShift
                    );

        } else {

            reusedPixels =
                    copyReusablePixels(
                            sourceFrame,
                            targetFrame,
                            pixelShift
                    );
        }

        return FrameReuseResult.reused(
                targetFrame,
                pixelShift,
                reusedPixels
        );
    }

    private int copyReusableRectangle(
            RenderFrame sourceFrame,
            RenderFrame targetFrame,
            PixelShift shift
    ) {
        int width =
                sourceFrame.request()
                        .width();

        int height =
                sourceFrame.request()
                        .height();

        /* Find the source rectangle that remains inside the shifted target. */
        int sourceXFrom =
                Math.max(
                        0,
                        -shift.dx()
                );

        int sourceXTo =
                Math.min(
                        width,
                        width - shift.dx()
                );

        int sourceYFrom =
                Math.max(
                        0,
                        -shift.dy()
                );

        int sourceYTo =
                Math.min(
                        height,
                        height - shift.dy()
                );

        /* The frames do not overlap. */
        if (sourceXFrom >= sourceXTo
                || sourceYFrom >= sourceYTo) {
            return 0;
        }

        int reusableWidth =
                sourceXTo - sourceXFrom;

        int reusableHeight =
                sourceYTo - sourceYFrom;

        int targetXFrom =
                sourceXFrom + shift.dx();

        int targetYFrom =
                sourceYFrom + shift.dy();

        /* Every source pixel is valid because this path requires a complete frame. */
        targetFrame.fractalData()
                .copyRegionFrom(
                        sourceFrame.fractalData(),
                        sourceXFrom,
                        sourceYFrom,
                        targetXFrom,
                        targetYFrom,
                        reusableWidth,
                        reusableHeight
                );

        /* Mark the complete overlap ready in row-sized BitSet ranges. */
        targetFrame.validity()
                .markReady(
                        new RenderRegion(
                                targetXFrom,
                                targetYFrom,
                                reusableWidth,
                                reusableHeight
                        )
                );

        return Math.multiplyExact(
                reusableWidth,
                reusableHeight
        );
    }

    private boolean canReuse(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        RenderRequest sourceRequest =
                sourceFrame.request();

        /* Reuse across different render dimensions is not supported yet. */
        if (sourceRequest.width()
                != targetRequest.width()) {
            return false;
        }

        if (sourceRequest.height()
                != targetRequest.height()) {
            return false;
        }

        /* Samples calculated with different iteration limits are not equivalent. */
        if (sourceRequest.maxIterations()
                != targetRequest.maxIterations()) {
            return false;
        }

        /* Calculator identity prevents reuse across formulas or formula parameters. */
        if (sourceRequest.calculator()
                != targetRequest.calculator()) {
            return false;
        }

        /* Partial pan reuse currently requires an unchanged viewport scale. */
        return Double.compare(
                sourceRequest.viewport().scale(),
                targetRequest.viewport().scale()
        ) == 0;
    }

    private Optional<PixelShift> calculatePixelShift(
            RenderFrame sourceFrame,
            RenderRequest targetRequest
    ) {
        Viewport sourceViewport =
                sourceFrame.request()
                        .viewport();

        Viewport targetViewport =
                targetRequest.viewport();

        RenderGrid grid =
                sourceFrame.renderGrid();

        /*
         * PixelShift uses target = source + shift. Moving the target viewport
         * left in the complex plane moves the old image right on screen and
         * therefore produces a positive horizontal shift.
         */
        double rawShiftX =
                (sourceViewport.centerReal()
                        - targetViewport.centerReal())
                        / grid.realStep();

        /* The imaginary axis points up while screen Y points down. */
        double rawShiftY =
                (targetViewport.centerImaginary()
                        - sourceViewport.centerImaginary())
                        / grid.imaginaryStep();

        Optional<Integer> shiftX =
                toIntegerShift(
                        rawShiftX
                );

        Optional<Integer> shiftY =
                toIntegerShift(
                        rawShiftY
                );

        if (shiftX.isEmpty()
                || shiftY.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(
                new PixelShift(
                        shiftX.get(),
                        shiftY.get()
                )
        );
    }

    private Optional<Integer> toIntegerShift(
            double value
    ) {
        if (!Double.isFinite(value)) {
            return Optional.empty();
        }

        long rounded =
                Math.round(value);

        if (rounded < Integer.MIN_VALUE
                || rounded > Integer.MAX_VALUE) {
            return Optional.empty();
        }

        /*
         * Grid snapping can produce values such as 340.000000000001. Accept
         * that floating-point noise while rejecting true fractional shifts.
         */
        if (Math.abs(value - rounded)
                > INTEGER_SHIFT_EPSILON) {
            return Optional.empty();
        }

        return Optional.of(
                (int) rounded
        );
    }

    private int copyReusablePixels(
            RenderFrame sourceFrame,
            RenderFrame targetFrame,
            PixelShift shift
    ) {
        int width =
                sourceFrame.request()
                        .width();

        int height =
                sourceFrame.request()
                        .height();

        /* Find the source area that remains inside the shifted target frame. */
        int sourceXFrom =
                Math.max(
                        0,
                        -shift.dx()
                );

        int sourceXTo =
                Math.min(
                        width,
                        width - shift.dx()
                );

        int sourceYFrom =
                Math.max(
                        0,
                        -shift.dy()
                );

        int sourceYTo =
                Math.min(
                        height,
                        height - shift.dy()
                );

        /* The frames do not overlap. */
        if (sourceXFrom >= sourceXTo
                || sourceYFrom >= sourceYTo) {
            return 0;
        }

        FractalData sourceData =
                sourceFrame.fractalData();

        FractalData targetData =
                targetFrame.fractalData();

        ValidityMask sourceValidity =
                sourceFrame.validity();

        ValidityMask targetValidity =
                targetFrame.validity();

        int reusedPixels = 0;

        for (int sourceY = sourceYFrom;
             sourceY < sourceYTo;
             sourceY++) {

            int targetY =
                    sourceY + shift.dy();

            /* Group adjacent valid pixels into row runs to reduce mask updates. */
            int runStartTargetX = -1;

            for (int sourceX = sourceXFrom;
                 sourceX < sourceXTo;
                 sourceX++) {

                int targetX =
                        sourceX + shift.dx();

                if (sourceValidity.isReady(
                        sourceX,
                        sourceY
                )) {

                    /*
                     * target <- source
                     */
                    targetData.copyPixelFrom(
                            sourceData,
                            sourceX,
                            sourceY,
                            targetX,
                            targetY
                    );

                    reusedPixels++;

                    if (runStartTargetX < 0) {
                        runStartTargetX =
                                targetX;
                    }

                } else if (runStartTargetX >= 0) {

                    /* The contiguous run of ready pixels has ended. */
                    markRunReady(
                            targetValidity,
                            runStartTargetX,
                            targetX,
                            targetY
                    );

                    runStartTargetX = -1;
                }
            }

            /* Flush a ready run that ends at the right edge of the overlap. */
            if (runStartTargetX >= 0) {

                int targetXTo =
                        sourceXTo
                                + shift.dx();

                markRunReady(
                        targetValidity,
                        runStartTargetX,
                        targetXTo,
                        targetY
                );
            }
        }

        return reusedPixels;
    }

    private void markRunReady(
            ValidityMask validity,
            int xFrom,
            int xTo,
            int y
    ) {
        if (xFrom >= xTo) {
            return;
        }

        validity.markReady(
                new RenderRegion(
                        xFrom,
                        y,
                        xTo - xFrom,
                        1
                )
        );
    }
}
