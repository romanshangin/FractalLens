package com.shangin.fractal.gpu;

import com.shangin.fractal.coloring.GradientPalette;
import com.shangin.fractal.render.AntialiasSampleCache;
import com.shangin.fractal.render.BaseColorPhaseCache;
import java.util.Objects;

/** Inputs are immutable snapshots; only the caller-owned output array is writable. */
public record PaletteRecolorRequest(BaseColorPhaseCache base, AntialiasSampleCache.Snapshot aa,
                                    GradientPalette palette, PaletteOffset offset, int[] colors) {
    public PaletteRecolorRequest {
        Objects.requireNonNull(base);
        Objects.requireNonNull(aa);
        Objects.requireNonNull(palette);
        Objects.requireNonNull(offset);
        Objects.requireNonNull(colors);
        if (base.size() != colors.length || aa.maxPixelIndex() >= colors.length) {
            throw new IllegalArgumentException("Palette dimensions do not match cached phases");
        }
    }
}
