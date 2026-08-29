package com.shangin.fractal.render;

import com.shangin.fractal.scene.FractalScene;

import java.util.Objects;

/** A completed calculation paired with the exact scene snapshot it represents. */
public record CompletedRender(
        FractalScene scene,
        RenderFrame frame
) {
    public CompletedRender {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(frame);

        if (!frame.isComplete()) {
            throw new IllegalArgumentException("Completed render frame must be complete");
        }
    }
}
