package com.shangin.fractal.gpu;

/** Optional per-dispatch diagnostics; kernel is -1 if queue timestamps are unsupported. */
record MandelbrotTiming(long uploadNanos, long dispatchNanos, long readbackNanos,
                        long kernelNanos, long nativeBytes) {}
