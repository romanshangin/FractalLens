package com.shangin.fractal.render;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Creates render frames over storage allocated ahead of time on a background thread.
 *
 * <p>A full-size frame is tens of megabytes. Allocating it means zero-filling and
 * page-faulting every page, which costs the JavaFX thread several milliseconds on a
 * Retina window before the preview of a gesture can be shown. This class keeps at most
 * one spare: empty storage for the most recent stable frame shape, allocated off the
 * caller's thread. A frame of that shape takes the spare; any other frame allocates its
 * storage directly, exactly as {@link RenderFrame#create(RenderJob, RenderGrid)} does.
 *
 * <p>Storage is never recycled. Every spare is newly allocated, is handed to exactly one
 * frame, and this class keeps no reference to it afterwards, so no other frame, render
 * worker, cache, surface or antialiasing service can see its samples. A frame created
 * from a spare holds the same zero samples, NaN orbit-trap distances and empty validity
 * as a newly allocated frame.
 *
 * <p>{@link #create} and {@link #prepareFor} are called on one thread, the JavaFX thread
 * in the application. The spare costs one frame of memory while it waits.
 */
public final class SpareFrameStorage implements AutoCloseable {

    private record Shape(int width, int height, boolean storesOrbitTrapDistances) {
        static Shape of(RenderJob job) {
            return new Shape(job.width(), job.height(),
                    RenderFrame.storesOrbitTrapDistances(job.formula()));
        }
    }

    private record Spare(Shape shape, FractalData.Storage samples, ValidityMask validity) {}

    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final AtomicReference<Spare> spare = new AtomicReference<>();
    private final AtomicBoolean preparationQueued = new AtomicBoolean();
    /** The shape the next preparation allocates; an allocation for an older shape is dropped. */
    private volatile Shape wanted;
    private volatile boolean closed;
    /** Shape of the previous frame created here; confined to the caller's thread. */
    private Shape lastCreated;
    /** Frames created over a spare; confined to the caller's thread. */
    private int sparesTaken;

    /** Prepares spares on an owned daemon thread that stops while idle. */
    public SpareFrameStorage() {
        ThreadPoolExecutor owned = new ThreadPoolExecutor(
                1, 1, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "fractal-frame-storage");
                    thread.setDaemon(true);
                    return thread;
                });
        owned.allowCoreThreadTimeOut(true);
        this.executor = owned;
        this.ownedExecutor = owned;
    }

    /** Prepares spares on the supplied executor, which must run tasks one at a time. */
    SpareFrameStorage(Executor executor) {
        this.executor = Objects.requireNonNull(executor);
        this.ownedExecutor = null;
    }

    /**
     * Creates an empty frame for the job and grid, over the spare when its shape matches.
     * Two consecutive frames of one shape, or a frame that used the spare, start the
     * preparation of the next spare. A changing shape, as during a window resize, does
     * not, so resizing never allocates spares that are dropped at the next size.
     */
    public RenderFrame create(RenderJob job, RenderGrid grid) {
        Objects.requireNonNull(job);
        Objects.requireNonNull(grid);
        Shape shape = Shape.of(job);
        // A spare of another shape is outdated; taking it also releases its memory.
        Spare taken = spare.getAndSet(null);
        boolean matched = taken != null && taken.shape().equals(shape);
        RenderFrame frame = matched
                ? RenderFrame.create(job, grid, taken.samples(), taken.validity())
                : RenderFrame.create(job, grid);
        if (matched) sparesTaken++;
        if (matched || shape.equals(lastCreated)) {
            request(shape);
        }
        lastCreated = shape;
        return frame;
    }

    /** Prepares a spare for frames of the job's shape unless one is ready or in preparation. */
    public void prepareFor(RenderJob job) {
        Objects.requireNonNull(job);
        request(Shape.of(job));
    }

    /** True when a spare for frames of the job's shape is ready. */
    boolean hasSpareFor(RenderJob job) {
        Spare current = spare.get();
        return current != null && current.shape().equals(Shape.of(job));
    }

    /** Number of frames created over a spare, read on the caller's thread. */
    int sparesTaken() {
        return sparesTaken;
    }

    private void request(Shape shape) {
        if (closed) return;
        wanted = shape;
        Spare current = spare.get();
        if (current != null && current.shape().equals(shape)) return;
        // At most one preparation waits; it reads the latest wanted shape when it runs.
        if (preparationQueued.compareAndSet(false, true)) {
            executor.execute(this::prepare);
        }
    }

    private void prepare() {
        preparationQueued.set(false);
        Shape shape = wanted;
        if (closed || shape == null) return;
        Spare current = spare.get();
        if (current != null && current.shape().equals(shape)) return;
        Spare prepared;
        try {
            prepared = new Spare(
                    shape,
                    FractalData.Storage.allocate(shape.width(), shape.height(),
                            shape.storesOrbitTrapDistances()),
                    new ValidityMask(shape.width(), shape.height()));
        } catch (OutOfMemoryError error) {
            // The spare is optional: the next frame allocates its own storage and reports
            // a real shortage on its own thread.
            System.getLogger(SpareFrameStorage.class.getName()).log(
                    System.Logger.Level.WARNING, "Spare frame storage could not be allocated", error);
            return;
        }
        if (closed || !shape.equals(wanted)) return;
        spare.set(prepared);
        // Close may have cleared the slot between the check above and the set.
        if (closed) spare.set(null);
    }

    /** Drops the spare and stops preparation; idempotent. */
    @Override
    public void close() {
        closed = true;
        spare.set(null);
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }
}
