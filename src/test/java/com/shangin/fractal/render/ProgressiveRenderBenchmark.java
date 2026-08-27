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

    private static final double FOCUS_X = 0.70;
    private static final double FOCUS_Y = 0.40;

    public static void main(String[] args) throws InterruptedException {

        FractalPreset preset = FractalPreset.MANDELBROT;

        FractalCalculator calculator = new FractalCalculator(preset.createFormula());

        RenderRequest request = new RenderRequest(
                calculator,
                preset.defaultViewport(),
                WIDTH,
                HEIGHT,
                MAX_ITERATIONS,
                new RenderPriority(
                    FOCUS_X,
                    FOCUS_Y));

        double focusX =
                request.width() * FOCUS_X;

        double focusY =
                request.height() * FOCUS_Y;

        try (FractalRenderService renderService = new FractalRenderService()) {

            for (int i = 0; i < WARMUP_RUNS; i++) {
                runOnce(renderService, request);
            }

            List<Result> results = new ArrayList<>();

            for (int i = 0; i < MEASURED_RUNS; i++) {
                Result result = runOnce(renderService, request);

                results.add(result);

                System.out.printf(
                        "Run %2d: first=%7.2f ms, focus25=%7.2f ms, focus50=%7.2f ms, total=%7.2f ms, batches=%d%n",
                        i + 1,
                        result.firstBatchMs(),
                        result.focus25Ms(),
                        result.focus50Ms(),
                        result.totalMs(),
                        result.batchCount()
                );
            }

            printSummary(results);
        }
    }


    private record FocusRegion(
            int xFrom,
            int yFrom,
            int xTo,
            int yTo
    ) {

        static FocusRegion around(
                int width,
                int height,
                double focusX,
                double focusY,
                double areaFraction
        ) {
            double sideScale =
                    Math.sqrt(areaFraction);

            int regionWidth =
                    Math.max(
                            1,
                            (int) Math.round(
                                    width * sideScale
                            )
                    );

            int regionHeight =
                    Math.max(
                            1,
                            (int) Math.round(
                                    height * sideScale
                            )
                    );

            int xFrom =
                    (int) Math.round(
                            focusX - regionWidth / 2.0
                    );

            int yFrom =
                    (int) Math.round(
                            focusY - regionHeight / 2.0
                    );

            // Сдвигаем область внутрь viewport,
            // сохраняя её полный размер.
            xFrom =
                    Math.clamp(
                            xFrom,
                            0,
                            width - regionWidth
                    );

            yFrom =
                    Math.clamp(
                            yFrom,
                            0,
                            height - regionHeight
                    );

            return new FocusRegion(
                    xFrom,
                    yFrom,
                    xFrom + regionWidth,
                    yFrom + regionHeight
            );
        }

        long pixelCount() {
            return (long) (xTo - xFrom)
                    * (yTo - yFrom);
        }

        long intersectionPixels(
                RenderRegion region
        ) {
            int intersectionXFrom =
                    Math.max(
                            xFrom,
                            region.x()
                    );

            int intersectionYFrom =
                    Math.max(
                            yFrom,
                            region.y()
                    );

            int intersectionXTo =
                    Math.min(
                            xTo,
                            region.x()
                                    + region.width()
                    );

            int intersectionYTo =
                    Math.min(
                            yTo,
                            region.y()
                                    + region.height()
                    );

            if (intersectionXFrom >= intersectionXTo
                    || intersectionYFrom >= intersectionYTo) {
                return 0;
            }

            return (long)
                    (intersectionXTo - intersectionXFrom)
                    * (intersectionYTo - intersectionYFrom);
        }
    }

    private static Result runOnce(
            FractalRenderService renderService,
            RenderRequest request
    ) throws InterruptedException {

        double focusX =
                request.width() * FOCUS_X;

        double focusY =
                request.height() * FOCUS_Y;

        FocusRegion focus25 =
                FocusRegion.around(
                        request.width(),
                        request.height(),
                        focusX,
                        focusY,
                        0.25
                );

        FocusRegion focus50 =
                FocusRegion.around(
                        request.width(),
                        request.height(),
                        focusX,
                        focusY,
                        0.50
                );

        AtomicLong focus25Rendered =
                new AtomicLong();

        AtomicLong focus50Rendered =
                new AtomicLong();

        AtomicLong focus25ReadyNs =
                new AtomicLong();

        AtomicLong focus50ReadyNs =
                new AtomicLong();

        AtomicLong firstBatchNs =
                new AtomicLong();

        AtomicLong totalNs =
                new AtomicLong();

        AtomicInteger batchCount =
                new AtomicInteger();

        AtomicReference<Throwable> error =
                new AtomicReference<>();

        CountDownLatch completed =
                new CountDownLatch(1);

        long start =
                System.nanoTime();

        RenderFrame renderFrame = RenderFrame.create(request);
        renderService.render(
                renderFrame,
                DIRECT_EXECUTOR,

                progress -> {
                    long now =
                            System.nanoTime();

                    firstBatchNs.compareAndSet(
                            0,
                            now
                    );

                    batchCount.incrementAndGet();

                    long batchFocus25Pixels = 0;
                    long batchFocus50Pixels = 0;

                    for (RenderRegion region
                            : progress.regions()) {

                        batchFocus25Pixels +=
                                focus25.intersectionPixels(
                                        region
                                );

                        batchFocus50Pixels +=
                                focus50.intersectionPixels(
                                        region
                                );
                    }

                    long ready25 =
                            focus25Rendered.addAndGet(
                                    batchFocus25Pixels
                            );

                    long ready50 =
                            focus50Rendered.addAndGet(
                                    batchFocus50Pixels
                            );

                    if (ready25 >= focus25.pixelCount()) {
                        focus25ReadyNs.compareAndSet(
                                0,
                                now
                        );
                    }

                    if (ready50 >= focus50.pixelCount()) {
                        focus50ReadyNs.compareAndSet(
                                0,
                                now
                        );
                    }
                },

                data -> {
                    totalNs.set(
                            System.nanoTime()
                    );

                    completed.countDown();
                },

                throwable -> {
                    error.set(throwable);
                    completed.countDown();
                }
        );

        if (!completed.await(
                10,
                TimeUnit.SECONDS
        )) {
            throw new IllegalStateException(
                    "Render timed out"
            );
        }

        if (error.get() != null) {
            throw new IllegalStateException(
                    "Render failed",
                    error.get()
            );
        }

        if (firstBatchNs.get() == 0
                || focus25ReadyNs.get() == 0
                || focus50ReadyNs.get() == 0
                || totalNs.get() == 0) {
            throw new IllegalStateException(
                    "Incomplete benchmark measurements"
            );
        }

        return new Result(
                toMillis(
                        firstBatchNs.get() - start
                ),
                toMillis(
                        focus25ReadyNs.get() - start
                ),
                toMillis(
                        focus50ReadyNs.get() - start
                ),
                toMillis(
                        totalNs.get() - start
                ),
                batchCount.get()
        );
    }

    private static void printSummary(List<Result> results) {

        double[] first =
                results.stream()
                        .mapToDouble(Result::firstBatchMs)
                        .toArray();

        double[] focus25 =
                results.stream()
                        .mapToDouble(Result::focus25Ms)
                        .toArray();

        double[] focus50 =
                results.stream()
                        .mapToDouble(Result::focus50Ms)
                        .toArray();

        double[] total =
                results.stream()
                        .mapToDouble(Result::totalMs)
                        .toArray();

        System.out.println();
        System.out.println("Median:");

        System.out.printf(
                "First batch: %7.2f ms%n",
                median(first)
        );

        System.out.printf(
                "Focus 25%%:   %7.2f ms%n",
                median(focus25)
        );

        System.out.printf(
                "Focus 50%%:   %7.2f ms%n",
                median(focus50)
        );

        System.out.printf(
                "Total:       %7.2f ms%n",
                median(total)
        );
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

    private record Result(
            double firstBatchMs,
            double focus25Ms,
            double focus50Ms,
            double totalMs,
            int batchCount
    ) {}
}