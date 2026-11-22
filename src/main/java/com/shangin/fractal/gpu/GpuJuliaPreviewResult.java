package com.shangin.fractal.gpu;

/**
 * Completed approximate preview frame. Times are host wall clock; dispatch
 * covers command encoding, submission and the fence wait, not a device query.
 */
public record GpuJuliaPreviewResult(
        int[] colors,
        long setupNanos,
        long uploadNanos,
        long dispatchNanos,
        long readbackNanos,
        long totalNanos,
        long nativeBytes
) {}
