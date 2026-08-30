package com.shangin.fractal.ui;

import com.shangin.fractal.render.RenderFrame;

/** Decides whether an incompatible in-progress frame must remain as a preview. */
final class ProgressiveFrameRetention {

    enum SourceSlot {
        DISPLAYED,
        RETAINED,
        NONE
    }

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

    static boolean shouldReplaceRetained(
            RenderFrame currentFrame,
            RenderFrame nextFrame,
            boolean progressVisible,
            RenderFrame plannerSource,
            RenderFrame retainedFrame
    ) {
        return shouldRetain(currentFrame, nextFrame, progressVisible)
                && (plannerSource == null || plannerSource != retainedFrame);
    }

    static SourceSlot reusableSource(
            RenderFrame requestedFrame,
            RenderFrame displayedFrame,
            RenderFrame retainedFrame
    ) {
        if (requestedFrame != null && requestedFrame == displayedFrame) {
            return SourceSlot.DISPLAYED;
        }
        if (requestedFrame != null && requestedFrame == retainedFrame) {
            return SourceSlot.RETAINED;
        }
        return SourceSlot.NONE;
    }
}
