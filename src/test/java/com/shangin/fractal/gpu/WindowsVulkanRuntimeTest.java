package com.shangin.fractal.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowsVulkanRuntimeTest {
    @Test
    void unsupportedArchitecturesNeverLoadNatives() {
        for (String arch : new String[]{"aarch64", "x86", ""}) {
            try (GpuRuntime runtime = WindowsVulkanRuntime.discover(arch,
                    () -> fail("Native loading on unsupported architecture"))) {
                assertEquals(GpuPlatform.WINDOWS, runtime.capabilityReport().platform());
                assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
            }
        }
    }

    @Test
    void missingLoaderOrNativesRetainCpuFallbackOnBothX64Aliases() throws Exception {
        for (String arch : new String[]{"amd64", "x86_64", "AMD64"}) {
            try (GpuRuntime runtime = WindowsVulkanRuntime.discover(arch,
                    () -> { throw new UnsatisfiedLinkError("Vulkan loader unavailable"); })) {
                assertEquals(GpuPlatform.WINDOWS, runtime.capabilityReport().platform());
                assertEquals(GpuRuntimeState.UNAVAILABLE, runtime.capabilityReport().state());
                assertTrue(runtime.capabilityReport().detail().contains("Vulkan loader unavailable"));
                assertEquals("cpu", runtime.runOrFallback(GpuNumericCapability.FLOAT32,
                        () -> fail("Unavailable GPU used"), () -> "cpu"));
            }
        }
    }
}
