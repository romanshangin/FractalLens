package com.shangin.fractal.ui;

import com.shangin.fractal.scene.InteractiveRenderMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractiveRenderPresentationTest {

    @Test
    void fastModeShouldExposeBasePassProgress() {
        assertTrue(InteractiveRenderPresentation.showsBaseProgress(
                InteractiveRenderMode.FAST));
    }

    @Test
    void refinedModeShouldKeepBasePassProgressHidden() {
        assertFalse(InteractiveRenderPresentation.showsBaseProgress(
                InteractiveRenderMode.REFINED));
    }
}
