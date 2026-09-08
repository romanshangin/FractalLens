package com.shangin.fractal.ui;

import com.shangin.fractal.render.RenderDiagnostics;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Opt-in diagnostic recorder. Input and publication methods run on the FX thread.
 * Callback executors retain their original input/render identity across cancellation.
 * Times end at software boundaries; none is a physical display timestamp.
 */
public final class InteractionLatency {
    public record Event(long epoch, long input, long render, String stage, long nanos, long durationNanos) {}
    private record Token(long epoch, long input, long render) {}
    private final LongSupplier clock;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private final Set<String> milestones = new HashSet<>();
    private volatile boolean enabled;
    private volatile long epoch;
    private final AtomicInteger count = new AtomicInteger();
    private static final int MAX_EVENTS = 100_000;
    private final List<CompletableFuture<Void>> diagnostics = new ArrayList<>();
    private final List<CallbackStats> callbackStats = new ArrayList<>();
    private long input;
    private long render;
    private Token token = new Token(epoch, 0, 0);
    private Consumer<Event> publication = ignored -> {};

    public InteractionLatency() { this(System::nanoTime); }
    public boolean isEnabled() { return enabled; }
    InteractionLatency(LongSupplier clock) { this.clock = clock; }

    /** Arm only while the renderer is idle; one bounded benchmark trial per recording. */
    public synchronized void arm(Consumer<Event> publication) {
        enabled = false;
        epoch++;
        events.clear();
        count.set(0);
        milestones.clear();
        callbackStats.clear();
        diagnostics.clear();
        input = render = 0;
        token = new Token(epoch, 0, 0);
        this.publication = publication;
        enabled = true;
    }

    public synchronized List<Event> stop() {
        if (enabled) {
            if (!diagnosticsDrained().isDone()) throw new IllegalStateException("Render diagnostics have not drained");
            diagnosticsDrained().join();
            for (CallbackStats stats : callbackStats) stats.finish();
        }
        enabled = false;
        publication = ignored -> {};
        if (count.get() > MAX_EVENTS) throw new IllegalStateException("Latency recording exceeded event limit");
        return new ArrayList<>(events);
    }

    /** Submission runs on the FX thread; late worker exits retain this trial and render. */
    public synchronized void attachDiagnostics(CompletableFuture<RenderDiagnostics.Snapshot> completion) {
        if (!enabled) return;
        Token captured = token;
        diagnostics.add(completion.thenAccept(snapshot -> {
            long drained = snapshot.values().get("request_drained");
            snapshot.values().forEach((key, value) -> {
                boolean metric = key.endsWith("_ns") || key.startsWith("worker_tasks_")
                        || key.equals("planned_tasks") || key.equals("candidate_tiles");
                addAt(captured, "diagnostic_" + key, metric ? drained : value, metric ? value : 0);
            });
            addAt(captured, "diagnostic_generation", drained, snapshot.generation());
        }));
    }

    /** Await off the FX thread after UI idle, before stop(), to include cancelled workers. */
    public synchronized CompletableFuture<Void> diagnosticsDrained() {
        return CompletableFuture.allOf(diagnostics.toArray(CompletableFuture[]::new));
    }

    public void input(String kind) {
        if (!enabled) return;
        input++;
        add(new Token(epoch, input, 0), "input_" + kind, 0);
    }

    public void boundary(String kind) {
        if (enabled) add(new Token(epoch, input, 0), kind, 0);
    }

    public void renderStarted() {
        if (!enabled) return;
        token = new Token(epoch, input, ++render);
        add(token, "render_start", 0);
    }

    public void preview() {
        if (!enabled || input == 0) return;
        Event event = add(new Token(epoch, input, 0), "preview_publish", 0);
        if (event != null) publication.accept(event);
    }

    public void mark(String stage) {
        if (!enabled) return;
        if (!milestones.add(token.render + ":" + stage)) return;
        Event event = add(token, stage, 0);
        // An old render must not turn on a marker for a newer gesture.
        if (event != null && token.input == input && input > 0) publication.accept(event);
    }

    /** Records a software pulse or screen observation with the publication's identity. */
    public void observed(Event source, String suffix) {
        if (enabled) add(new Token(source.epoch, source.input, source.render), source.stage + suffix, 0);
    }

    public Executor callbacks(String phase, Executor delegate) {
        if (!enabled) return delegate;
        Token captured = token;
        CallbackStats stats = new CallbackStats(captured, phase);
        callbackStats.add(stats);
        AtomicBoolean first = new AtomicBoolean(true);
        return command -> {
            long queued = clock.getAsLong();
            if (first.compareAndSet(true, false)) add(captured, phase + "_first_ready", 0);
            delegate.execute(() -> {
                long start = clock.getAsLong();
                long queue = start - queued;
                if (stats.count++ == 0) add(captured, phase + "_first_callback", queue);
                stats.queueTotal += queue;
                stats.queueMax = Math.max(stats.queueMax, queue);
                try { command.run(); }
                finally {
                    long work = clock.getAsLong() - start;
                    stats.workTotal += work;
                    stats.workMax = Math.max(stats.workMax, work);
                }
            });
        };
    }

    // Callback bodies and stop() run on the FX thread. No per-tile event allocation.
    private final class CallbackStats {
        final Token identity;
        final String phase;
        long count, queueTotal, queueMax, workTotal, workMax;
        CallbackStats(Token identity, String phase) { this.identity = identity; this.phase = phase; }
        void finish() {
            add(identity, phase + "_callback_count", count);
            add(identity, phase + "_queue_sum", queueTotal);
            add(identity, phase + "_queue_max", queueMax);
            add(identity, phase + "_fx_work_sum", workTotal);
            add(identity, phase + "_fx_work_max", workMax);
        }
    }

    private synchronized Event add(Token identity, String stage, long duration) {
        return addAt(identity, stage, clock.getAsLong(), duration);
    }

    private synchronized Event addAt(Token identity, String stage, long nanos, long duration) {
        if (!enabled || identity.epoch != epoch) return null;
        if (count.incrementAndGet() > MAX_EVENTS) return null;
        Event event = new Event(identity.epoch, identity.input, identity.render, stage, nanos, duration);
        events.add(event);
        return event;
    }
}
