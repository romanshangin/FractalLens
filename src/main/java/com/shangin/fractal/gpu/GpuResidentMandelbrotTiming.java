package com.shangin.fractal.gpu;

/** Host wall times for the opt-in resident feasibility spike. */
public record GpuResidentMandelbrotTiming(
        long allocationNanos,
        long axisPackNanos,
        long uploadNanos,
        long calculationNanos,
        long rejectionReadbackNanos,
        long recoveryNanos,
        long correctionUploadNanos,
        long coloringNanos,
        long colorReadbackNanos,
        long totalNanos,
        long nativeBytes,
        long uploadedBytes,
        long readbackBytes
) {}
