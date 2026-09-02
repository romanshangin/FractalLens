package com.shangin.fractal.gpu;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A diagnostic snapshot; never exposes native Vulkan handles. */
public record GpuCapabilityReport(
        GpuPlatform platform,
        GpuRuntimeState state,
        List<GpuDevice> devices,
        String detail
) {
    public GpuCapabilityReport {
        platform = Objects.requireNonNull(platform);
        state = Objects.requireNonNull(state);
        devices = List.copyOf(Objects.requireNonNull(devices));
        detail = Objects.requireNonNull(detail).strip();
    }

    public Optional<GpuDevice> primaryDevice() {
        return devices.stream().findFirst();
    }

    public boolean supports(GpuNumericCapability capability) {
        return state == GpuRuntimeState.AVAILABLE
                && devices.stream().anyMatch(device -> device.supports(capability));
    }
}
