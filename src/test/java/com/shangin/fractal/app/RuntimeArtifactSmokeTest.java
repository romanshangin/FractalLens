package com.shangin.fractal.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeArtifactSmokeTest {

    @Test
    void validatesCpuFallbackAndPackagedNativeResources() throws Exception {
        var checks = RuntimeArtifactSmoke.runChecks();

        assertEquals("available", checks.get("cpu_fallback"));
        assertEquals("complete", checks.get("cpu_render"));
        assertEquals("ok", checks.get("status"));
        assertEquals(System.getProperty("os.name", "").startsWith("Mac")
                        ? "available" : "not_applicable",
                checks.get("appkit_bridge"));
    }
}
