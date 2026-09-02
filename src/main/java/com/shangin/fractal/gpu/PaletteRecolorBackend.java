package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.render.AntialiasSampleCache;
import com.shangin.fractal.render.BaseColorPhaseCache;
import java.util.Objects;

/** Opt-in palette backend; the portable baseline retains its parallel loops and one lookup. */
public final class PaletteRecolorBackend {
    private final GpuRuntime runtime;
    private final boolean gpuEnabled;
    private final ThreadLocal<SmoothColorLookup> lookup = ThreadLocal.withInitial(SmoothColorLookup::new);

    public PaletteRecolorBackend(GpuRuntime runtime) {
        this(runtime, Boolean.getBoolean("fractal.gpu.palette.enabled"));
    }

    public PaletteRecolorBackend(GpuRuntime runtime, boolean gpuEnabled) {
        this.runtime = Objects.requireNonNull(runtime);
        this.gpuEnabled = gpuEnabled;
    }

    public PaletteRecolorTiming recolor(BaseColorPhaseCache base, AntialiasSampleCache.Snapshot aa,
                                        SmoothPaletteColoring coloring, int[] colors)
            throws InterruptedException {
        Objects.requireNonNull(base);
        Objects.requireNonNull(aa);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(colors);
        if (!base.matches(coloring) || colors.length != base.size() || aa.maxPixelIndex() >= colors.length) {
            throw new IllegalArgumentException("Coloring/cache dimensions or scale differ");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Recolor cancelled");
        long start = System.nanoTime();
        GpuWork<PaletteRecolorTiming> cpu = () -> {
            long prepare = System.nanoTime();
            SmoothColorLookup table = lookup.get();
            table.update(coloring);
            long ready = System.nanoTime();
            base.recolorInto(colors, table);
            aa.recolorInto(colors, table);
            long done = System.nanoTime();
            return PaletteRecolorTiming.cpu(ready - prepare, done - ready, done - start);
        };
        PaletteRecolorTiming result;
        if (gpuEnabled && coloring.palette() instanceof GradientPalette palette
                && Double.isFinite(coloring.offset()) && Math.abs(coloring.offset()) < 1_000_000) {
            result = runtime.recolorPalette(() -> new PaletteRecolorRequest(
                    base, aa, palette, PaletteOffset.from(coloring), colors), cpu);
        } else {
            result = cpu.run();
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Recolor cancelled");
        return result.withPreparation(0, System.nanoTime() - start);
    }
}
