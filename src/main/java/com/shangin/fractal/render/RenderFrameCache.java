package com.shangin.fractal.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/**
 * Memory-bounded LRU history of completed frames for exact reverse-navigation reuse.
 * Entries of every formula share the one budget; a hit requires the same formula and
 * parameters, so frames survive fractal switches without matching another fractal.
 * A frame may also keep its finished antialiasing; those bytes count against the same
 * budget. When space runs short, palette-independent AA samples, the largest and least
 * valuable part, go first (least recently used frames first), then whole frames with
 * their AA colors. AA samples never displace a frame.
 */
public final class RenderFrameCache {

    /** Four 1080p sample frames, with room for their validity masks. */
    public static final long DEFAULT_MAX_BYTES = 192L * 1024L * 1024L;

    private static final long APPROXIMATE_BYTES_PER_PIXEL =
            Integer.BYTES + 2L * Double.BYTES + 1L + 1L;

    private final long maxBytes;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long cachedBytes;

    public RenderFrameCache() {
        this(DEFAULT_MAX_BYTES);
    }

    public RenderFrameCache(long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Frame cache budget must be positive");
        }
        this.maxBytes = maxBytes;
    }

    /** Adds a completed frame, replacing the older entry for the same request. */
    public void put(RenderFrame frame) {
        Objects.requireNonNull(frame);

        if (!frame.isComplete()) {
            return;
        }

        long bytes = estimatedBytes(frame);
        removeMatching(frame.request());

        if (bytes > maxBytes) {
            return;
        }

        dropSamplesFor(bytes, null);
        while (!entries.isEmpty() && cachedBytes + bytes > maxBytes) {
            Entry evicted = entries.removeLast();
            cachedBytes -= evicted.bytes();
        }

        entries.addFirst(new Entry(frame, bytes));
        cachedBytes += bytes;
    }

    /**
     * Keeps finished antialiasing beside its cached frame, replacing earlier AA. Does
     * nothing when {@code frame} itself is not cached. Room is made by dropping other
     * frames' AA samples, then this AA's own samples, and only then older frames.
     */
    public void retainRefinement(RenderFrame frame, RetainedRefinement refinement) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(refinement);
        Entry entry = promote(frame);
        if (entry == null) {
            return;
        }
        setRefinement(entry, null);
        RetainedRefinement colorsOnly = refinement.withoutSamples();
        // Samples never displace frames: without room for them, only the colors are kept.
        if (!fits(entry, refinement)) {
            if (colorsOnly == null) {
                return;
            }
            refinement = colorsOnly;
        }
        if (entry.baseBytes + refinement.estimatedBytes() > maxBytes) {
            return;
        }
        long bytes = refinement.estimatedBytes();
        dropSamplesFor(bytes, entry);
        while (cachedBytes + bytes > maxBytes) {
            Entry evicted = entries.removeLast();
            cachedBytes -= evicted.bytes();
        }
        setRefinement(entry, refinement);
    }

    /** True when the AA fits beside its frame after every other entry's samples are dropped. */
    private boolean fits(Entry entry, RetainedRefinement refinement) {
        long withoutOtherSamples = cachedBytes;
        for (Entry other : entries) {
            if (other != entry && other.refinement != null && other.refinement.samples() != null) {
                RetainedRefinement colors = other.refinement.withoutSamples();
                withoutOtherSamples -= other.refinementBytes - (colors == null ? 0L : colors.estimatedBytes());
            }
        }
        return withoutOtherSamples + refinement.estimatedBytes() <= maxBytes;
    }

    /**
     * Drops AA samples, least recently used first, until {@code bytes} more fit or none
     * remain; AA kept as samples alone is dropped entirely.
     */
    private void dropSamplesFor(long bytes, Entry keep) {
        Iterator<Entry> iterator = entries.descendingIterator();
        while (cachedBytes + bytes > maxBytes && iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry != keep && entry.refinement != null && entry.refinement.samples() != null) {
                setRefinement(entry, entry.refinement.withoutSamples());
            }
        }
    }

    private void setRefinement(Entry entry, RetainedRefinement refinement) {
        cachedBytes -= entry.refinementBytes;
        entry.refinement = refinement;
        entry.refinementBytes = refinement == null ? 0L : refinement.estimatedBytes();
        cachedBytes += entry.refinementBytes;
    }

    /** The antialiasing retained beside this exact cached frame. */
    public Optional<RetainedRefinement> refinementFor(RenderFrame frame) {
        Objects.requireNonNull(frame);
        for (Entry entry : entries) {
            if (entry.frame == frame) return Optional.ofNullable(entry.refinement);
        }
        return Optional.empty();
    }

    private Entry promote(RenderFrame frame) {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.frame == frame) {
                iterator.remove();
                entries.addFirst(entry);
                return entry;
            }
        }
        return null;
    }

    /** Finds an exact scene/view/target match and promotes it to most-recently used. */
    public Optional<RenderFrame> findExact(RenderJob request) {
        Objects.requireNonNull(request);

        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!sameCalculatedFrame(entry.frame().request(), request)) {
                continue;
            }

            iterator.remove();
            entries.addFirst(entry);
            return Optional.of(entry.frame());
        }

        return Optional.empty();
    }

    public void clear() {
        entries.clear();
        cachedBytes = 0L;
    }

    int size() {
        return entries.size();
    }

    long cachedBytes() {
        return cachedBytes;
    }

    static long estimatedBytes(RenderFrame frame) {
        return Math.multiplyExact(
                (long) frame.request().width() * frame.request().height(),
                APPROXIMATE_BYTES_PER_PIXEL
        );
    }

    private void removeMatching(RenderJob request) {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (sameCalculatedFrame(entry.frame().request(), request)) {
                iterator.remove();
                cachedBytes -= entry.bytes();
            }
        }
    }

    /** True when both requests calculate identical samples, whatever their priority or preview coverage. */
    public static boolean sameCalculatedFrame(RenderJob cached, RenderJob requested) {
        return cached.formula().equals(requested.formula())
                && cached.sampleAccuracy() == requested.sampleAccuracy()
                && cached.viewport().equals(requested.viewport())
                && cached.width() == requested.width()
                && cached.height() == requested.height()
                && cached.maxIterations() == requested.maxIterations();
    }

    private static final class Entry {
        private final RenderFrame frame;
        private final long baseBytes;
        private RetainedRefinement refinement;
        private long refinementBytes;

        private Entry(RenderFrame frame, long baseBytes) {
            this.frame = frame;
            this.baseBytes = baseBytes;
        }

        RenderFrame frame() { return frame; }

        long bytes() { return baseBytes + refinementBytes; }
    }
}
