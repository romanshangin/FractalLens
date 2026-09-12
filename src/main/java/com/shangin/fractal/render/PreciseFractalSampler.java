package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Thread-safe sampling of exact coordinates for deep render and AA passes. */
@FunctionalInterface
public interface PreciseFractalSampler {
    /** Returns null when cancelled. */
    FractalSample sample(BigDecimal real, BigDecimal imaginary, BooleanSupplier cancelled);

    static Optional<PreciseFractalSampler> create(RenderJob job, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return Optional.empty();
        if (job.formula().preset() == FractalPreset.JULIA) {
            return Optional.ofNullable(JuliaReferenceSampler.create(job, job.preciseGrid(), cancelled));
        }
        return MandelbrotPerturbationRenderBackend.createPreciseSampler(job, cancelled)
                .map(sampler -> sampler::sample);
    }
}
