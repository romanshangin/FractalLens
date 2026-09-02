package com.shangin.fractal.gpu;

/** Creates the runtime appropriate for the current platform. */
public final class GpuRuntimeFactory {

    private GpuRuntimeFactory() {
    }

    public static GpuRuntime createDefault() {
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
