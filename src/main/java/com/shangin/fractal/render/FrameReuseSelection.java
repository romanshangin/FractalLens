package com.shangin.fractal.render;

/** Identifies the retained source selected for a frame-reuse plan. */
public record FrameReuseSelection(
        RenderFrame sourceFrame,
        FrameReuseResult result
) {}
