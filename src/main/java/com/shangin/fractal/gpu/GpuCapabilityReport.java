package com.shangin.fractal.gpu;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A diagnostic snapshot; never exposes native Vulkan handles. */
public record GpuCapabilityReport(
        GpuPlatform platform,
        GpuRuntimeState state,
        List<GpuDevice> devices,
        Optional<GpuDevice> selectedDevice,
        String detail
) {
    public GpuCapabilityReport {
        platform = Objects.requireNonNull(platform);
        state = Objects.requireNonNull(state);
        devices = List.copyOf(Objects.requireNonNull(devices));
        selectedDevice = Objects.requireNonNull(selectedDevice);
        detail = Objects.requireNonNull(detail).strip();
        if (selectedDevice.isPresent() && !devices.contains(selectedDevice.get())) {
            throw new IllegalArgumentException("Selected device must be in the discovered devices");
        }
        if (state == GpuRuntimeState.AVAILABLE
                && selectedDevice.filter(device -> device.supports(GpuNumericCapability.FLOAT32)).isEmpty()) {
            throw new IllegalArgumentException("An available runtime requires a selected compute device");
        }
    }

    public Optional<GpuDevice> primaryDevice() {
        return selectedDevice;
    }

    public boolean supports(GpuNumericCapability capability) {
        return state == GpuRuntimeState.AVAILABLE
                && selectedDevice.filter(device -> device.supports(capability)).isPresent();
    }
}
