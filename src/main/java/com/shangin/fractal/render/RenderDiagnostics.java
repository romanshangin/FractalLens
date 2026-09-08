package com.shangin.fractal.render;

import java.util.*;
import java.util.concurrent.*;

/** Opt-in request-owned scheduling diagnostics. Future cancellation is not worker exit. */
public final class RenderDiagnostics {
    public static final String PROPERTY = "fractal.render.diagnostics";
    private static final ThreadLocal<RenderDiagnostics> CURRENT = new ThreadLocal<>();
    public record Snapshot(long generation, Map<String, Long> values) {
        public Snapshot { values = Map.copyOf(values); }
    }
    private final long generation;
    private final Map<String, Long> values = new HashMap<>();
    private final CompletableFuture<Snapshot> completion = new CompletableFuture<>();
    private int registered, terminal;
    private boolean coordinatorTerminal, completionClaimed;

    RenderDiagnostics(long generation) {
        this.generation = generation;
        markLocal("request_submitted");
    }
    public static boolean enabled() { return Boolean.getBoolean(PROPERTY); }
    public CompletableFuture<Snapshot> completion() { return completion.copy(); }
    public synchronized Map<String, Long> currentValues() { return Map.copyOf(values); }
    private synchronized void markLocal(String key) { values.putIfAbsent(key, System.nanoTime()); }
    synchronized void cancelled() { markLocal("cancel_requested"); }
    synchronized void cancellationObserved() { markLocal("cancel_observed"); }
    synchronized void backendExited() { markLocal("backend_exit"); }
    static RenderDiagnostics current() { return CURRENT.get(); }
    static void mark(String key) { var current = CURRENT.get(); if (current != null) current.markLocal(key); }
    static void add(String key, long value) { var current = CURRENT.get(); if (current != null) current.addLocal(key, value); }
    private synchronized void addLocal(String key, long value) { values.merge(key, value, Long::sum); }
    private synchronized void register() { registered++; values.put("worker_tasks_registered", (long) registered); }
    private synchronized void taskSubmitted() { addLocal("worker_tasks_submitted", 1); markLocal("first_task_submitted"); }
    private synchronized void taskStarted(long queued) {
        long now = System.nanoTime();
        values.putIfAbsent("first_worker_start", now);
        addLocal("worker_tasks_started", 1);
        addLocal("worker_queue_sum_ns", now - queued);
        values.merge("worker_queue_max_ns", now - queued, Math::max);
    }
    private void taskEnded(long started) {
        Snapshot snapshot;
        synchronized (this) {
            long now = System.nanoTime();
            values.put("last_worker_exit", now);
            addLocal("worker_run_sum_ns", now - started);
            values.merge("worker_run_max_ns", now - started, Math::max);
            terminal++;
            snapshot = finishIfDrained();
        }
        complete(snapshot);
    }
    private void taskSkipped() {
        Snapshot snapshot;
        synchronized (this) {
            addLocal("worker_tasks_cancelled_before_start", 1);
            terminal++;
            snapshot = finishIfDrained();
        }
        complete(snapshot);
    }
    private void coordinatorEnded(boolean started) {
        Snapshot snapshot;
        synchronized (this) {
            coordinatorTerminal = true;
            markLocal(started ? "coordinator_exit" : "coordinator_cancelled_before_start");
            snapshot = finishIfDrained();
        }
        complete(snapshot);
    }
    // Called under our monitor, but completion callbacks always run outside it.
    private Snapshot finishIfDrained() {
        if (coordinatorTerminal && terminal == registered && !completionClaimed) {
            completionClaimed = true;
            values.put("request_drained", System.nanoTime());
            values.put("worker_tasks_terminal", (long) terminal);
            return new Snapshot(generation, values);
        }
        return null;
    }
    private void complete(Snapshot snapshot) {
        if (snapshot != null) completion.complete(snapshot);
    }

    FutureTask<Void> coordinatorTask(Runnable operation) {
        return new TrackedTask<>(Executors.callable(() -> {
            CURRENT.set(this);
            try { operation.run(); } finally { CURRENT.remove(); }
        }, null), true);
    }

    /** Same fixed-pool policy; normal runs use the original JDK executor directly. */
    static ExecutorService workerPool(int workers, ThreadFactory factory) {
        if (!enabled()) return Executors.newFixedThreadPool(workers, factory);
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory) {
            @Override protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
                var diagnostics = CURRENT.get();
                return diagnostics == null ? super.newTaskFor(callable) : diagnostics.new TrackedTask<>(callable, false);
            }
            @Override public void execute(Runnable command) {
                if (command instanceof RenderDiagnostics.TrackedTask<?> task) task.submitted();
                super.execute(command);
            }
        };
    }

    private final class TrackedTask<T> extends FutureTask<T> {
        private final boolean coordinator;
        private boolean running, skipped;
        private long queued;
        TrackedTask(Callable<T> callable, boolean coordinator) {
            super(callable);
            this.coordinator = coordinator;
            if (!coordinator) register();
        }
        void submitted() { queued = System.nanoTime(); taskSubmitted(); }
        @Override public void run() {
            synchronized (this) {
                // done() wins if cancellation happened before a worker claimed us.
                if (skipped || running) return;
                running = true;
            }
            long started = System.nanoTime();
            if (coordinator) markLocal("coordinator_start"); else taskStarted(queued);
            try { super.run(); }
            finally {
                if (coordinator) coordinatorEnded(true); else taskEnded(started);
            }
        }
        @Override protected void done() {
            synchronized (this) {
                if (running || skipped) return;
                skipped = true;
            }
            if (coordinator) coordinatorEnded(false); else taskSkipped();
        }
    }
}
