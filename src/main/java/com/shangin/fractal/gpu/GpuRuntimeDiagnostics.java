package com.shangin.fractal.gpu;

/** Headless diagnostic entry point, using the same module path as the JavaFX app. */
public final class GpuRuntimeDiagnostics {
    private GpuRuntimeDiagnostics() {
    }

    public static void main(String[] args) {
        GpuRuntime runtime = GpuRuntimeFactory.createDefault();
        try (runtime) {
            System.out.println(runtime.capabilityReport());
            System.out.println("Native queue healthy: " + runtime.checkHealth());
        }
        System.out.println("Shutdown: " + runtime.capabilityReport().state());
        System.out.println(runtime.capabilityReport().detail());
    }
}
