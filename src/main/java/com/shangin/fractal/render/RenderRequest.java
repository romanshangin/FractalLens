package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Optional;

/** @deprecated Use {@link RenderJob}. */
@Deprecated
public final class RenderRequest extends RenderJob {

    public RenderRequest(
            FractalCalculator calculator,
            Viewport viewport,
            int width,
            int height,
            int maxIterations,
            RenderPriority priority,
            Optional<RenderRegion> approximateCoverage
    ) {
        super(calculator.formulaDefinition(), viewport, width, height,
                maxIterations, priority, approximateCoverage);
    }

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
                RenderPriority.center(),
                Optional.empty()
        );
    }

    public RenderRequest(
            FractalCalculator calculator,
            Viewport viewport,
            int width,
            int height,
            int maxIterations,
            RenderPriority priority
    ) {
        this(calculator, viewport, width, height, maxIterations, priority, Optional.empty());
    }
}
