package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.formula.FractalSample;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, palette-independent LRU storage for supersampled display pixels.
 * Entries are evicted at pixel granularity; the newest AA candidates survive
 * palette animation when the configured memory budget is reached.
 */
public final class AntialiasSampleCache {

    public static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;
    private static final int SAMPLE_BYTES = Integer.BYTES + Double.BYTES + 1;

    private final long maxBytes;
    private final LinkedHashMap<Integer, Samples> entries =
            new LinkedHashMap<>(256, 0.75f, true);
    private long usedBytes;

    public AntialiasSampleCache() {
        this(DEFAULT_MAX_BYTES);
    }

    public AntialiasSampleCache(long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Cache limit cannot be negative");
        }
        this.maxBytes = maxBytes;
    }

    public synchronized void put(int pixelIndex, FractalSample[] samples) {
        Samples value = Samples.copyOf(samples);
        long bytes = value.estimatedBytes();
        Samples replaced = entries.remove(pixelIndex);
        if (replaced != null) {
            usedBytes -= replaced.estimatedBytes();
        }
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
        return samples == null ? null : samples.color(coloring, maxIterations);
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
    }

    private record Samples(int[] iterations, double[] smoothIterations, boolean[] escaped) {
        static Samples copyOf(FractalSample[] samples) {
            if (samples == null || samples.length == 0) {
                throw new IllegalArgumentException("AA samples cannot be empty");
            }
            int[] iterations = new int[samples.length];
            double[] smooth = new double[samples.length];
            boolean[] escaped = new boolean[samples.length];
            for (int index = 0; index < samples.length; index++) {
                FractalSample sample = java.util.Objects.requireNonNull(samples[index]);
                iterations[index] = sample.iterations();
                smooth[index] = sample.smoothIterations();
                escaped[index] = sample.escaped();
            }
            return new Samples(iterations, smooth, escaped);
        }

        long estimatedBytes() {
            return (long) iterations.length * SAMPLE_BYTES;
        }

        int color(ColoringStrategy coloring, int maxIterations) {
            long alpha = 0;
            double red = 0, green = 0, blue = 0;
            for (int index = 0; index < iterations.length; index++) {
                int color = coloring.color(iterations[index], smoothIterations[index], escaped[index], maxIterations);
                alpha += color >>> 24;
                red += toLinear(color >>> 16 & 0xff);
                green += toLinear(color >>> 8 & 0xff);
                blue += toLinear(color & 0xff);
            }
            int count = iterations.length;
            return ((int) ((alpha + count / 2) / count) << 24)
                    | (fromLinear(red / count) << 16)
                    | (fromLinear(green / count) << 8)
                    | fromLinear(blue / count);
        }

        private static double toLinear(int channel) {
            double srgb = channel / 255.0;
            return srgb <= 0.04045
                    ? srgb / 12.92
                    : Math.pow((srgb + 0.055) / 1.055, 2.4);
        }

        private static int fromLinear(double linear) {
            double srgb = linear <= 0.0031308
                    ? linear * 12.92
                    : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
            return Math.clamp((int) Math.round(srgb * 255.0), 0, 255);
        }
    }
}
