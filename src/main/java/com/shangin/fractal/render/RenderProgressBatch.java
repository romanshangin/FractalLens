package com.shangin.fractal.render;

import java.util.List;
import java.util.Objects;

public record RenderProgressBatch(
        RenderFrame frame,
        List<RenderRegion> regions
) {
    public RenderProgressBatch {
        Objects.requireNonNull(frame);
        regions = List.copyOf(regions);
    }
}