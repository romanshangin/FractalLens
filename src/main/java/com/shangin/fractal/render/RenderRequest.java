package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Objects;
import java.util.Optional;

public record RenderRequest(
        FractalCalculator calculator,
        Viewport viewport,
        int width,
        int height,
        int maxIterations,
        RenderPriority priority,
        Optional<RenderRegion> approximateCoverage
) {
    public RenderRequest {
        Objects.requireNonNull(calculator);
        Objects.requireNonNull(viewport);
        Objects.requireNonNull(priority);
        Objects.requireNonNull(approximateCoverage);

        approximateCoverage.ifPresent(region -> {
            if (region.x() < 0 || region.y() < 0
                    || region.width() < 1 || region.height() < 1
                    || region.x() + region.width() > width
                    || region.y() + region.height() > height) {
                throw new IllegalArgumentException(
                        "Approximate coverage must be inside the render target");
            }
        });
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
