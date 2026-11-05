package com.shangin.fractal.render;

import com.shangin.fractal.scene.WidePreviewSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WidePreviewInputBenchmarkTest {
    @Test
    void acceptsOnlyDeclaredSettingsConstants() throws Exception {
        for (String name : new String[]{"CANDIDATE", "DEFAULT"}) {
            assertEquals(name, WidePreviewInputBenchmark.widePreviewSettings(name));
            // The driver resolves the name reflectively; it must denote the production constant.
            Object constant = WidePreviewSettings.class.getField(name).get(null);
            assertSame(name.equals("DEFAULT") ? WidePreviewSettings.DEFAULT : WidePreviewSettings.CANDIDATE, constant);
        }
        for (String invalid : new String[]{"candidate", "", "OFF", "CANDIDATE "})
            assertThrows(IllegalArgumentException.class, () -> WidePreviewInputBenchmark.widePreviewSettings(invalid));
    }
}
