package com.shangin.fractal.ui;

import com.shangin.fractal.render.RenderFrame;

/** Decides whether an incompatible in-progress frame must remain as a preview. */
final class ProgressiveFrameRetention {

    private ProgressiveFrameRetention() {}

    static boolean shouldRetain(
            RenderFrame currentFrame,
            RenderFrame nextFrame,
            boolean progressVisible
    ) {
        return progressVisible
                && currentFrame != null
                && currentFrame != nextFrame;
    }
}
