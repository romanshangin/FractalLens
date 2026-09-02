package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.formula.FractalSample;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Bounded, palette-independent LRU storage for supersampled display pixels.
 * Entries are evicted at pixel granularity; the newest AA candidates survive
 * palette animation when the configured memory budget is reached.
 */
public final class AntialiasSampleCache {

    public static final long DEFAULT_MAX_BYTES = Long.getLong(
            "fractal.aaCache.maxBytes", 128L * 1024L * 1024L);
    private static final int SAMPLE_BYTES = Short.BYTES;
    private static final int[] LINEAR_TO_SRGB = createSrgbLookup();

    private final long maxBytes;
    private final LinkedHashMap<Integer, Samples> entries =
            new LinkedHashMap<>(256, 0.75f, true);
    private long usedBytes;
    private Snapshot recolorSnapshot;
    private double cachedColorScale = Double.NaN;

    public AntialiasSampleCache() {
        this(DEFAULT_MAX_BYTES);
    }

    public AntialiasSampleCache(long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Cache limit cannot be negative");
        }
        this.maxBytes = maxBytes;
    }

    public synchronized void put(
            int pixelIndex,
            FractalSample[] samples,
            SmoothPaletteColoring coloring
    ) {
        if (Double.compare(cachedColorScale, coloring.colorScale()) != 0) {
            clear();
            cachedColorScale = coloring.colorScale();
        }
        Samples value = Samples.copyOf(samples, coloring);
        long bytes = value.estimatedBytes();
        Samples replaced = entries.remove(pixelIndex);
        if (replaced != null) {
            usedBytes -= replaced.estimatedBytes();
        }
        recolorSnapshot = null;
        if (bytes > maxBytes) {
            return;
        }
        entries.put(pixelIndex, value);
        usedBytes += bytes;
        while (usedBytes > maxBytes) {
            Map.Entry<Integer, Samples> eldest = entries.entrySet().iterator().next();
            usedBytes -= eldest.getValue().estimatedBytes();
            entries.remove(eldest.getKey());
        }
    }

    public synchronized Integer color(int pixelIndex, ColoringStrategy coloring, int maxIterations) {
        Samples samples = entries.get(pixelIndex);
        return samples == null || !(coloring instanceof SmoothPaletteColoring smooth)
                || Double.compare(cachedColorScale, smooth.colorScale()) != 0
                ? null
                : samples.color(new SmoothColorLookup(smooth));
    }

    public synchronized Integer color(int pixelIndex, SmoothColorLookup lookup) {
        Samples samples = entries.get(pixelIndex);
        return samples == null ? null : samples.color(lookup);
    }

    /**
     * Applies an immutable cache snapshot in parallel. No LRU lock is taken in
     * the per-pixel animation loop, and each worker writes a distinct pixel.
     */
    public void recolorInto(int[] colors, SmoothColorLookup lookup) {
        snapshot().recolorInto(colors, lookup);
    }

    public synchronized Snapshot snapshotFor(SmoothPaletteColoring coloring) {
        return Double.compare(cachedColorScale, coloring.colorScale()) == 0
                ? snapshot() : Snapshot.EMPTY;
    }

    public synchronized boolean contains(int pixelIndex) {
        return entries.get(pixelIndex) != null;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long usedBytes() {
        return usedBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public synchronized void clear() {
        entries.clear();
        usedBytes = 0;
        recolorSnapshot = null;
        cachedColorScale = Double.NaN;
    }

    /** Retains samples whose pixels remain visible after an integer pan. */
    public synchronized void shift(int width, int height, PixelShift shift) {
        LinkedHashMap<Integer, Samples> shifted = new LinkedHashMap<>(256, 0.75f, true);
        for (Map.Entry<Integer, Samples> entry : entries.entrySet()) {
            int sourceX = entry.getKey() % width;
            int sourceY = entry.getKey() / width;
            int targetX = sourceX + shift.dx();
            int targetY = sourceY + shift.dy();
            if (targetX >= 0 && targetX < width && targetY >= 0 && targetY < height) {
                shifted.put(targetY * width + targetX, entry.getValue());
            }
        }
        entries.clear();
        entries.putAll(shifted);
        usedBytes = entries.values().stream().mapToLong(Samples::estimatedBytes).sum();
        recolorSnapshot = null;
    }

    private synchronized Snapshot snapshot() {
        if (recolorSnapshot != null) {
            return recolorSnapshot;
        }
        int[] pixelIndices = new int[entries.size()];
        Samples[] samples = new Samples[entries.size()];
        int index = 0;
        for (Map.Entry<Integer, Samples> entry : entries.entrySet()) {
            pixelIndices[index] = entry.getKey();
            samples[index] = entry.getValue();
            index++;
        }
        recolorSnapshot = new Snapshot(pixelIndices, samples);
        return recolorSnapshot;
    }

    private record Samples(short[] phases, int escapedMask) {
        static Samples copyOf(FractalSample[] samples, SmoothPaletteColoring coloring) {
            if (samples == null || samples.length == 0) {
                throw new IllegalArgumentException("AA samples cannot be empty");
            }
            if (samples.length > Integer.SIZE) {
                throw new IllegalArgumentException("At most 32 AA samples are supported");
            }
            short[] phases = new short[samples.length];
            int escapedMask = 0;
            for (int index = 0; index < samples.length; index++) {
                FractalSample sample = java.util.Objects.requireNonNull(samples[index]);
                if (sample.escaped()) {
                    escapedMask |= 1 << index;
                    double phase = coloring.basePhase(sample.smoothIterations());
                    phases[index] = SmoothColorLookup.encode(phase);
                }
            }
            return new Samples(phases, escapedMask);
        }

        long estimatedBytes() {
            return (long) phases.length * SAMPLE_BYTES + Integer.BYTES;
        }

        int color(SmoothColorLookup lookup) {
            long alpha = 0;
            double red = 0, green = 0, blue = 0;
            for (int index = 0; index < phases.length; index++) {
                boolean escaped = (escapedMask & 1 << index) != 0;
                int color = escaped ? lookup.color(phases[index]) : 0xFF000000;
                alpha += color >>> 24;
                if (escaped) {
                    red += lookup.linearRed(phases[index]);
                    green += lookup.linearGreen(phases[index]);
                    blue += lookup.linearBlue(phases[index]);
                }
            }
            int count = phases.length;
            return ((int) ((alpha + count / 2) / count) << 24)
                    | (fromLinear(red / count) << 16)
                    | (fromLinear(green / count) << 8)
                    | fromLinear(blue / count);
        }

        private static int fromLinear(double linear) {
            int index = Math.clamp(
                    (int) Math.round(linear * (LINEAR_TO_SRGB.length - 1)),
                    0,
                    LINEAR_TO_SRGB.length - 1);
            return LINEAR_TO_SRGB[index];
        }
    }

    /** Immutable generation of the cache, shared by CPU and GPU recoloring. */
    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(new int[0], new Samples[0]);
        private final int[] pixelIndices;
        private final Samples[] samples;
        private final int words;
        private final int maxPixel;

        private Snapshot(int[] pixelIndices, Samples[] samples) {
            this.pixelIndices = pixelIndices;
            this.samples = samples;
            int count = Math.multiplyExact(pixelIndices.length, 4);
            int max = -1;
            for (int i = 0; i < samples.length; i++) {
                count = Math.addExact(count, (samples[i].phases.length + 1) / 2);
                max = Math.max(max, pixelIndices[i]);
            }
            words = count;
            maxPixel = max;
        }

        public int size() { return pixelIndices.length; }
        public int gpuWordCount() { return words; }
        public int maxPixelIndex() { return maxPixel; }

        public void recolorInto(int[] colors, SmoothColorLookup lookup) {
            IntStream.range(0, samples.length).parallel().forEach(i ->
                    colors[pixelIndices[i]] = samples[i].color(lookup));
        }

        /** Four metadata words per pixel, followed by packed pairs of 16-bit phases. */
        public void writeGpuWords(java.nio.IntBuffer target) {
            int offset = samples.length * 4;
            for (int i = 0; i < samples.length; i++) {
                target.put(pixelIndices[i]).put(offset).put(samples[i].phases.length)
                        .put(samples[i].escapedMask);
                offset += (samples[i].phases.length + 1) / 2;
            }
            for (Samples sample : samples) {
                for (int i = 0; i < sample.phases.length; i += 2) {
                    target.put(Short.toUnsignedInt(sample.phases[i])
                            | (i + 1 < sample.phases.length
                            ? Short.toUnsignedInt(sample.phases[i + 1]) << 16 : 0));
                }
            }
        }
    }

    private static int[] createSrgbLookup() {
        int[] lookup = new int[65_536];
        for (int index = 0; index < lookup.length; index++) {
            double linear = (double) index / (lookup.length - 1);
            double srgb = linear <= 0.0031308
                    ? linear * 12.92
                    : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
            lookup[index] = Math.clamp((int) Math.round(srgb * 255.0), 0, 255);
        }
        return lookup;
    }
}
