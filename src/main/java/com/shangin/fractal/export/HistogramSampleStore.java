package com.shangin.fractal.export;

import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.PixelShift;

import java.util.Arrays;

/**
 * Coloring-independent work of antialiasing passes under Histogram Coloring on one
 * standard-zoom frame: the subpixel samples of each sampled candidate and whether the
 * distance estimate puts the boundary inside each tested pixel. A histogram maps the
 * samples of every completed frame differently, so its AA colors cannot follow a pan the
 * way cached smooth phases do; these values can. A shifted frame keeps the origin of its
 * source grid, so the same pixel has bit-identical subpixel coordinates there, and a pass
 * that reads them publishes exactly what a pass sampling everything again would.
 *
 * <p>Only the values that {@link com.shangin.fractal.coloring.HistogramPaletteColoring}
 * reads are kept: smooth iterations and escape flags. Callers synchronize every access
 * except reads of their own pixels during a pass: tiles write their pixels only through
 * {@link #commit}, under the caller's lock, and a shift or clear happens only while no
 * pass of the bound frame can still commit.
 */
final class HistogramSampleStore {

    static final long DEFAULT_MAX_BYTES = Long.getLong(
            "fractal.aaHistogramCache.maxBytes", 256L * 1024L * 1024L);
    private static final byte UNTESTED = 0;
    private static final byte EDGE = 1;
    private static final byte NOT_EDGE = 2;
    /**
     * Per pixel: a block reference (counted as uncompressed, 8 bytes), its entry index and
     * its distance flag, in three frame-sized arrays.
     */
    static final long BYTES_PER_PIXEL = Long.BYTES + Integer.BYTES + 1L;
    /** Object and array headers, conservatively for a 64-bit layout. */
    private static final long ARRAY_HEADER_BYTES = 16;
    private static final long BLOCK_HEADER_BYTES = 32 + 3 * ARRAY_HEADER_BYTES;

    /** Samples committed by one tile: entry {@code i} holds {@code values[offsets[i]..offsets[i + 1])}. */
    static final class Block {
        private final int[] masks;
        private final int[] offsets;
        private final double[] values;
        /** The last {@link Move} that charged this tile; written only by moves, on one thread. */
        private long chargedBy;

        Block(int[] masks, int[] offsets, double[] values) {
            this.masks = masks;
            this.offsets = offsets;
            this.values = values;
        }

        int[] masks() { return masks; }

        int[] offsets() { return offsets; }

        double[] values() { return values; }
    }

    /** Identifies moves, so a tile kept by several pixels is charged once per move. */
    private static final java.util.concurrent.atomic.AtomicLong MOVES = new java.util.concurrent.atomic.AtomicLong();

    private final long maxBytes;
    private int width;
    private int height;
    private int maxIterations;
    private Block[] blockOf = new Block[0];
    private int[] entryOf = new int[0];
    private byte[] distanceEdges = new byte[0];
    private long bytes;
    /** A shift recorded by {@link #shift} and not yet applied: the bound arrays still hold the source. */
    private boolean shiftPending;
    private int pendingWidth;
    private int pendingHeight;
    private long pendingDx;
    private long pendingDy;
    /** Changes whenever the bound values are replaced or a shift is recorded. */
    private long version;

    HistogramSampleStore(long maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("Cache limit cannot be negative");
        this.maxBytes = maxBytes;
    }

    /** Prepares storage for a frame of this size and iteration limit, clearing values of any other. */
    void bind(int width, int height, int maxIterations) {
        if (shiftPending) {
            Move move = pendingMove();
            move.compute();
            install(move);
        }
        if (this.width == width && this.height == height && this.maxIterations == maxIterations
                && blockOf.length == width * height) {
            return;
        }
        clear();
        long pixelBytes = pixelBytes(width, height);
        if (pixelBytes > maxBytes) return;
        this.width = width;
        this.height = height;
        this.maxIterations = maxIterations;
        blockOf = new Block[width * height];
        entryOf = new int[width * height];
        distanceEdges = new byte[width * height];
        bytes = pixelBytes;
    }

    void clear() {
        shiftPending = false;
        pendingDx = 0;
        pendingDy = 0;
        version++;
        width = 0;
        height = 0;
        maxIterations = 0;
        blockOf = new Block[0];
        entryOf = new int[0];
        distanceEdges = new byte[0];
        bytes = 0;
    }

    /**
     * The values bound now, for one pass to read without the lock: a later shift or clear
     * replaces the arrays instead of changing these, and {@link #commit} adds to them only
     * while they are still bound. {@code null} when nothing is bound.
     */
    View view() {
        return blockOf.length == 0 ? null : new View(blockOf, entryOf, distanceEdges);
    }

    /** Read access to the values one pass started with; a tile reads only its own pixels. */
    record View(Block[] blockOf, int[] entryOf, byte[] distanceEdges) {

        /** Whether the distance estimate of {@code pixel} put an edge in it, or {@code null} if untested. */
        Boolean distanceEdge(int pixel) {
            return switch (distanceEdges[pixel]) {
                case EDGE -> Boolean.TRUE;
                case NOT_EDGE -> Boolean.FALSE;
                default -> null;
            };
        }

        boolean hasSamples(int pixel) {
            return blockOf[pixel] != null;
        }

        /** Colors {@code pixel}'s stored samples exactly as {@link AdaptivePngExportService#colorSamples} would. */
        int color(int pixel, com.shangin.fractal.coloring.ColoringStrategy coloring, int maxIterations) {
            Block block = blockOf[pixel];
            int entry = entryOf[pixel];
            int from = block.offsets()[entry];
            return AdaptivePngExportService.colorSampleValues(block.values(), from,
                    block.offsets()[entry + 1] - from, block.masks()[entry], coloring, maxIterations);
        }
    }

    /** Values one tile adds; committed together under the caller's lock. */
    static final class Batch {
        private int[] pixels = new int[16];
        private int[] masks = new int[16];
        private int[] offsets = new int[17];
        private double[] values = new double[256];
        private int count;
        private int[] testedPixels = new int[64];
        private byte[] testedFlags = new byte[64];
        private int tested;

        void recordDistanceEdge(int pixel, boolean edge) {
            if (tested == testedPixels.length) {
                testedPixels = Arrays.copyOf(testedPixels, tested * 2);
                testedFlags = Arrays.copyOf(testedFlags, tested * 2);
            }
            testedPixels[tested] = pixel;
            testedFlags[tested++] = edge ? EDGE : NOT_EDGE;
        }

        void addSamples(int pixel, FractalSample[] samples) {
            if (samples.length > Integer.SIZE) throw new IllegalArgumentException("Too many samples");
            if (count == pixels.length) {
                pixels = Arrays.copyOf(pixels, count * 2);
                masks = Arrays.copyOf(masks, count * 2);
                offsets = Arrays.copyOf(offsets, count * 2 + 1);
            }
            int from = offsets[count];
            if (from + samples.length > values.length) {
                values = Arrays.copyOf(values, Math.max(values.length * 2, from + samples.length));
            }
            int mask = 0;
            for (int index = 0; index < samples.length; index++) {
                values[from + index] = samples[index].smoothIterations();
                if (samples[index].escaped()) mask |= 1 << index;
            }
            pixels[count] = pixel;
            masks[count] = mask;
            offsets[++count] = from + samples.length;
        }
    }

    /**
     * Adds a tile's values to {@code view} while it is still bound; its samples only when
     * they fit the budget. Caller owns the lock.
     */
    void commit(View view, Batch batch) {
        if (view == null || view.blockOf() != blockOf) return;
        for (int index = 0; index < batch.tested; index++) {
            distanceEdges[batch.testedPixels[index]] = batch.testedFlags[index];
        }
        int count = batch.count;
        if (count == 0) return;
        long added = blockBytes(count, batch.offsets[count]);
        if (bytes + added > maxBytes) return;
        Block block = new Block(Arrays.copyOf(batch.masks, count), Arrays.copyOf(batch.offsets, count + 1),
                Arrays.copyOf(batch.values, batch.offsets[count]));
        for (int entry = 0; entry < count; entry++) {
            int pixel = batch.pixels[entry];
            if (blockOf[pixel] == null) {
                blockOf[pixel] = block;
                entryOf[pixel] = entry;
            }
        }
        bytes += added;
    }

    /**
     * Records that the frame of {@code sourceWidth} moves to a target of the given size
     * along the frame-reuse {@code shift}: target pixel {@code (x, y)} takes source pixel
     * {@code (x - dx, y - dy)}. Recording is constant-time, so frame reuse on the FX thread
     * does not wait for it; the arrays move before the next pass reads them, see
     * {@link #prepareMove}. Successive shifts add up: every value stays exact for the
     * grid coordinate it was calculated at, including values an intermediate frame clipped.
     */
    void shift(int sourceWidth, int targetWidth, int targetHeight, PixelShift shift, int maxIterations) {
        int currentWidth = shiftPending ? pendingWidth : width;
        // A target whose per-pixel arrays alone exceed the budget could keep no values, so
        // the move would allocate them only to discard them: its pass samples as usual.
        if (blockOf.length == 0 || maxIterations != this.maxIterations || sourceWidth != currentWidth
                || pixelBytes(targetWidth, targetHeight) > maxBytes) {
            clear();
            return;
        }
        shiftPending = true;
        pendingDx += shift.dx();
        pendingDy += shift.dy();
        pendingWidth = targetWidth;
        pendingHeight = targetHeight;
        version++;
    }

    /**
     * Captures the pending shift for {@link Move#compute} without the caller's lock, or
     * returns {@code null} when none is pending. Caller owns the lock.
     */
    Move prepareMove() {
        return shiftPending ? pendingMove() : null;
    }

    /** Caller owns the lock and has checked that a shift is pending. */
    private Move pendingMove() {
        return new Move(version, blockOf, entryOf, distanceEdges, width, height,
                pendingWidth, pendingHeight, pendingDx, pendingDy);
    }

    /** Applies a computed move unless the values changed since it was prepared. Caller owns the lock. */
    void install(Move move) {
        if (move == null || move.version != version || move.blockOf == null) return;
        shiftPending = false;
        pendingDx = 0;
        pendingDy = 0;
        version++;
        width = move.targetWidth;
        height = move.targetHeight;
        blockOf = move.blockOf;
        entryOf = move.entryOf;
        distanceEdges = move.distanceEdges;
        bytes = move.liveBytes;
        if (bytes > maxBytes) clear();
    }

    /** One pending shift, calculated from arrays that are no longer written. */
    static final class Move {
        private final long version;
        private final Block[] sourceBlocks;
        private final int[] sourceEntries;
        private final byte[] sourceEdges;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int targetWidth;
        private final int targetHeight;
        private final long dx;
        private final long dy;
        private Block[] blockOf;
        private int[] entryOf;
        private byte[] distanceEdges;
        private long liveBytes;

        private Move(long version, Block[] sourceBlocks, int[] sourceEntries, byte[] sourceEdges,
                     int sourceWidth, int sourceHeight, int targetWidth, int targetHeight, long dx, long dy) {
            this.version = version;
            this.sourceBlocks = sourceBlocks;
            this.sourceEntries = sourceEntries;
            this.sourceEdges = sourceEdges;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            this.dx = dx;
            this.dy = dy;
        }

        /** Moves the values; the budget then counts only tiles with entries still in the target frame. */
        void compute() {
            Block[] blocks = new Block[targetWidth * targetHeight];
            int[] entries = new int[targetWidth * targetHeight];
            byte[] edges = new byte[targetWidth * targetHeight];
            long live = pixelBytes(targetWidth, targetHeight);
            // A tile's arrays stay whole while any of its entries is in the frame.
            long move = MOVES.incrementAndGet();
            for (int y = 0; y < targetHeight; y++) {
                long sourceY = y - dy;
                if (sourceY < 0 || sourceY >= sourceHeight) continue;
                for (int x = 0; x < targetWidth; x++) {
                    long sourceX = x - dx;
                    if (sourceX < 0 || sourceX >= sourceWidth) continue;
                    int source = (int) sourceY * sourceWidth + (int) sourceX;
                    int target = y * targetWidth + x;
                    edges[target] = sourceEdges[source];
                    Block block = sourceBlocks[source];
                    if (block == null) continue;
                    int entry = sourceEntries[source];
                    blocks[target] = block;
                    entries[target] = entry;
                    if (block.chargedBy != move) {
                        block.chargedBy = move;
                        live += blockBytes(block.masks().length, block.values().length);
                    }
                }
            }
            blockOf = blocks;
            entryOf = entries;
            distanceEdges = edges;
            liveBytes = live;
        }
    }

    static long pixelBytes(int width, int height) {
        return Math.addExact(Math.multiplyExact((long) width * height, BYTES_PER_PIXEL), 3 * ARRAY_HEADER_BYTES);
    }

    /** One committed tile: its masks and offsets, its values and the headers of all three arrays. */
    static long blockBytes(int entries, int values) {
        return BLOCK_HEADER_BYTES + (long) entries * Integer.BYTES + (entries + 1L) * Integer.BYTES
                + (long) values * Double.BYTES;
    }

    /** Bytes charged against the budget, for tests. */
    long bytes() {
        return bytes;
    }
}
