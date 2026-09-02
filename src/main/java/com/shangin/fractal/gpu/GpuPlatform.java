package com.shangin.fractal.gpu;

/** Operating-system boundary for a native GPU runtime. */
public enum GpuPlatform {
    MACOS,
    WINDOWS,
    UNSUPPORTED;

    public static GpuPlatform current() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) {
            return MACOS;
        }
        if (osName.contains("win")) {
            return WINDOWS;
        }
        return UNSUPPORTED;
    }
}
