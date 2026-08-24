package com.shangin.fractal.render;

import com.shangin.fractal.formula.FractalPreset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ProgressiveRenderBenchmark {

    private static final int WIDTH = 2000;
    private static final int HEIGHT = 1408;
    private static final int MAX_ITERATIONS = 2000;

    private static final int WARMUP_RUNS = 5;
    private static final int MEASURED_RUNS = 10;

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    public static void main(String[] args) throws InterruptedException {

        FractalPreset preset = FractalPreset.MANDELBROT;

        FractalCalculator calculator = new FractalCalculator(preset.createFormula());

        RenderRequest request = new RenderRequest(calculator, preset.defaultViewport(), WIDTH, HEIGHT, MAX_ITERATIONS);

        try (FractalRenderService renderService = new FractalRenderService()) {

            for (int i = 0; i < WARMUP_RUNS; i++) {
                runOnce(renderService, request);
            }

            List<Result> results = new ArrayList<>();

            for (int i = 0; i < MEASURED_RUNS; i++) {
                Result result = runOnce(renderService, request);

                results.add(result);

                System.out.printf("Run %2d: first=%7.2f ms, 50%%=%7.2f ms, 90%%=%7.2f ms, total=%7.2f ms, batches=%d%n",
                        i + 1, result.firstBatchMs(), result.fiftyPercentMs(), result.ninetyPercentMs(), result.totalMs(), result.batchCount());
            }

            printSummary(results);
        }
    }

    private static Result runOnce(FractalRenderService renderService, RenderRequest request) throws InterruptedException {

        long totalPixels = (long) request.width() * request.height();

        long fiftyPercent = totalPixels / 2;

        long ninetyPercent = (long) Math.ceil(totalPixels * 0.9);

        AtomicLong renderedPixels = new AtomicLong();

        AtomicLong firstBatchNs = new AtomicLong();

        AtomicLong fiftyPercentNs = new AtomicLong();

        AtomicLong ninetyPercentNs = new AtomicLong();

        AtomicLong totalNs = new AtomicLong();

        AtomicInteger batchCount = new AtomicInteger();

        AtomicReference<Throwable> error = new AtomicReference<>();

        CountDownLatch completed = new CountDownLatch(1);

        long start = System.nanoTime();

        renderService.render(request, DIRECT_EXECUTOR,

                progress -> {
                    long now = System.nanoTime();

                    firstBatchNs.compareAndSet(0, now);

                    batchCount.incrementAndGet();

                    long batchPixels = progress.regions().stream().mapToLong(region -> (long) region.width() * region.height()).sum();

                    long pixels = renderedPixels.addAndGet(batchPixels);

                    double percent =
                            pixels * 100.0 / totalPixels;

                    System.out.printf(
                            "Batch %2d: %6.2f%% at %7.2f ms%n",
                            batchCount.get(),
                            percent,
                            toMillis(System.nanoTime() - start)
                    );

                    if (pixels >= fiftyPercent) {
                        fiftyPercentNs.compareAndSet(0, now);
                    }

                    if (pixels >= ninetyPercent) {
                        ninetyPercentNs.compareAndSet(0, now);
                    }
                },

                data -> {
                    totalNs.set(System.nanoTime());

                    completed.countDown();
                },

                throwable -> {
                    error.set(throwable);
                    completed.countDown();
                });

        if (!completed.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Render timed out");
        }

        if (error.get() != null) {
            throw new IllegalStateException("Render failed", error.get());
        }

        if (firstBatchNs.get() == 0 || fiftyPercentNs.get() == 0 || ninetyPercentNs.get() == 0 || totalNs.get() == 0) {
            throw new IllegalStateException("Incomplete benchmark measurements");
        }

        return new Result(toMillis(firstBatchNs.get() - start), toMillis(fiftyPercentNs.get() - start), toMillis(ninetyPercentNs.get() - start), toMillis(totalNs.get() - start), batchCount.get());
    }

    private static void printSummary(List<Result> results) {
        double[] first = results.stream().mapToDouble(Result::firstBatchMs).toArray();

        double[] fifty = results.stream().mapToDouble(Result::fiftyPercentMs).toArray();

        double[] ninety = results.stream().mapToDouble(Result::ninetyPercentMs).toArray();

        double[] total = results.stream().mapToDouble(Result::totalMs).toArray();

        System.out.println();
        System.out.println("Median:");

        System.out.printf("First batch: %7.2f ms%n", median(first));

        System.out.printf("50%% pixels:  %7.2f ms%n", median(fifty));

        System.out.printf("90%% pixels:  %7.2f ms%n", median(ninety));

        System.out.printf("Total:       %7.2f ms%n", median(total));

        double averageBatches = results.stream().mapToInt(Result::batchCount).average().orElseThrow();

        System.out.printf("Avg batches: %.1f%n", averageBatches);
    }

    private static double median(double[] values) {
        Arrays.sort(values);

        int middle = values.length / 2;

        if (values.length % 2 == 0) {
            return (values[middle - 1] + values[middle]) / 2.0;
        }

        return values[middle];
    }

    private static double toMillis(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
    }

    private record Result(double firstBatchMs, double fiftyPercentMs, double ninetyPercentMs, double totalMs,
                          int batchCount) {
    }
}