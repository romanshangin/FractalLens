package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.math.Viewport;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrecisionSelectingRenderBackendTest {

    @Test
    void selectsDirectBackendOnlyWhileItsCoordinateCapabilityIsSufficient() {
        DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
        StubBackend deep = new StubBackend();
        PrecisionSelectingRenderBackend selector =
                new PrecisionSelectingRenderBackend(direct, deep);
        RenderJob normal = job(new Viewport(-0.75, 0.0, 2.4));
        RenderJob deepJob = job(new Viewport(
                "-0.7436438870371510000000000000000000000001",
                "0.1318259042053300000000000000000000000002",
                "1e-80"));

        try {
            assertSame(direct, selector.select(normal));
            assertSame(deep, selector.select(deepJob));
        } finally {
            selector.close();
        }
    }

    @Test
    void deepBackendDoesNotClaimJuliaJobs() {
        DirectDoubleRenderBackend direct = new DirectDoubleRenderBackend();
        MandelbrotPerturbationRenderBackend deep = new MandelbrotPerturbationRenderBackend(1);
        PrecisionSelectingRenderBackend selector = new PrecisionSelectingRenderBackend(direct, deep);
        RenderJob deepJulia = new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.JULIA, OrbitTrap.NONE),
                new Viewport("0.1", "0.2", "1e-80"), 320, 240, 300);

        try {
            assertThrows(IllegalArgumentException.class, () -> selector.select(deepJulia));
        } finally {
            selector.close();
        }
    }

    private static RenderJob job(Viewport viewport) {
        return new RenderJob(
                FormulaDefinition.forPreset(FractalPreset.MANDELBROT, OrbitTrap.NONE),
                viewport, 320, 240, 300);
    }

    private static final class StubBackend implements RenderBackend {
        @Override
        public RenderFrame render(
                RenderFrame frame,
                BooleanSupplier cancelled,
                Consumer<RenderRegion> regionCompleted,
                Consumer<TileTimingStats> timingCompleted
        ) {
            return frame;
        }

        @Override
        public void close() {
        }
    }
}
