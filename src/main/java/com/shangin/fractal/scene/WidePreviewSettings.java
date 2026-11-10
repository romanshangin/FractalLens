package com.shangin.fractal.scene;

/**
 * Display-only preview policy; dimensions and spacing are in physical render pixels.
 * The reserve uses the visible frame's iteration limit, so a sample that has not
 * escaped is never shown as interior merely because the reserve stopped earlier.
 */
public record WidePreviewSettings(boolean enabled, double extent, int pixelStep, int sampleLimit,
                                  int workMillis) {
    /** Planning budget for preview frame/display storage; not a JVM/RSS limit. */
    public static final long MEMORY_BUDGET_BYTES = 256L * 1024 * 1024;
    /** Compatibility ceiling for custom policies; the byte budget normally binds first. */
    public static final int MAX_SAMPLES = 16_777_216;
    public static final int MAX_WORKING_PRECISION = 512;
    public static final int WORKERS = 1;
    public static final long REFERENCE_CACHE_BYTES = 1_048_576;
    public static final long PUBLICATION_INTERVAL_NANOS = 50_000_000;
    /** The default policy with the preview switched off. */
    public static final WidePreviewSettings DISABLED =
            new WidePreviewSettings(false, 2.0, 8, MAX_SAMPLES, 2_000);
    /** The default policy with the preview switched on. */
    public static final WidePreviewSettings CANDIDATE =
            new WidePreviewSettings(true, DISABLED.extent, DISABLED.pixelStep, DISABLED.sampleLimit,
                    DISABLED.workMillis);
    /** Enabled by default; historical TG2-4 evidence covers the former sample/time caps only. */
    public static final WidePreviewSettings DEFAULT = CANDIDATE;

    /** The default policy, switched on or off; this is the Settings choice. */
    public static WidePreviewSettings of(boolean enabled) {
        return enabled ? CANDIDATE : DISABLED;
    }

    public long memoryBudgetBytes() { return MEMORY_BUDGET_BYTES; }

    /** Explicit configuration with custom limits; it is always enabled. */
    public WidePreviewSettings(double extent, int pixelStep, int sampleLimit, int workMillis) {
        this(true, extent, pixelStep, sampleLimit, workMillis);
    }

    public WidePreviewSettings {
        if (!Double.isFinite(extent) || extent < 1.0 || extent > 4.0)
            throw new IllegalArgumentException("Preview extent must be between 1 and 4");
        if (pixelStep < 2 || pixelStep > 64)
            throw new IllegalArgumentException("Preview step must be between 2 and 64 physical pixels");
        if (sampleLimit < 16 || sampleLimit > MAX_SAMPLES)
            throw new IllegalArgumentException("Preview sample limit must be between 16 and 16777216");
        if (workMillis < 1 || workMillis > 2_000)
            throw new IllegalArgumentException("Preview work budget must be between 1 and 2000 ms");
    }
}
