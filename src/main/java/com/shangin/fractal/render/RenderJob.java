package com.shangin.fractal.render;

import com.shangin.fractal.math.Viewport;

import java.util.Objects;
import java.util.Optional;

/** Immutable backend-neutral description of one render calculation. */
public class RenderJob {

    private final FormulaDefinition formula;
    private final Viewport viewport;
    private final int width;
    private final int height;
    private final int maxIterations;
    private final RenderPriority priority;
    private final Optional<RenderRegion> approximateCoverage;

    public RenderJob(
            FormulaDefinition formula,
            Viewport viewport,
            int width,
            int height,
            int maxIterations,
            RenderPriority priority,
            Optional<RenderRegion> approximateCoverage
    ) {
        this.formula = Objects.requireNonNull(formula);
        this.viewport = Objects.requireNonNull(viewport);
        this.priority = Objects.requireNonNull(priority);
        this.approximateCoverage = Objects.requireNonNull(approximateCoverage);
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Render dimensions must be at least 2");
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("Maximum iterations must be positive");
        }
        this.width = width;
        this.height = height;
        this.maxIterations = maxIterations;

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

    public RenderJob(
            FormulaDefinition formula,
            Viewport viewport,
            int width,
            int height,
            int maxIterations
    ) {
        this(formula, viewport, width, height, maxIterations,
                RenderPriority.center(), Optional.empty());
    }

    public FormulaDefinition formula() { return formula; }
    public Viewport viewport() { return viewport; }
    public int width() { return width; }
    public int height() { return height; }
    public int maxIterations() { return maxIterations; }
    public RenderPriority priority() { return priority; }
    public Optional<RenderRegion> approximateCoverage() { return approximateCoverage; }

    /** Derives a backend-neutral grid without converting coordinates to doubles. */
    public PreciseRenderGrid preciseGrid() {
        return PreciseRenderGrid.from(viewport, width, height);
    }
}
