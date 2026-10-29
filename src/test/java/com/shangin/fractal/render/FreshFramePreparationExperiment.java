package com.shangin.fractal.render;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/** Benchmark-only latest-request experiment; never used by the application controller. */
final class FreshFramePreparationExperiment implements AutoCloseable {
    record Prepared(RenderFrame frame, long started, long finished) {}
    record Snapshot(boolean allocating, boolean pending, boolean ready, boolean callbackQueued, boolean closed) {}
    private record Request(long generation, RenderJob job, Consumer<Prepared> success, Consumer<Throwable> failure) {}
    private record Ready(Request request, Prepared prepared, Throwable error) {}

    private final Executor callbacks;
    private final Function<RenderJob, RenderFrame> factory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "baseline-frame-preparation");
        thread.setDaemon(true);
        return thread;
    });
    private long generation;
    private Request pending;
    private Ready ready;
    private boolean draining, allocating, callbackQueued, closed;
    private CompletableFuture<Void> drained = CompletableFuture.completedFuture(null);

    FreshFramePreparationExperiment(Executor callbacks) {
        this(callbacks, RenderFrame::create);
    }

    FreshFramePreparationExperiment(Executor callbacks, Function<RenderJob, RenderFrame> factory) {
        this.callbacks = Objects.requireNonNull(callbacks);
        this.factory = Objects.requireNonNull(factory);
    }

    /** The returned stage drains actual factory work, including an allocation superseded in flight. */
    synchronized CompletableFuture<Void> submit(RenderJob job, Consumer<Prepared> success, Consumer<Throwable> failure) {
        if (closed) throw new IllegalStateException("Frame preparation is closed");
        Objects.requireNonNull(job);
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        pending = new Request(++generation, job, success, failure);
        ready = null;
        if (!draining) {
            draining = true;
            drained = new CompletableFuture<>();
            var completion = drained;
            worker.execute(() -> drain(completion));
        }
        return drained;
    }

    synchronized CompletableFuture<Void> cancel() {
        generation++;
        pending = null;
        ready = null;
        return drained;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(allocating, pending != null, ready != null, callbackQueued, closed);
    }

    private void drain(CompletableFuture<Void> completion) {
        while (true) {
            Request request;
            synchronized (this) {
                if (closed || pending == null) {
                    draining = false;
                    completion.complete(null);
                    return;
                }
                request = pending;
                pending = null;
                allocating = true;
            }
            Prepared prepared = null;
            Throwable error = null;
            try {
                long start = System.nanoTime();
                var frame = Objects.requireNonNull(factory.apply(request.job()), "Frame factory returned null");
                prepared = new Prepared(frame, start, System.nanoTime());
            } catch (Throwable failure) {
                error = failure;
            }
            synchronized (this) {
                allocating = false;
                if (closed || request.generation() != generation) continue;
                ready = new Ready(request, prepared, error);
                draining = false;
                if (!callbackQueued) {
                    callbackQueued = true;
                    try {
                        // This runnable owns no frame. Dispatch is atomic with replacement of the ready slot.
                        callbacks.execute(this::deliver);
                    } catch (RuntimeException dispatchFailure) {
                        callbackQueued = false;
                        ready = null;
                        completion.completeExceptionally(dispatchFailure);
                        return;
                    }
                }
            }
            completion.complete(null);
            return;
        }
    }

    /** Linearize publication with cancellation; the callback must not wait for this worker. */
    private synchronized void deliver() {
        callbackQueued = false;
        Ready result = ready;
        ready = null;
        if (closed || result == null || result.request().generation() != generation) return;
        if (result.error() == null) result.request().success().accept(result.prepared());
        else result.request().failure().accept(result.error());
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cancel();
        // Do not cancel a Future: an array allocation cannot be interrupted or declared drained early.
        worker.shutdown();
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return worker.awaitTermination(timeout, unit);
    }
}
