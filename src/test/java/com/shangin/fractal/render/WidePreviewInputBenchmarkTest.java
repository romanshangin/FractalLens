package com.shangin.fractal.render;

import com.shangin.fractal.scene.WidePreviewSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WidePreviewInputBenchmarkTest {
    @Test
    void acceptsOnlyDeclaredSettingsConstants() throws Exception {
        var constants = java.util.Map.of("CANDIDATE", WidePreviewSettings.CANDIDATE,
                "DEFAULT", WidePreviewSettings.DEFAULT, "DISABLED", WidePreviewSettings.DISABLED);
        for (var entry : constants.entrySet()) {
            assertEquals(entry.getKey(), WidePreviewInputBenchmark.widePreviewSettings(entry.getKey()));
            // The driver resolves the name reflectively; it must denote the production constant.
            assertSame(entry.getValue(), WidePreviewSettings.class.getField(entry.getKey()).get(null));
        }
        for (String invalid : new String[]{"candidate", "", "OFF", "CANDIDATE "})
            assertThrows(IllegalArgumentException.class, () -> WidePreviewInputBenchmark.widePreviewSettings(invalid));
    }
}
