package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

public record RenderRequest(
        FractalCalculator calculator,
        Viewport viewport,
        int width,
        int height,
        int maxIterations
) {
}
