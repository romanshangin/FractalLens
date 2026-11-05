package com.shangin.fractal.render;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.scene.ColoringSettings;
import com.shangin.fractal.scene.WidePreviewSettings;

import java.nio.IntBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Idle-only, single-worker reserve. Cancellation is cooperative: a successor
 * never reads/reuses a frame until its backend and all registered workers return.
 */
public final class WidePreviewService implements AutoCloseable {
    private record Work(long generation, RenderJob visible, ColoringSettings coloring,
                        WidePreviewSettings settings, Consumer<WidePreviewSnapshot> publish,
                        Consumer<Throwable> failure) {}
    private record Publication(Work work, WidePreviewSnapshot snapshot) {}

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "fractal-wide-preview");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final Executor callbacks;
    private final Supplier<RenderBackend> backendFactory;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Publication> publication = new AtomicReference<>();
    private final AtomicBoolean publicationQueued = new AtomicBoolean();
    private Work pending;
    private boolean scheduled;
    private boolean closed;
    // Accessed only by the coordinator, after the preceding backend drained.
    private RenderFrame reserve;
    private WidePreviewSettings reserveSettings;
    private int reserveIterations;

    public WidePreviewService(Executor callbacks) {
        this(callbacks, () -> new PrecisionSelectingRenderBackend(
                new DirectDoubleRenderBackend(new ParallelFractalCalculator(WidePreviewSettings.WORKERS)),
                new PrecisionSelectingRenderBackend(
                        new MandelbrotPerturbationRenderBackend(WidePreviewSettings.WORKERS, ignored -> {}, WidePreviewSettings.REFERENCE_CACHE_BYTES),
                        new JuliaDeepZoomRenderBackend(WidePreviewSettings.WORKERS))));
    }

    WidePreviewService(Executor callbacks, Supplier<RenderBackend> backendFactory) {
        this.callbacks = Objects.requireNonNull(callbacks);
        this.backendFactory = Objects.requireNonNull(backendFactory);
    }

    /** Caller submits only after visible rendering and AA have completed. Latest work wins. */
    public synchronized void request(RenderJob visible, ColoringSettings coloring,
                                     WidePreviewSettings settings,
                                     Consumer<WidePreviewSnapshot> publish, Consumer<Throwable> failure) {
        Objects.requireNonNull(visible);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(publish);
        Objects.requireNonNull(failure);
        if (closed) return;
        if (!settings.enabled()) {
            cancel();
            return;
        }
        long id = generation.incrementAndGet();
        publication.set(null);
        pending = new Work(id, visible, coloring, settings, publish, failure);
        if (!scheduled) {
            scheduled = true;
            coordinator.execute(this::runPending);
        }
    }

    /** Stops calculation/publication immediately; ready samples stay private for compatible reuse. */
    public synchronized void cancel() {
        generation.incrementAndGet();
        pending = null;
        publication.set(null);
    }

    private void runPending() {
        while (true) {
            Work work;
            synchronized (this) {
                work = pending;
                pending = null;
                if (work == null) {
                    scheduled = false;
                    if (closed) reserve = null;
                    return;
                }
            }
            calculate(work);
        }
    }

    private void calculate(Work work) {
        if (obsolete(work)) return;
        // Extreme decimal contexts can make a tiny reserve more expensive than the visible view.
        if (work.visible.viewport().mathContext().getPrecision() > WidePreviewSettings.MAX_WORKING_PRECISION) {
            reserve = null;
            return;
        }
        if (!work.settings.equals(reserveSettings) || reserveIterations != work.visible.maxIterations())
            reserve = null;
        WidePreviewPlan plan = WidePreviewPlan.create(work.visible, work.settings, reserve);
        RenderFrame frame = reserve != null && RenderFrameCache.sameCalculatedFrame(reserve.request(), plan.job())
                ? reserve : new FrameReusePlanner().createFrame(reserve, plan.job());
        reserve = frame;
        reserveSettings = work.settings;
        reserveIterations = work.visible.maxIterations();
        try {
            // Complete reserves only need new colors, never another reference orbit or backend.
            if (frame.isComplete()) {
                publish(work, plan, frame);
                return;
            }
            long deadline = System.nanoTime() + work.settings.workMillis() * 1_000_000L;
            AtomicLong lastPublication = new AtomicLong();
            try (RenderBackend backend = backendFactory.get()) {
                if (!backend.supports(plan.job())) return;
                publish(work, plan, frame);
                backend.render(frame, () -> obsolete(work) || System.nanoTime() >= deadline,
                        region -> {
                            long now = System.nanoTime();
                            if (now - lastPublication.get() >= WidePreviewSettings.PUBLICATION_INTERVAL_NANOS) {
                                lastPublication.set(now);
                                publish(work, plan, frame);
                            }
                        }, null);
                publish(work, plan, frame);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            callbacks.execute(() -> { if (!obsolete(work)) work.failure.accept(error); });
        }
    }

    private boolean obsolete(Work work) { return work.generation != generation.get(); }

    private void publish(Work work, WidePreviewPlan plan, RenderFrame frame) {
        if (obsolete(work) || frame.validity().readyPixelCount() == 0) return;
        int[] colors = new int[plan.job().width() * plan.job().height()];
        // Histogram mapping remains the smooth progressive mapping in a partial reserve.
        ColoringStrategy coloring = work.coloring.createStrategy();
        new FractalColorizer().colorReadyPixels(frame.samplePlane(), IntBuffer.wrap(colors),
                coloring, frame.validity(), null);
        if (obsolete(work)) return;
        publication.set(new Publication(work,
                new WidePreviewSnapshot(plan, colors, frame.validity().readyPixelCount())));
        queuePublication();
    }

    private void queuePublication() {
        if (!publicationQueued.compareAndSet(false, true)) return;
        callbacks.execute(() -> {
            Publication ready = publication.getAndSet(null);
            try {
                if (ready != null && !obsolete(ready.work)) ready.work.publish.accept(ready.snapshot);
            } finally {
                publicationQueued.set(false);
                if (publication.get() != null) queuePublication();
            }
        });
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        cancel();
        // Do not cancel a coordinator Future: invokeAll must first drain its workers.
        coordinator.shutdown();
        if (!scheduled) reserve = null;
    }
}
