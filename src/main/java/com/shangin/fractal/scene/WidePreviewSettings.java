package com.shangin.fractal.scene;

/** Display-only preview policy; dimensions and spacing are in physical render pixels. */
public record WidePreviewSettings(boolean enabled, double extent, int pixelStep, int sampleLimit,
                                  int workMillis, int iterationLimit) {
    public static final int MAX_WORKING_PRECISION = 512;
    public static final int WORKERS = 1;
    public static final long REFERENCE_CACHE_BYTES = 1_048_576;
    public static final long PUBLICATION_INTERVAL_NANOS = 50_000_000;
    /** Disabled until the candidate passes the declared production memory/latency gate. */
    public static final WidePreviewSettings DEFAULT =
            new WidePreviewSettings(false, 2.0, 8, 262_144, 2_000, 4_096);
    public static final WidePreviewSettings CANDIDATE =
            new WidePreviewSettings(true, DEFAULT.extent, DEFAULT.pixelStep, DEFAULT.sampleLimit,
                    DEFAULT.workMillis, DEFAULT.iterationLimit);

    /** Explicit configuration enables evaluation without a hidden JVM switch or Settings UI. */
    public WidePreviewSettings(double extent, int pixelStep, int sampleLimit, int workMillis, int iterationLimit) {
        this(true, extent, pixelStep, sampleLimit, workMillis, iterationLimit);
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
        if (iterationLimit < 1 || iterationLimit > 4_096)
            throw new IllegalArgumentException("Preview iteration limit must be between 1 and 4096");
    }
}
