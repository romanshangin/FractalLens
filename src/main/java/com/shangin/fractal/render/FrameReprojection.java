package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Objects;
import java.util.Optional;

/**
 * Maps the visible extent of an older frame into a zoomed-out target frame.
 * The returned region is a display-only scheduling hint: its pixels are an
 * approximate scaled preview and must not be marked valid in a RenderFrame.
 */
public final class FrameReprojection {

    private FrameReprojection() {}

    public static Optional<RenderRegion> approximateCoverage(
            Viewport source,
            Viewport target,
            int width,
            int height
    ) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);

        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Frame dimensions must be at least 2");
        }

        /* Scaling an older image is useful as coverage only while zooming out. */
        if (target.scale() <= source.scale()) {
            return Optional.empty();
        }

        double left = target.xAt(source.minReal(width, height), width, height);
        double right = target.xAt(source.maxReal(width, height), width, height);
        double top = target.yAt(source.maxImaginary(), height);
        double bottom = target.yAt(source.minImaginary(), height);

        int xFrom = clamp((int) Math.ceil(Math.min(left, right)), 0, width);
        int xTo = clamp((int) Math.floor(Math.max(left, right)) + 1, 0, width);
        int yFrom = clamp((int) Math.ceil(Math.min(top, bottom)), 0, height);
        int yTo = clamp((int) Math.floor(Math.max(top, bottom)) + 1, 0, height);

        if (xFrom >= xTo || yFrom >= yTo) {
            return Optional.empty();
        }

        return Optional.of(new RenderRegion(
                xFrom,
                yFrom,
                xTo - xFrom,
                yTo - yFrom
        ));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
