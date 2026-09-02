package com.shangin.fractal.gpu;

/**
 * macOS entry point for MoltenVK. The initial boundary validates that LWJGL's
 * Vulkan binding is present; native loading and physical-device feature
 * enumeration remain pending in 8.1, so no numeric mode is enabled.
 */
final class MacosMoltenVkRuntime extends UnavailableGpuRuntime {

    private MacosMoltenVkRuntime(String detail) {
        super(GpuPlatform.MACOS, detail);
    }

    static GpuRuntime discover() {
        try {
            Class.forName("org.lwjgl.vulkan.VK", false,
                    MacosMoltenVkRuntime.class.getClassLoader());
            return new MacosMoltenVkRuntime(
                    "LWJGL Vulkan binding found. MoltenVK device enumeration is not yet "
                            + "implemented; rendering remains on CPU.");
        } catch (ClassNotFoundException exception) {
            return new MacosMoltenVkRuntime(
                    "LWJGL Vulkan binding is not installed; using the CPU backend.");
        } catch (LinkageError error) {
            return new MacosMoltenVkRuntime(
                    "LWJGL Vulkan binding could not load: " + error.getClass().getSimpleName());
        }
    }
}
