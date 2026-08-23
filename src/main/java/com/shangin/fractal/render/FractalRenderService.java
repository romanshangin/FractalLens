package com.shangin.fractal.render;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class FractalRenderService implements AutoCloseable {

    private final ExecutorService coordinator;
    private final ParallelFractalCalculator parallelCalculator;
    private final AtomicLong generation = new AtomicLong();
    private Future<?> currentRender;

    public FractalRenderService() {
        coordinator = Executors.newSingleThreadExecutor(daemonThreadFactory("fractal-render"));
        parallelCalculator = new ParallelFractalCalculator();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();

        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());

            thread.setDaemon(true);

            return thread;
        };
    }

    public synchronized void render(
            RenderRequest request,
            Executor callbackExecutor,
            Consumer<FractalData> onSuccess,
            Consumer<Throwable> onError
    ) {
        long renderId = generation.incrementAndGet();

        cancelCurrentFuture();
        currentRender = coordinator.submit(() ->
                executeRender(
                        renderId,
                        request,
                        callbackExecutor,
                        onSuccess,
                        onError));
    }

    public synchronized void cancelCurrent() {
        generation.incrementAndGet();

        cancelCurrentFuture();
    }

    private void executeRender(
            long renderId,
            RenderRequest request,
            Executor callbackExecutor,
            Consumer<FractalData> onSuccess,
            Consumer<Throwable> onError
    ) {
        try {
            FractalData data =
                    parallelCalculator.calculate(
                            request,
                            () -> shouldCancel(renderId)
                    );

            if (data == null || isCancelled(renderId)) {
                return;
            }

            callbackExecutor.execute(() -> {
                if (isCurrent(renderId)) {
                    onSuccess.accept(data);
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } catch (RuntimeException e) {
            if (isCancelled(renderId)) {
                return;
            }
            callbackExecutor.execute(
                    () -> onError.accept(e)
            );
        }
    }

    private boolean isCancelled(long renderId) {
        return renderId != generation.get() || Thread.currentThread().isInterrupted();
    }

    private void cancelCurrentFuture() {
        Future<?> render = currentRender;
        currentRender = null;

        if (render != null && !render.isDone()) {
            render.cancel(true);
        }
    }

    private boolean isCurrent(long renderId) {
        return renderId == generation.get();
    }

    private boolean shouldCancel(long renderId) {
        return !isCurrent(renderId) || Thread.currentThread().isInterrupted();
    }

    @Override
    public synchronized void close() {
        generation.incrementAndGet();

        cancelCurrentFuture();

        coordinator.shutdownNow();
        parallelCalculator.close();
    }
}