package com.shangin.fractal.gpu;

/**
 * Owns all native GPU lifetime. Render and JavaFX code consume only this API,
 * never Vulkan/MoltenVK objects or native-library paths.
 */
public interface GpuRuntime extends AutoCloseable {

    GpuCapabilityReport capabilityReport();

    /** Marks native work as unsafe after a device-loss error and forces CPU fallback. */
    void handleDeviceLoss(Throwable cause);

    /** Checks the native queue and disables GPU execution on a native failure. */
    boolean checkHealth();

    /**
     * Serializes GPU work against close/device loss. Only typed GPU failures
     * trigger fallback; cancellation and programming errors propagate. Callers
     * supply the CPU operation matching the job's precision or coloring mode.
     * GPU work must finish its native accesses before returning, and must not
     * publish partial output that would make retrying on CPU unsafe.
     */
    <T> T runOrFallback(GpuNumericCapability required, GpuWork<T> gpu, GpuWork<T> cpu)
            throws InterruptedException;

    default boolean isUsableFor(GpuNumericCapability capability) {
        return capabilityReport().supports(capability);
    }

    @Override
    void close();
}
