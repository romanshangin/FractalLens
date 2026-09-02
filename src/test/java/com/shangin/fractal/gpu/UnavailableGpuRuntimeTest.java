package com.shangin.fractal.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnavailableGpuRuntimeTest {

    @Test
    void deviceLossDisablesTheRuntimeUntilShutdown() {
        GpuRuntime runtime = new UnavailableGpuRuntime(
                GpuPlatform.MACOS, "MoltenVK unavailable");

        runtime.handleDeviceLoss(new IllegalStateException("VK_ERROR_DEVICE_LOST"));

        GpuCapabilityReport report = runtime.capabilityReport();
        assertEquals(GpuRuntimeState.DEVICE_LOST, report.state());
        assertTrue(report.detail().contains("VK_ERROR_DEVICE_LOST"));
        assertFalse(runtime.isUsableFor(GpuNumericCapability.FLOAT32));

        runtime.close();
        assertEquals(GpuRuntimeState.CLOSED, runtime.capabilityReport().state());
    }

    @Test
    void deviceDescriptionCopiesItsCapabilities() {
        var capabilities = new java.util.HashSet<>(java.util.Set.of(GpuNumericCapability.FLOAT32));
        GpuDevice device = new GpuDevice("Apple GPU", "1.1.0", 0, 0, 0, 256, 1_048_576, capabilities);
        capabilities.clear();

        assertTrue(device.supports(GpuNumericCapability.FLOAT32));
        assertFalse(device.supports(GpuNumericCapability.FLOAT64));
    }
}
