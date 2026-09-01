package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds render frames for new requests and reuses compatible pixel data from
 * an earlier frame when the viewport movement maps to an integer pixel shift.
 */
public final class FrameReusePlanner {

    private static final double INTEGER_SHIFT_EPSILON = 1e-6;
    private static final double MAX_INTEGER_SHIFT_UNCERTAINTY = 0.25;

    /**
     * Creates a frame for the target request, reusing compatible source data
     * when possible.
     */
    public RenderFrame createFrame(
            RenderFrame sourceFrame,
            RenderJob targetRequest
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
            RenderJob targetRequest
    ) {
        Objects.requireNonNull(
                targetRequest,
                "Target request must not be null"
        );

        return planCandidate(
                sourceFrame,
                targetRequest
        ).orElseGet(
                () -> FrameReuseResult.fresh(
                        RenderFrame.create(targetRequest)
                )
        );
    }

    /** Checks the active frame and then the retained frame for reusable data. */
    public FrameReuseSelection plan(
            RenderFrame activeFrame,
            RenderFrame retainedFrame,
            RenderJob targetRequest
    ) {
        Objects.requireNonNull(targetRequest, "Target request must not be null");

        Optional<FrameReuseResult> activeResult =
                planCandidate(activeFrame, targetRequest)
                        .filter(FrameReuseResult::reused);

        if (activeResult.isPresent()) {
            return new FrameReuseSelection(activeFrame, activeResult.get());
        }

        Optional<FrameReuseResult> retainedResult =
                planCandidate(retainedFrame, targetRequest)
                        .filter(FrameReuseResult::reused);

        if (retainedResult.isPresent()) {
            return new FrameReuseSelection(retainedFrame, retainedResult.get());
        }

        return new FrameReuseSelection(
                null,
                FrameReuseResult.fresh(RenderFrame.create(targetRequest))
        );
    }

    private Optional<FrameReuseResult> planCandidate(
            RenderFrame sourceFrame,
            RenderJob targetRequest
    ) {
        if (sourceFrame == null || !canReuse(sourceFrame, targetRequest)) {
            return Optional.empty();
        }

        Optional<PixelShift> shift =
                calculatePixelShift(
                        sourceFrame,
                        targetRequest
                );

        if (shift.isEmpty()) {
            return Optional.empty();
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

        return Optional.of(
                FrameReuseResult.reused(
                        targetFrame,
                        pixelShift,
                        reusedPixels
                )
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
        targetFrame.samplePlane()
                .copyRegionFrom(
                        sourceFrame.samplePlane(),
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
            RenderJob targetRequest
    ) {
        RenderJob sourceRequest =
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

        /* Formula identity and parameters prevent incompatible sample reuse. */
        if (!sourceRequest.formula().equals(targetRequest.formula())) {
            return false;
        }

        /* Partial pan reuse currently requires an unchanged viewport scale. */
        return sourceRequest.viewport().scaleExact().compareTo(
                targetRequest.viewport().scaleExact()) == 0;
    }

    private Optional<PixelShift> calculatePixelShift(
            RenderFrame sourceFrame,
            RenderJob targetRequest
    ) {
        Viewport sourceViewport =
                sourceFrame.request()
                        .viewport();

        Viewport targetViewport =
                targetRequest.viewport();

        RenderGrid grid =
                sourceFrame.renderGrid();

        PreciseRenderGrid preciseGrid = grid.preciseGrid();
        if (preciseGrid != null) {
            BigDecimal rawShiftX = sourceViewport.center().real()
                    .subtract(targetViewport.center().real(), sourceViewport.mathContext())
                    .divide(preciseGrid.realStep(), sourceViewport.mathContext());
            BigDecimal rawShiftY = targetViewport.center().imaginary()
                    .subtract(sourceViewport.center().imaginary(), sourceViewport.mathContext())
                    .divide(preciseGrid.imaginaryStep(), sourceViewport.mathContext());
            Optional<Integer> preciseX = toIntegerShift(rawShiftX);
            Optional<Integer> preciseY = toIntegerShift(rawShiftY);
            if (preciseX.isPresent() && preciseY.isPresent()) {
                return Optional.of(new PixelShift(preciseX.get(), preciseY.get()));
            }
            return Optional.empty();
        }

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
                        rawShiftX,
                        pixelShiftUncertainty(
                                sourceViewport.centerReal(),
                                targetViewport.centerReal(),
                                grid.realStep()
                        )
                );

        Optional<Integer> shiftY =
                toIntegerShift(
                        rawShiftY,
                        pixelShiftUncertainty(
                                sourceViewport.centerImaginary(),
                                targetViewport.centerImaginary(),
                                grid.imaginaryStep()
                        )
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

    private Optional<Integer> toIntegerShift(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        BigDecimal difference = value.subtract(rounded).abs();
        if (difference.compareTo(BigDecimal.valueOf(INTEGER_SHIFT_EPSILON)) > 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(rounded.intValueExact());
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
    }

    private Optional<Integer> toIntegerShift(
            double value,
            double uncertainty
    ) {
        if (!Double.isFinite(value)
                || !Double.isFinite(uncertainty)
                || uncertainty >= MAX_INTEGER_SHIFT_UNCERTAINTY) {
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
        double tolerance = Math.max(
                INTEGER_SHIFT_EPSILON,
                uncertainty
                        + 2.0 * Math.ulp(value)
        );

        if (Math.abs(value - rounded) > tolerance) {
            return Optional.empty();
        }

        return Optional.of(
                (int) rounded
        );
    }

    private double pixelShiftUncertainty(
            double sourceCoordinate,
            double targetCoordinate,
            double pixelStep
    ) {
        if (!Double.isFinite(pixelStep) || pixelStep == 0.0) {
            return Double.POSITIVE_INFINITY;
        }

        /*
         * At deep zooms a center coordinate can move only in whole ULPs. A
         * viewport snapped to an integer render-pixel shift therefore may
         * decode a few hundredths away from that integer. Bound that expected
         * representation error in pixel units instead of applying one fixed
         * epsilon at every zoom depth.
         */
        return (Math.ulp(sourceCoordinate) + Math.ulp(targetCoordinate))
                / Math.abs(pixelStep);
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

        SamplePlane sourceData = sourceFrame.samplePlane();

        SamplePlane targetData = targetFrame.samplePlane();

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
