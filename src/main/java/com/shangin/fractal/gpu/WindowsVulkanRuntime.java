package com.shangin.fractal.gpu;

import java.util.Locale;
import java.util.function.Supplier;

/** Uses the system Vulkan loader and the packaged LWJGL Windows x64 natives. */
final class WindowsVulkanRuntime {
    private WindowsVulkanRuntime() {
    }

    static GpuRuntime discover() {
        return discover(System.getProperty("os.arch", ""), VulkanComputeSession::open);
    }

    static GpuRuntime discover(String architecture, Supplier<GpuSession> opener) {
        String arch = architecture.toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            return new UnavailableGpuRuntime(GpuPlatform.WINDOWS,
                    "Unsupported Windows JVM architecture: " + arch);
        }
        return ManagedGpuRuntime.open(GpuPlatform.WINDOWS, opener);
    }
}
