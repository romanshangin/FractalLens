package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.gpu.JuliaBrightPointFilter;
import com.shangin.fractal.math.Viewport;

import java.math.BigDecimal;
import java.nio.IntBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Renders transient whole-frame Julia previews on the CPU for Explore Julia Parameters
 * when the GPU kernel cannot render the view. A frame at scale {@code s} calculates
 * binary64 samples of a grid with the same center and {@code s} times the pixel spacing,
 * colors them with the scene's palette, optionally applies the bright-point filter at that
 * resolution, and enlarges each sample to an {@code s} by {@code s} block of the target.
 * At scale 1 the colors are those of the direct-double CPU render before antialiasing.
 * Calls block the caller and must not run on the JavaFX Application Thread; one caller
 * at a time.
 */
public final class JuliaCpuPreview implements AutoCloseable {

    /** Largest supported enlargement: one sample per 8 by 8 target pixels. */
    public static final int MAX_SCALE = 8;

    /** Samples calculated for one frame and their cost, colors included. */
    public record Frame(int scale, int width, int height, long sampleNanos) {}

    private final RenderBackend backend;
    private int[] coarse = new int[0];
    private int[] filtered = new int[0];

    public JuliaCpuPreview() {
        this(new DirectDoubleRenderBackend());
    }

    /** Uses {@code backend} for the samples and closes it with this preview. */
    public JuliaCpuPreview(RenderBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /**
     * Whether this job is a Julia view the direct-double backend can render at full size.
     * A coarser grid of the same view has a larger pixel spacing, so it is then safe too.
     */
    public boolean supports(RenderJob job) {
        Objects.requireNonNull(job);
        return job.formula().preset() == FractalPreset.JULIA && backend.supports(job);
    }

    /**
     * The coarse job for {@code scale}: same formula, center and iteration limit, pixel
     * spacing multiplied by the scale, and enough samples to cover the target, at least two
     * per axis.
     */
    public static RenderJob coarseJob(RenderJob job, int scale) {
        Objects.requireNonNull(job);
        if (scale < 1 || scale > MAX_SCALE) throw new IllegalArgumentException("Unsupported preview scale " + scale);
        if (scale == 1) return job;
        int width = coarseLength(job.width(), scale);
        int height = coarseLength(job.height(), scale);
        Viewport viewport = job.viewport();
        // The viewport's scale spans the first to the last pixel row, height - 1 steps:
        // keep the target's pixel spacing times the scale.
        BigDecimal visibleHeight = viewport.scaleExact().multiply(BigDecimal.valueOf((height - 1L) * scale))
                .divide(BigDecimal.valueOf(job.height() - 1L), viewport.mathContext());
        return new RenderJob(job.formula(), viewport.withCalculatedValues(viewport.center(), visibleHeight),
                width, height, job.maxIterations());
    }

    /** Samples along one axis for a scale: enough to cover the target, at least two. */
    public static int coarseLength(int length, int scale) {
        return Math.max(2, (length + scale - 1) / scale);
    }

    /**
     * Renders {@code job} at {@code scale} into {@code destination}, row-major ARGB of the
     * job's full size. Returns {@code null}, leaving the destination partly written, when
     * {@code cancelled} reported cancellation first.
     *
     * @throws IllegalArgumentException if the job is not {@linkplain #supports supported}
     */
    public Frame render(RenderJob job, int scale, ColoringStrategy coloring, boolean filterBrightPoints,
                        int[] destination, BooleanSupplier cancelled) throws InterruptedException {
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(cancelled);
        if (!supports(job)) throw new IllegalArgumentException("The CPU preview cannot render this job");
        if (destination.length != job.width() * job.height()) {
            throw new IllegalArgumentException("Destination does not match the job size");
        }
        long start = System.nanoTime();
        RenderJob sampled = coarseJob(job, scale);
        int samples = sampled.width() * sampled.height();
        boolean enlarged = sampled != job;
        int[] colors = enlarged ? buffer(samples, false) : destination;
        RenderFrame frame = RenderFrame.create(sampled);
        SamplePlane plane = frame.samplePlane();
        IntBuffer target = IntBuffer.wrap(colors);
        var colorizer = new FractalColorizer();
        // Tiles are colored on the workers that complete them.
        frame = backend.render(frame, cancelled,
                region -> colorizer.colorRegion(plane, target, coloring, region), null);
        if (cancelled.getAsBoolean() || !frame.isComplete()) return null;
        if (filterBrightPoints) {
            int[] result = buffer(samples, true);
            JuliaBrightPointFilter.apply(colors, result, sampled.width(), sampled.height());
            if (enlarged) colors = result;
            else System.arraycopy(result, 0, destination, 0, samples);
        }
        long sampleNanos = System.nanoTime() - start;
        if (enlarged) enlarge(colors, sampled.width(), sampled.height(), scale, destination, job.width(), job.height());
        return new Frame(scale, sampled.width(), sampled.height(), sampleNanos);
    }

    /**
     * Gives each target pixel the color of its nearest coarse sample. Both grids share their
     * center, and coarse samples are {@code scale} target pixels apart.
     */
    static void enlarge(int[] source, int sourceWidth, int sourceHeight, int scale,
                        int[] destination, int width, int height) {
        int[] columns = new int[width];
        for (int x = 0; x < width; x++) columns[x] = nearestSample(x, width, sourceWidth, scale);
        int previousRow = -1;
        for (int y = 0; y < height; y++) {
            int sourceY = nearestSample(y, height, sourceHeight, scale);
            int row = y * width;
            if (sourceY == previousRow) {
                System.arraycopy(destination, row - width, destination, row, width);
                continue;
            }
            previousRow = sourceY;
            int sourceRow = sourceY * sourceWidth;
            for (int x = 0; x < width; x++) destination[row + x] = source[sourceRow + columns[x]];
        }
    }

    /**
     * The coarse sample nearest to target pixel {@code index}. Pixel {@code index} lies
     * {@code index - (length - 1) / 2} target steps from the center and sample {@code i}
     * {@code (i - (samples - 1) / 2) * scale}; rounding half up, in integers.
     */
    static int nearestSample(int index, int length, int samples, int scale) {
        return Math.min(samples - 1, (2 * index + samples * scale - length + 1) / (2 * scale));
    }

    /** Exactly {@code size} long: the bright-point filter copies whole arrays. */
    private int[] buffer(int size, boolean filter) {
        int[] current = filter ? filtered : coarse;
        if (current.length != size) {
            current = new int[size];
            if (filter) filtered = current;
            else coarse = current;
        }
        return current;
    }

    @Override
    public void close() {
        backend.close();
    }
}
