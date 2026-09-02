package com.shangin.fractal.gpu;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class UnavailableGpuRuntime implements GpuRuntime {

    private final GpuPlatform platform;
    private String detail;
    private GpuRuntimeState state = GpuRuntimeState.UNAVAILABLE;

    UnavailableGpuRuntime(GpuPlatform platform, String detail) {
        this.platform = Objects.requireNonNull(platform);
        this.detail = Objects.requireNonNull(detail);
    }

    @Override
    public synchronized GpuCapabilityReport capabilityReport() {
        return new GpuCapabilityReport(platform, state, List.of(), Optional.empty(), detail);
    }

    @Override
    public boolean checkHealth() {
        return false;
    }

    @Override
    public <T> T runOrFallback(GpuNumericCapability required, GpuWork<T> gpu, GpuWork<T> cpu)
            throws InterruptedException {
        Objects.requireNonNull(required);
        Objects.requireNonNull(gpu);
        Objects.requireNonNull(cpu);
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("CPU fallback cancelled");
        }
        return cpu.run();
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
