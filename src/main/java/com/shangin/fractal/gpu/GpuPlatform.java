package com.shangin.fractal.gpu;

import java.util.Locale;

/** Operating-system boundary for a native GPU runtime. */
public enum GpuPlatform {
    MACOS,
    WINDOWS,
    UNSUPPORTED;

    public static GpuPlatform current() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac") || osName.equals("darwin")) {
            return MACOS;
        }
        if (osName.contains("win")) {
            return WINDOWS;
        }
        return UNSUPPORTED;
    }
}
