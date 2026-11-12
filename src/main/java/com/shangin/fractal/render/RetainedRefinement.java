package com.shangin.fractal.render;

import com.shangin.fractal.scene.ColoringSettings;

import java.util.Objects;

/**
 * Antialiasing finished for one completed frame, kept beside it in {@link RenderFrameCache}.
 * The displayed AA colors are valid only for the coloring they were produced with; the
 * palette-independent samples, when present, also serve any smooth palette of the same
 * color scale.
 *
 * @param pixels   refined display colors and their validity, or {@code null} when the
 *                 displayed AA colors did not all come from one coloring
 * @param coloring the coloring that produced {@code pixels}, {@code null} without pixels
 * @param samples  palette-independent AA samples, or {@code null} for colorings without them
 */
public record RetainedRefinement(
        RefinedPixelSnapshot pixels,
        ColoringSettings coloring,
        AntialiasSampleCache.Retained samples
) {

    /** Colors plus the validity and ready bit sets copied into the snapshot. */
    private static final long BYTES_PER_PIXEL = Integer.BYTES + 1L;
    private static final long FIXED_BYTES = 256;

    public RetainedRefinement {
        if ((pixels == null) != (coloring == null)) {
            throw new IllegalArgumentException("Retained AA colors need the coloring that produced them");
        }
        if (pixels == null && samples == null) {
            throw new IllegalArgumentException("Retained AA needs colors or samples");
        }
    }

    /** True when the retained colors can be shown as finished AA for {@code displayed}. */
    public boolean showsColorsFor(ColoringSettings displayed) {
        return pixels != null && coloring.equals(Objects.requireNonNull(displayed));
    }

    /** The display colors alone, or {@code null} when there are none to keep. */
    public RetainedRefinement withoutSamples() {
        if (pixels == null) return null;
        return samples == null ? this : new RetainedRefinement(pixels, coloring, null);
    }

    /** Conservative retained size, charged against the frame cache budget. */
    public long estimatedBytes() {
        long pixelBytes = pixels == null ? 0L
                : Math.multiplyExact((long) pixels.width() * pixels.height(), BYTES_PER_PIXEL);
        return Math.addExact(FIXED_BYTES + pixelBytes, samples == null ? 0L : samples.bytes());
    }
}
