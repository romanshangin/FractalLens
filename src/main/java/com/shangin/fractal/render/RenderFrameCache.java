package com.shangin.fractal.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/** Memory-bounded LRU history of completed frames for exact reverse-navigation reuse. */
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

        while (!entries.isEmpty() && cachedBytes + bytes > maxBytes) {
            Entry evicted = entries.removeLast();
            cachedBytes -= evicted.bytes();
        }

        entries.addFirst(new Entry(frame, bytes));
        cachedBytes += bytes;
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

    private static boolean sameCalculatedFrame(RenderJob cached, RenderJob requested) {
        return cached.formula().equals(requested.formula())
                && cached.viewport().equals(requested.viewport())
                && cached.width() == requested.width()
                && cached.height() == requested.height()
                && cached.maxIterations() == requested.maxIterations();
    }

    private record Entry(RenderFrame frame, long bytes) {}
}
