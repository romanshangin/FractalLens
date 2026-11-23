package com.shangin.fractal.gpu;

/**
 * Completed approximate preview frame. Times are host wall clock; dispatch
 * covers command encoding, submission and the fence wait, not a device query.
 * {@code brightPointsFiltered} is false when the request asked for the
 * bright-point pass but its output buffer did not fit the memory budget; the
 * caller must then apply {@link JuliaBrightPointFilter} itself.
 */
public record GpuJuliaPreviewResult(
        int[] colors,
        long setupNanos,
        long uploadNanos,
        long dispatchNanos,
        long readbackNanos,
        long totalNanos,
        long nativeBytes,
        boolean brightPointsFiltered
) {}
