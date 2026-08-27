package com.shangin.fractal.render;

import java.util.Optional;

public record FrameReuseResult(
        RenderFrame frame,
        Optional<PixelShift> shift,
        int reusedPixels
) {
    public boolean reused() {
        return shift.isPresent()
                && reusedPixels > 0;
    }

    public static FrameReuseResult fresh(
            RenderFrame frame
    ) {
        return new FrameReuseResult(
                frame,
                Optional.empty(),
                0
        );
    }

    public static FrameReuseResult reused(
            RenderFrame frame,
            PixelShift shift,
            int reusedPixels
    ) {
        return new FrameReuseResult(
                frame,
                Optional.of(shift),
                reusedPixels
        );
    }
}