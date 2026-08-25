package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

public record RenderRequest(
        FractalCalculator calculator,
        Viewport viewport,
        int width,
        int height,
        int maxIterations,
        RenderPriority priority
) {
    public RenderRequest(
            FractalCalculator calculator,
            Viewport viewport,
            int width,
            int height,
            int maxIterations
    ) {
        this(
                calculator,
                viewport,
                width,
                height,
                maxIterations,
                RenderPriority.center()
        );
    }
}
