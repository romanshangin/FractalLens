package com.shangin.fractal.gpu;

import java.util.Locale;

/** Loads the native adapter only on supported macOS JVM architectures. */
final class MacosMoltenVkRuntime {
    private MacosMoltenVkRuntime() {
    }

    static GpuRuntime discover() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("aarch64") && !arch.equals("arm64")
                && !arch.equals("x86_64") && !arch.equals("amd64")) {
            return new UnavailableGpuRuntime(GpuPlatform.MACOS, "Unsupported macOS JVM architecture: " + arch);
        }
        return ManagedGpuRuntime.open(GpuPlatform.MACOS, MacosMoltenVkSession::open);
    }
}
