package com.shangin.fractal.gpu;

import java.util.Objects;
import java.util.Set;

/** Immutable description of a discovered physical device. */
public record GpuDevice(
        String name,
        String apiVersion,
        int vendorId,
        int deviceId,
        int computeQueueFamily,
        int maxComputeWorkGroupInvocations,
        long maxStorageBufferBytes,
        Set<GpuNumericCapability> numericCapabilities
) {

    public GpuDevice {
        name = Objects.requireNonNull(name).strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Device name must not be blank");
        }
        numericCapabilities = Set.copyOf(Objects.requireNonNull(numericCapabilities));
        apiVersion = Objects.requireNonNull(apiVersion);
        if (computeQueueFamily < -1 || maxComputeWorkGroupInvocations < 0 || maxStorageBufferBytes < 0) {
            throw new IllegalArgumentException("Invalid compute limits");
        }
    }

    public boolean supports(GpuNumericCapability capability) {
        return computeQueueFamily >= 0 && numericCapabilities.contains(capability);
    }
}
