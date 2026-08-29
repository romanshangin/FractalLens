package com.shangin.fractal.render;

import java.util.Objects;

/** Transient output dimensions and scheduling priority for a render. */
public record RenderTarget(
        int width,
        int height,
        RenderPriority priority
) {
    public RenderTarget {
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Render dimensions must be at least 2");
        }
        Objects.requireNonNull(priority);
    }

    public RenderTarget(int width, int height) {
        this(width, height, RenderPriority.center());
    }
}
