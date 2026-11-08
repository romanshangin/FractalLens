package com.shangin.fractal.scene;

/**
 * Display-only preview policy; dimensions and spacing are in physical render pixels.
 * The reserve uses the visible frame's iteration limit, so a sample that has not
 * escaped is never shown as interior merely because the reserve stopped earlier.
 */
public record WidePreviewSettings(boolean enabled, double extent, int pixelStep, int sampleLimit,
                                  int workMillis) {
    public static final int MAX_WORKING_PRECISION = 512;
    public static final int WORKERS = 1;
    public static final long REFERENCE_CACHE_BYTES = 1_048_576;
    public static final long PUBLICATION_INTERVAL_NANOS = 50_000_000;
    /** The validated limits with the preview switched off. */
    public static final WidePreviewSettings DISABLED =
            new WidePreviewSettings(false, 2.0, 8, 262_144, 2_000);
    /** The validated limits with the preview switched on. */
    public static final WidePreviewSettings CANDIDATE =
            new WidePreviewSettings(true, DISABLED.extent, DISABLED.pixelStep, DISABLED.sampleLimit,
                    DISABLED.workMillis);
    /**
     * Enabled: the candidate passed the declared timing, memory, availability, thermal and
     * exact-control gates in campaign TG2-4 (benchmarks/WIDE_PREVIEW_VALIDATION.md).
     */
    public static final WidePreviewSettings DEFAULT = CANDIDATE;

    /** The validated limits, switched on or off; this is the Settings choice. */
    public static WidePreviewSettings of(boolean enabled) {
        return enabled ? CANDIDATE : DISABLED;
    }

    /** Explicit configuration with custom limits; it is always enabled. */
    public WidePreviewSettings(double extent, int pixelStep, int sampleLimit, int workMillis) {
        this(true, extent, pixelStep, sampleLimit, workMillis);
    }

    public WidePreviewSettings {
        if (!Double.isFinite(extent) || extent < 1.0 || extent > 4.0)
            throw new IllegalArgumentException("Preview extent must be between 1 and 4");
        if (pixelStep < 2 || pixelStep > 64)
            throw new IllegalArgumentException("Preview step must be between 2 and 64 physical pixels");
        if (sampleLimit < 16 || sampleLimit > 262_144)
            throw new IllegalArgumentException("Preview sample limit must be between 16 and 262144");
        if (workMillis < 1 || workMillis > 2_000)
            throw new IllegalArgumentException("Preview work budget must be between 1 and 2000 ms");
    }
}
