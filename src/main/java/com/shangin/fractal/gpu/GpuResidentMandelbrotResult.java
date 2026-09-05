package com.shangin.fractal.gpu;

/** Completed experimental frame; only final ARGB and rejected indices crossed back to the host. */
public record GpuResidentMandelbrotResult(
        int[] colors,
        int certifiedPixels,
        int recoveredPixels,
        GpuResidentRejectionStats rejections,
        GpuResidentMandelbrotTiming timing
) {}
