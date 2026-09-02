package com.shangin.fractal.gpu;

import java.util.List;
import java.util.Objects;

class UnavailableGpuRuntime implements GpuRuntime {

    private final GpuPlatform platform;
    private String detail;
    private GpuRuntimeState state = GpuRuntimeState.UNAVAILABLE;

    UnavailableGpuRuntime(GpuPlatform platform, String detail) {
        this.platform = Objects.requireNonNull(platform);
        this.detail = Objects.requireNonNull(detail);
    }

    @Override
    public synchronized GpuCapabilityReport capabilityReport() {
        return new GpuCapabilityReport(platform, state, List.of(), detail);
    }

    @Override
    public synchronized void handleDeviceLoss(Throwable cause) {
        if (state != GpuRuntimeState.CLOSED) {
            state = GpuRuntimeState.DEVICE_LOST;
            detail = "GPU device lost: " + safeMessage(cause);
        }
    }

    @Override
    public synchronized void close() {
        state = GpuRuntimeState.CLOSED;
    }

    private static String safeMessage(Throwable cause) {
        return cause == null || cause.getMessage() == null ? "unknown cause" : cause.getMessage();
    }
}
