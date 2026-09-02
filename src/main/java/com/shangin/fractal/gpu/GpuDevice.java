package com.shangin.fractal.gpu;

import java.util.Objects;
import java.util.Set;

/** Immutable description of a discovered physical device. */
public record GpuDevice(String name, Set<GpuNumericCapability> numericCapabilities) {

    public GpuDevice {
        name = Objects.requireNonNull(name).strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Device name must not be blank");
        }
        numericCapabilities = Set.copyOf(Objects.requireNonNull(numericCapabilities));
    }

    public boolean supports(GpuNumericCapability capability) {
        return numericCapabilities.contains(capability);
    }
}
