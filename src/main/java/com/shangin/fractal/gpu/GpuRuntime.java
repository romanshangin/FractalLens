package com.shangin.fractal.gpu;

/**
 * Owns all native GPU lifetime. Render and JavaFX code consume only this API,
 * never Vulkan/MoltenVK objects or native-library paths.
 */
public interface GpuRuntime extends AutoCloseable {

    GpuCapabilityReport capabilityReport();

    /** Marks native work as unsafe after a device-loss error and forces CPU fallback. */
    void handleDeviceLoss(Throwable cause);

    default boolean isUsableFor(GpuNumericCapability capability) {
        return capabilityReport().supports(capability);
    }

    @Override
    void close();
}
