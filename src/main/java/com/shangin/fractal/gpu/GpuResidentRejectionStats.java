package com.shangin.fractal.gpu;

/** Diagnostic breakdown of samples rejected by the experimental resident certificate. */
public record GpuResidentRejectionStats(
        int uncertainInterval,
        int boundedMismatch,
        int iterationMismatch,
        int invalidInterval,
        int smoothError,
        int phaseMismatch,
        int other
) {
    public int total() {
        return Math.addExact(Math.addExact(Math.addExact(uncertainInterval, boundedMismatch),
                        Math.addExact(iterationMismatch, invalidInterval)),
                Math.addExact(Math.addExact(smoothError, phaseMismatch), other));
    }
}
