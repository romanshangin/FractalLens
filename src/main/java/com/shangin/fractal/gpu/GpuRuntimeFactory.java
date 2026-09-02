package com.shangin.fractal.gpu;

/** Creates the runtime appropriate for the current platform. */
public final class GpuRuntimeFactory {

    private GpuRuntimeFactory() {
    }

    public static GpuRuntime createDefault() {
        if (!Boolean.parseBoolean(System.getProperty("fractal.gpu.enabled", "true"))) {
            return new UnavailableGpuRuntime(GpuPlatform.current(), "GPU runtime disabled by fractal.gpu.enabled.");
        }
        return switch (GpuPlatform.current()) {
            case MACOS -> MacosMoltenVkRuntime.discover();
            case WINDOWS -> new UnavailableGpuRuntime(
                    GpuPlatform.WINDOWS,
                    "Native Vulkan runtime is scheduled after macOS validation.");
            case UNSUPPORTED -> new UnavailableGpuRuntime(
                    GpuPlatform.UNSUPPORTED,
                    "GPU rendering is supported only on macOS and Windows.");
        };
    }
}
