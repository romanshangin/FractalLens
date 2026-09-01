package com.shangin.fractal.ui;

import com.shangin.fractal.scene.InteractiveRenderMode;

import java.util.Objects;

/** Defines which intermediate data may become visible for each display mode. */
final class InteractiveRenderPresentation {

    private InteractiveRenderPresentation() {}

    static boolean showsBaseProgress(InteractiveRenderMode renderMode) {
        Objects.requireNonNull(renderMode);
        return renderMode == InteractiveRenderMode.FAST;
    }
}
