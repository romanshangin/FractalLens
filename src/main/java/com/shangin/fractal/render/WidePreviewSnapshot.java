package com.shangin.fractal.render;

/** Immutable-by-ownership display pixels. No samples or exact validity leave the preview service. */
public final class WidePreviewSnapshot {
    private final WidePreviewPlan plan;
    private final int[] colors;
    private final int readySamples;
    private final long elapsedNanos;

    WidePreviewSnapshot(WidePreviewPlan plan, int[] colors, int readySamples) {
        this(plan, colors, readySamples, 0);
    }

    WidePreviewSnapshot(WidePreviewPlan plan, int[] colors, int readySamples, long elapsedNanos) {
        this.elapsedNanos = elapsedNanos;
        this.plan = plan;
        this.colors = colors;
        this.readySamples = readySamples;
    }

    public WidePreviewPlan plan() { return plan; }
    /** Elapsed worker time for this request through publication, including reuse and coloring. */
    public long elapsedNanos() { return elapsedNanos; }

    public int readySamples() { return readySamples; }
    public int[] copyColors() { return colors.clone(); }

    /** Copies into an owner-reused upload buffer without exposing the immutable raster. */
    public void copyColorsInto(int[] target) {
        if (target.length != colors.length) throw new IllegalArgumentException("Preview color dimensions do not match");
        System.arraycopy(colors, 0, target, 0, colors.length);
    }
}
