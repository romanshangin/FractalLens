package com.shangin.fractal.render;

import java.util.List;
import java.util.Objects;

public record RenderProgressBatch(
        FractalData data,
        List<RenderRegion> regions
) {
    public RenderProgressBatch {
        Objects.requireNonNull(data);
        regions = List.copyOf(regions);
    }
}