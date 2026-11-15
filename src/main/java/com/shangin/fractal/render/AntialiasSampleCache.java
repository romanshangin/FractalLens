package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.coloring.Palette;
import com.shangin.fractal.coloring.PaletteEdgeThresholds;
import com.shangin.fractal.coloring.SmoothPaletteColoring;
import com.shangin.fractal.coloring.SmoothColorLookup;
import com.shangin.fractal.formula.FractalSample;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Bounded, palette-independent storage for supersampled display pixels.
 *
 * <p>Entries live in append-only primitive arrays: one slot per pixel in the
 * per-entry arrays and the encoded phases back to back. An immutable
 * {@link Snapshot} shares these arrays and reads only entries appended before it,
 * so later appends never change it; growth, shifts and compaction write new arrays.
 *
 * <p>The cache never evicts. A pixel that no longer fits the configured budget is
 * rejected and the cache reports {@link #dropped()} until it is cleared, so callers
 * can treat the rejected candidates as missing instead of showing their base colors.
 *
 * <p>Each entry keeps the base-phase edge strength that made its pixel a candidate.
 * Recoloring shows an entry only where the palette's own
 * {@link PaletteEdgeThresholds#phaseThreshold threshold} selects that pixel, so the
 * displayed image depends on the frame and the current palette alone, not on the
 * palettes that sampled it.
 */
public final class AntialiasSampleCache {

    public static final long DEFAULT_MAX_BYTES = Long.getLong(
            "fractal.aaCache.maxBytes", 512L * 1024L * 1024L);
    private static final int MAX_SAMPLES = Integer.SIZE;
    // Conservative 64-bit layout: cache and owner objects, 32-byte array headers and
    // 16-byte alignment. Two snapshot index arrays (the current snapshot and one palette
    // selection) are prepaid per entry slot; snapshots retained by callers belong to
    // their own lifecycles.
    private static final long FIXED_BYTES = 384;
    private static final long ARRAY_HEADER_BYTES = 32;
    private static final int MIN_ENTRY_CAPACITY = 16;
    private static final int MIN_PHASE_CAPACITY = 256;
    private static final int MIN_TABLE_CAPACITY = 32;
    private static final int[] LINEAR_TO_SRGB = createSrgbLookup();

    private final long maxBytes;
    private Storage storage = Storage.EMPTY;
    /** Open addressing from pixel to entry index plus one; zero is empty. */
    private int[] table = new int[0];
    private int live;
    private boolean dropped;
    /** Rejected appends since construction; clearing does not reset it. */
    private long rejections;
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
        short[] phases = new short[validatedLength(samples)];
        int escapedMask = encode(samples, coloring, phases, 0);
        prepareScale(coloring.colorScale());
        append(pixelIndex, phases, 0, phases.length, escapedMask, Float.POSITIVE_INFINITY, false);
    }

    private void prepareScale(double colorScale) {
        if (Double.compare(cachedColorScale, colorScale) != 0) {
            clear();
            cachedColorScale = colorScale;
        }
    }

    /**
     * Caller owns this cache's monitor. Appends one entry, replacing the pixel's
     * previous entry, or rejects it when it does not fit the budget.
     */
    private boolean append(int pixel, short[] phases, int from, int length, int escapedMask,
                           float edgeStrength, boolean deepZoom) {
        boolean replaces = table.length > 0 && table[slotOf(pixel)] != 0;
        if (replaces && storage.count - live >= Math.max(live, MIN_ENTRY_CAPACITY)) {
            rebuild(null, 0, 0, 0);
        }
        int liveAfter = replaces ? live : live + 1;
        if (!reserve(storage.count + 1, storage.phaseCount + length, liveAfter)) {
            // Space held by replaced entries is reclaimed before anything is rejected.
            if (storage.count == live
                    || !compactThenReserve(liveAfter, length)) {
                dropped = true;
                rejections++;
                return false;
            }
        }
        // Growth, rehashing and compaction may move the pixel's slot.
        int slot = slotOf(pixel);
        Storage target = storage;
        int index = target.count;
        target.pixels[index] = pixel;
        target.masks[index] = escapedMask;
        target.strengths[index] = edgeStrength;
        target.deep[index] = (byte) (deepZoom ? 1 : 0);
        // offsets[index] already holds phaseCount; entries a snapshot reads are never rewritten.
        System.arraycopy(phases, from, target.phases, target.phaseCount, length);
        target.phaseCount += length;
        target.offsets[index + 1] = target.phaseCount;
        target.count = index + 1;
        if (table[slot] == 0) live++;
        table[slot] = index + 1;
        recolorSnapshot = null;
        return true;
    }

    private boolean compactThenReserve(int liveEntries, int length) {
        rebuild(null, 0, 0, 0);
        return reserve(storage.count + 1, storage.phaseCount + length, liveEntries);
    }

    /** Grows storage and table to the requested sizes within the budget, or returns false. */
    private boolean reserve(int entries, int phaseCount, int liveEntries) {
        Storage current = storage;
        int entryCapacity = current.capacity();
        int phaseCapacity = current.phases.length;
        int tableCapacity = table.length;
        boolean growsEntries = entries > entryCapacity;
        boolean growsPhases = phaseCount > phaseCapacity;
        boolean growsTable = liveEntries > tableCapacity / 4L * 3;
        if (!growsEntries && !growsPhases && !growsTable) return true;
        int nextEntries = growsEntries ? grown(entryCapacity, entries, MIN_ENTRY_CAPACITY) : entryCapacity;
        int nextPhases = growsPhases ? grown(phaseCapacity, phaseCount, MIN_PHASE_CAPACITY) : phaseCapacity;
        int nextTable = growsTable ? tableCapacityFor(liveEntries, Math.max(MIN_TABLE_CAPACITY, tableCapacity * 2))
                : tableCapacity;
        if (bytesFor(nextEntries, nextPhases, nextTable) > maxBytes) {
            // Near the budget, grow by the largest fraction of the usual step that still
            // fits, so the last appends do not copy the arrays once per entry.
            int minEntries = growsEntries ? entries : entryCapacity;
            int minPhases = growsPhases ? phaseCount : phaseCapacity;
            nextTable = growsTable ? tableCapacityFor(liveEntries, 1) : tableCapacity;
            if (bytesFor(minEntries, minPhases, nextTable) > maxBytes) return false;
            long fits = 0, exceeds = 1L << 20;
            while (exceeds - fits > 1) {
                long middle = (fits + exceeds) >>> 1;
                if (bytesFor(between(minEntries, nextEntries, middle), between(minPhases, nextPhases, middle),
                        nextTable) <= maxBytes) {
                    fits = middle;
                } else {
                    exceeds = middle;
                }
            }
            nextEntries = between(minEntries, nextEntries, fits);
            nextPhases = between(minPhases, nextPhases, fits);
        }
        if (growsEntries || growsPhases) storage = current.grownTo(nextEntries, nextPhases);
        if (growsTable) rehash(nextTable);
        return true;
    }

    /** {@code low} plus {@code fraction / 2^20} of the way to {@code high}. */
    private static int between(int low, int high, long fraction) {
        return (int) (low + ((high - (long) low) * fraction >> 20));
    }

    private static int grown(int capacity, int needed, int minimum) {
        long next = Math.max(Math.max(needed, minimum), capacity + (long) (capacity >> 1));
        return (int) Math.min(next, Integer.MAX_VALUE - 16);
    }

    private static int tableCapacityFor(int liveEntries, int minimum) {
        int capacity = Math.max(2, Integer.highestOneBit(Math.max(1, minimum - 1)) << 1);
        while (liveEntries > capacity / 4L * 3) capacity <<= 1;
        return capacity;
    }

    private long bytesFor(int entryCapacity, int phaseCapacity, int tableCapacity) {
        if (entryCapacity == 0 && tableCapacity == 0) return 0;
        long intArray = alignedArrayBytes(entryCapacity, Integer.BYTES);
        return FIXED_BYTES
                + 2 * intArray // pixels and escaped masks
                + alignedArrayBytes(entryCapacity + 1L, Integer.BYTES) // phase offsets
                + alignedArrayBytes(entryCapacity, Float.BYTES)
                + alignedArrayBytes(entryCapacity, Byte.BYTES)
                + alignedArrayBytes(phaseCapacity, Short.BYTES)
                + alignedArrayBytes(tableCapacity, Integer.BYTES)
                + 2 * intArray; // prepaid snapshot and palette-selection indices
    }

    private static long alignedArrayBytes(long length, int elementBytes) {
        return (ARRAY_HEADER_BYTES + length * elementBytes + 15) & ~15L;
    }

    private int slotOf(int pixel) {
        if (table.length == 0) return 0;
        int mask = table.length - 1;
        int slot = mix(pixel) & mask;
        while (table[slot] != 0 && storage.pixels[table[slot] - 1] != pixel) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private static int mix(int pixel) {
        int hash = pixel * 0x9E3779B9;
        return hash ^ (hash >>> 16);
    }

    private void rehash(int capacity) {
        int[] next = new int[capacity];
        int mask = capacity - 1;
        for (int value : table) {
            if (value == 0) continue;
            int slot = mix(storage.pixels[value - 1]) & mask;
            while (next[slot] != 0) slot = (slot + 1) & mask;
            next[slot] = value;
        }
        table = next;
    }

    private boolean isLive(int index) {
        // Without replaced entries every stored entry is live, which skips the probe.
        return storage.count == live
                || table.length > 0 && table[slotOf(storage.pixels[index])] == index + 1;
    }

    /**
     * Rewrites the live entries into new exact-size arrays, mapping their pixels
     * through {@code shift} when one is given; snapshots keep the old arrays.
     */
    private void rebuild(PixelShift shift, int sourceWidth, int width, int height) {
        Storage source = storage;
        int kept = 0;
        int keptPhases = 0;
        int[] targets = new int[source.count];
        for (int index = 0; index < source.count; index++) {
            targets[index] = -1;
            if (!isLive(index)) continue;
            int pixel = source.pixels[index];
            if (shift != null) {
                int x = pixel % sourceWidth + shift.dx();
                int y = pixel / sourceWidth + shift.dy();
                if (x < 0 || x >= width || y < 0 || y >= height) continue;
                pixel = y * width + x;
            }
            targets[index] = pixel;
            kept++;
            keptPhases += source.offsets[index + 1] - source.offsets[index];
        }
        Storage rebuilt = kept == 0 ? Storage.EMPTY : new Storage(kept, keptPhases);
        for (int index = 0; index < source.count; index++) {
            if (targets[index] < 0) continue;
            rebuilt.copyFrom(source, index, targets[index]);
        }
        storage = rebuilt;
        live = kept;
        // No larger than the old table, which held at least these entries within the budget.
        table = kept == 0 ? new int[0] : new int[tableCapacityFor(kept, 1)];
        int mask = table.length - 1;
        for (int index = 0; index < kept; index++) {
            int slot = mix(rebuilt.pixels[index]) & mask;
            while (table[slot] != 0) slot = (slot + 1) & mask;
            table[slot] = index + 1;
        }
        recolorSnapshot = null;
    }

    /** Worker-owned staging, bounded to one 32x32 tile; no cache lock is acquired. */
    public Batch newBatch(int capacity, SmoothPaletteColoring coloring) {
        return new Batch(capacity, Objects.requireNonNull(coloring));
    }

    /** Atomically admits a completed tile. The caller must first validate its render generation. */
    public synchronized void merge(Batch batch) {
        if (batch.owner != this || batch.merged) {
            throw new IllegalArgumentException("Batch belongs to another cache or has already been merged");
        }
        batch.merged = true;
        if (batch.size == 0) return;
        prepareScale(batch.coloring.colorScale());
        for (int index = 0; index < batch.size; index++) {
            int from = batch.offsets[index];
            append(batch.pixels[index], batch.phases, from, batch.offsets[index + 1] - from,
                    batch.masks[index], batch.strengths[index], batch.deep[index]);
        }
    }

    public final class Batch {
        private final AntialiasSampleCache owner = AntialiasSampleCache.this;
        private final SmoothPaletteColoring coloring;
        private final int[] pixels;
        private final int[] masks;
        private final float[] strengths;
        private final boolean[] deep;
        private final int[] offsets;
        private short[] phases = new short[0];
        private int size;
        private boolean merged;

        private Batch(int capacity, SmoothPaletteColoring coloring) {
            if (capacity < 1 || capacity > 32 * 32) {
                throw new IllegalArgumentException("AA staging is limited to one 32x32 tile");
            }
            this.coloring = coloring;
            pixels = new int[capacity];
            masks = new int[capacity];
            strengths = new float[capacity];
            deep = new boolean[capacity];
            offsets = new int[capacity + 1];
        }

        /**
         * Copies phases once and returns their display color without a second cache lookup.
         * {@code edgeStrength} is the pixel's base-phase edge strength, infinite across the
         * set boundary or a distance-estimate edge.
         */
        public Integer add(int pixel, FractalSample[] source, SmoothColorLookup lookup,
                           double edgeStrength, boolean deepZoom) {
            if (merged || size == pixels.length) throw new IllegalStateException("AA batch is full or merged");
            int length = validatedLength(source);
            // Match the uncached color path when one pixel cannot fit in the configured cache.
            if (bytesFor(1, length, tableCapacityFor(1, 1)) > maxBytes) return null;
            int from = offsets[size];
            if (from + length > phases.length) {
                phases = Arrays.copyOf(phases, Math.max(from + length, Math.max(64, phases.length * 2)));
            }
            pixels[size] = pixel;
            masks[size] = encode(source, coloring, phases, from);
            strengths[size] = (float) edgeStrength;
            deep[size] = deepZoom;
            offsets[size + 1] = from + length;
            size++;
            return color(phases, from, from + length, masks[size - 1], lookup);
        }

        public boolean isEmpty() { return size == 0; }
    }

    private static int validatedLength(FractalSample[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("AA samples cannot be empty");
        }
        if (samples.length > MAX_SAMPLES) {
            throw new IllegalArgumentException("At most 32 AA samples are supported");
        }
        return samples.length;
    }

    /** Writes the escaped samples' phases at {@code offset} and returns the escaped mask. */
    private static int encode(FractalSample[] samples, SmoothPaletteColoring coloring,
                              short[] target, int offset) {
        int escapedMask = 0;
        for (int index = 0; index < samples.length; index++) {
            FractalSample sample = Objects.requireNonNull(samples[index]);
            short phase = 0;
            if (sample.escaped()) {
                escapedMask |= 1 << index;
                phase = SmoothColorLookup.encode(coloring.basePhase(sample.smoothIterations()));
            }
            target[offset + index] = phase;
        }
        return escapedMask;
    }

    public synchronized Integer color(int pixelIndex, ColoringStrategy coloring, int maxIterations) {
        return coloring instanceof SmoothPaletteColoring smooth
                && Double.compare(cachedColorScale, smooth.colorScale()) == 0
                ? color(pixelIndex, new SmoothColorLookup(smooth))
                : null;
    }

    public synchronized Integer color(int pixelIndex, SmoothColorLookup lookup) {
        int index = indexOf(pixelIndex);
        return index < 0 ? null : storage.color(index, lookup);
    }

    private int indexOf(int pixel) {
        if (table.length == 0) return -1;
        return table[slotOf(pixel)] - 1;
    }

    /**
     * Applies an immutable cache snapshot in parallel. No cache lock is taken in
     * the per-pixel animation loop, and each worker writes a distinct pixel.
     */
    public void recolorInto(int[] colors, SmoothColorLookup lookup) {
        snapshot().recolorInto(colors, lookup);
    }

    /** Entries that {@code coloring}'s palette selects, or none for another color scale. */
    public synchronized Snapshot snapshotFor(SmoothPaletteColoring coloring) {
        return Double.compare(cachedColorScale, coloring.colorScale()) == 0
                ? snapshot().forPalette(coloring.palette()) : Snapshot.EMPTY;
    }

    public synchronized boolean contains(int pixelIndex) {
        return indexOf(pixelIndex) >= 0;
    }

    public synchronized int size() {
        return live;
    }

    /**
     * True when an entry was rejected for the budget since the cache was last cleared:
     * the cached samples may then lack AA candidates that a completed pass sampled.
     */
    public synchronized boolean dropped() {
        return dropped;
    }

    /** Appends rejected for the budget so far, unaffected by clearing; a pass compares it. */
    public synchronized long rejections() {
        return rejections;
    }

    /**
     * Records that the samples are complete again: a finished pass re-sampled every
     * candidate the rejected ones could have been, and stored all of them.
     */
    public synchronized void markComplete() {
        dropped = false;
    }

    /** Conservative retained storage, including growth slack and the current snapshot's indices. */
    public synchronized long usedBytes() {
        return bytesFor(storage.capacity(), storage.phases.length, table.length);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public synchronized void clear() {
        storage = Storage.EMPTY;
        table = new int[0];
        live = 0;
        dropped = false;
        recolorSnapshot = null;
        cachedColorScale = Double.NaN;
    }

    /**
     * An immutable view of every cached pixel for retention beside its completed frame,
     * or {@code null} when nothing is cached. The view shares the append-only arrays.
     */
    public synchronized Retained retain() {
        return live == 0 ? null
                : new Retained(snapshot(), cachedColorScale, usedBytes(), Double.POSITIVE_INFINITY, dropped);
    }

    /** Replaces the contents with retained samples, subject to this cache's own budget. */
    public synchronized void restore(Retained retained) {
        clear();
        cachedColorScale = retained.colorScale;
        Snapshot source = retained.snapshot;
        Storage from = source.storage;
        for (int index : source.indices) {
            int offset = from.offsets[index];
            append(from.pixels[index], from.phases, offset, from.offsets[index + 1] - offset,
                    from.masks[index], from.strengths[index], from.deep[index] != 0);
        }
        if (!dropped) recolorSnapshot = source;
        dropped |= retained.dropped;
    }

    /** Retains samples whose pixels remain visible after an integer pan. */
    public synchronized void shift(int width, int height, PixelShift shift) {
        shift(width, width, height, shift);
    }

    public synchronized void shift(int sourceWidth, int width, int height, PixelShift shift) {
        if (storage.count == 0) return;
        rebuild(Objects.requireNonNull(shift), sourceWidth, width, height);
    }

    private synchronized Snapshot snapshot() {
        if (recolorSnapshot != null) {
            return recolorSnapshot;
        }
        int[] indices = new int[live];
        int next = 0;
        for (int index = 0; index < storage.count; index++) {
            if (isLive(index)) indices[next++] = index;
        }
        recolorSnapshot = live == 0 ? Snapshot.EMPTY : new Snapshot(storage, indices);
        return recolorSnapshot;
    }

    /**
     * Append-only entry arrays. Entries below {@link #count} never change, so snapshots
     * may share the arrays while the owning cache appends beyond them.
     */
    private static final class Storage {
        static final Storage EMPTY = new Storage(0, 0);
        final int[] pixels;
        final int[] masks;
        final float[] strengths;
        final byte[] deep;
        /** Entry {@code i}'s phases are {@code phases[offsets[i] .. offsets[i + 1])}. */
        final int[] offsets;
        final short[] phases;
        int count;
        int phaseCount;

        Storage(int entryCapacity, int phaseCapacity) {
            pixels = new int[entryCapacity];
            masks = new int[entryCapacity];
            strengths = new float[entryCapacity];
            deep = new byte[entryCapacity];
            offsets = new int[entryCapacity + 1];
            phases = new short[phaseCapacity];
        }

        int capacity() { return pixels.length; }

        Storage grownTo(int entryCapacity, int phaseCapacity) {
            Storage grown = new Storage(entryCapacity, phaseCapacity);
            System.arraycopy(pixels, 0, grown.pixels, 0, count);
            System.arraycopy(masks, 0, grown.masks, 0, count);
            System.arraycopy(strengths, 0, grown.strengths, 0, count);
            System.arraycopy(deep, 0, grown.deep, 0, count);
            System.arraycopy(offsets, 0, grown.offsets, 0, count + 1);
            System.arraycopy(phases, 0, grown.phases, 0, phaseCount);
            grown.count = count;
            grown.phaseCount = phaseCount;
            return grown;
        }

        void copyFrom(Storage source, int index, int pixel) {
            int from = source.offsets[index];
            int length = source.offsets[index + 1] - from;
            pixels[count] = pixel;
            masks[count] = source.masks[index];
            strengths[count] = source.strengths[index];
            deep[count] = source.deep[index];
            System.arraycopy(source.phases, from, phases, phaseCount, length);
            phaseCount += length;
            offsets[++count] = phaseCount;
        }

        int length(int index) { return offsets[index + 1] - offsets[index]; }

        int color(int index, SmoothColorLookup lookup) {
            return AntialiasSampleCache.color(phases, offsets[index], offsets[index + 1], masks[index], lookup);
        }
    }

    private static int color(short[] phases, int from, int to, int escapedMask, SmoothColorLookup lookup) {
        long alpha = 0;
        double red = 0, green = 0, blue = 0;
        for (int index = from; index < to; index++) {
            boolean escaped = (escapedMask & 1 << (index - from)) != 0;
            int color = escaped ? lookup.color(phases[index]) : 0xFF000000;
            alpha += color >>> 24;
            if (escaped) {
                red += lookup.linearRed(phases[index]);
                green += lookup.linearGreen(phases[index]);
                blue += lookup.linearBlue(phases[index]);
            }
        }
        int count = to - from;
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

    /** Retained palette-independent samples of one completed frame, with their accounted size. */
    public static final class Retained {
        private final Snapshot snapshot;
        private final double colorScale;
        private final long bytes;
        private final double completeThreshold;
        private final boolean dropped;

        private Retained(Snapshot snapshot, double colorScale, long bytes, double completeThreshold,
                         boolean dropped) {
            this.snapshot = snapshot;
            this.colorScale = colorScale;
            this.bytes = bytes;
            this.completeThreshold = completeThreshold;
            this.dropped = dropped;
        }

        public int size() { return snapshot.size(); }

        /** The conservative size charged while these samples are retained. */
        public long bytes() { return bytes; }

        public double colorScale() { return colorScale; }

        /**
         * The smallest phase threshold of a finished AA pass over these samples, or
         * positive infinity when none finished.
         */
        public double completeThreshold() { return completeThreshold; }

        public Retained withCompleteThreshold(double threshold) {
            return Double.compare(threshold, completeThreshold) == 0 ? this
                    : new Retained(snapshot, colorScale, bytes, threshold, dropped);
        }

        /** True when the cache had rejected samples for the budget before retaining these. */
        public boolean dropped() { return dropped; }

        /** The retained samples {@code coloring}'s palette selects, or none for another color scale. */
        public Snapshot snapshotFor(SmoothPaletteColoring coloring) {
            return Double.compare(colorScale, coloring.colorScale()) == 0
                    ? snapshot.forPalette(coloring.palette()) : Snapshot.EMPTY;
        }
    }

    /** Immutable generation of the cache, shared by CPU and GPU recoloring. */
    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(Storage.EMPTY, new int[0]);
        private final Storage storage;
        /** Entries of {@link #storage}, all below its count when this snapshot was taken. */
        private final int[] indices;
        private final int words;
        private final int maxPixel;
        private volatile Selection selection;

        /** The last palette-threshold selection, reused while palette animation keeps it. */
        private record Selection(double direct, double deep, Snapshot snapshot) {}

        private Snapshot(Storage storage, int[] indices) {
            this.storage = storage;
            this.indices = indices;
            int count = Math.multiplyExact(indices.length, 4);
            int max = -1;
            for (int index : indices) {
                count = Math.addExact(count, (storage.length(index) + 1) / 2);
                max = Math.max(max, storage.pixels[index]);
            }
            words = count;
            maxPixel = max;
        }

        public int size() { return indices.length; }

        /** The entries whose edge strength exceeds {@code palette}'s phase threshold. */
        public Snapshot forPalette(Palette palette) {
            if (indices.length == 0) return this;
            double direct = PaletteEdgeThresholds.phaseThreshold(palette, false);
            double deep = PaletteEdgeThresholds.phaseThreshold(palette, true);
            Selection current = selection;
            if (current != null && current.direct == direct && current.deep == deep) {
                return current.snapshot;
            }
            int[] kept = new int[indices.length];
            int count = 0;
            for (int index : indices) {
                double threshold = storage.deep[index] != 0 ? deep : direct;
                if (storage.strengths[index] > threshold) kept[count++] = index;
            }
            Snapshot selected = count == indices.length ? this
                    : new Snapshot(storage, Arrays.copyOf(kept, count));
            selection = new Selection(direct, deep, selected);
            return selected;
        }

        public int gpuWordCount() { return words; }
        public int maxPixelIndex() { return maxPixel; }

        /** An independent membership mask for a refinement's read-only cache snapshot. */
        public BitSet pixelMask() {
            BitSet mask = new BitSet();
            for (int index : indices) mask.set(storage.pixels[index]);
            return mask;
        }

        public void recolorInto(int[] colors, SmoothColorLookup lookup) {
            IntStream.range(0, indices.length).parallel().forEach(i -> {
                int index = indices[i];
                colors[storage.pixels[index]] = storage.color(index, lookup);
            });
        }

        /** Four metadata words per pixel, followed by packed pairs of 16-bit phases. */
        public void writeGpuWords(java.nio.IntBuffer target) {
            int offset = indices.length * 4;
            for (int index : indices) {
                int length = storage.length(index);
                target.put(storage.pixels[index]).put(offset).put(length).put(storage.masks[index]);
                offset += (length + 1) / 2;
            }
            for (int index : indices) {
                int from = storage.offsets[index];
                int to = storage.offsets[index + 1];
                for (int i = from; i < to; i += 2) {
                    target.put(Short.toUnsignedInt(storage.phases[i])
                            | (i + 1 < to ? Short.toUnsignedInt(storage.phases[i + 1]) << 16 : 0));
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
