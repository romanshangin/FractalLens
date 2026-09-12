package com.shangin.fractal.export;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.formula.DistanceSample;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.FractalColorizer;
import com.shangin.fractal.render.SamplePlane;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderGrid;
import com.shangin.fractal.scene.SamplingPattern;

import com.shangin.fractal.render.PreciseFractalSampler;
import com.shangin.fractal.render.PreciseRenderGrid;
import java.math.BigDecimal;
import java.util.function.BooleanSupplier;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Exports a frame with adaptive 2x2, 4x4, or 8x8 subpixel sampling. */
public final class AdaptivePngExportService implements AutoCloseable {

    static final int MIN_SAMPLE_GRID = 2;
    static final int MEDIUM_SAMPLE_GRID = 4;
    static final int MAX_SAMPLE_GRID = 8;

    private static final int ROWS_PER_TASK = 8;
    private static final double BASE_EDGE_THRESHOLD = 0.10;
    private static final double TWO_BY_TWO_THRESHOLD = 0.10;
    private static final double FOUR_BY_FOUR_THRESHOLD = 0.055;
    private static final double[] SRGB_TO_LINEAR = createLinearLookup();

    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(
            daemonThreadFactory("fractal-export")
    );
    private final ExecutorService workers = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
            daemonThreadFactory("fractal-export-worker")
    );
    private static final FractalColorizer COLORIZER = new FractalColorizer();
    private final AtomicLong generation = new AtomicLong();

    private Future<?> currentExport;

    public synchronized void export(
            RenderFrame sourceFrame,
            ColoringStrategy coloring,
            Path target,
            Consumer<Path> onSuccess,
            Consumer<Throwable> onError
    ) {
        Objects.requireNonNull(sourceFrame);
        Objects.requireNonNull(coloring);
        Objects.requireNonNull(target);
        Objects.requireNonNull(onSuccess);
        Objects.requireNonNull(onError);

        long exportId = generation.incrementAndGet();
        cancelCurrentFuture();

        currentExport = coordinator.submit(() -> runExport(
                exportId,
                sourceFrame,
                coloring,
                target,
                onSuccess,
                onError
        ));
    }

    private void runExport(
            long exportId,
            RenderFrame sourceFrame,
            ColoringStrategy coloring,
            Path target,
            Consumer<Path> onSuccess,
            Consumer<Throwable> onError
    ) {
        try {
            SamplePlane data = sourceFrame.samplePlane();

            if (!sourceFrame.isComplete()) {
                throw new IllegalArgumentException("Adaptive export requires a completed frame");
            }

            int[] baseColors = colorBaseFrame(data, coloring);
            int[] output = new int[data.size()];
            List<Future<?>> tasks = createTasks(
                    exportId,
                    sourceFrame,
                    coloring,
                    baseColors,
                    output
            );

            for (Future<?> task : tasks) {
                task.get();
            }

            if (shouldCancel(exportId)) {
                return;
            }

            PngExporter.write(target, data.width(), data.height(), output);

            if (isCurrent(exportId)) {
                onSuccess.accept(target);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (CancellationException exception) {
            // A newer export or application shutdown superseded this operation.
        } catch (ExecutionException exception) {
            reportError(exportId, exception.getCause(), onError);
        } catch (IOException | RuntimeException exception) {
            reportError(exportId, exception, onError);
        }
    }

    static int[] colorBaseFrame(SamplePlane data, ColoringStrategy coloring) {
        int[] colors = new int[data.size()];
        COLORIZER.color(data, IntBuffer.wrap(colors), coloring);
        return colors;
    }

    private List<Future<?>> createTasks(
            long exportId,
            RenderFrame frame,
            ColoringStrategy coloring,
            int[] baseColors,
            int[] output
    ) {
        int height = frame.samplePlane().height();
        List<Future<?>> tasks = new ArrayList<>((height + ROWS_PER_TASK - 1) / ROWS_PER_TASK);

        boolean deep = frame.job().formula().preset() != null
                && frame.job().formula().preset().supportsDeepZoom()
                && !frame.job().formula().preset().hasSufficientDirectPrecision(
                frame.job().viewport(), frame.job().width(), frame.job().height());
        PreciseFractalSampler sampler = deep ? PreciseFractalSampler.create(
                frame.job(), () -> shouldCancel(exportId)).orElseThrow(CancellationException::new) : null;
        for (int yFrom = 0; yFrom < height; yFrom += ROWS_PER_TASK) {
            int firstRow = yFrom;
            int lastRow = Math.min(yFrom + ROWS_PER_TASK, height);

            tasks.add(workers.submit(() -> renderRows(
                    exportId,
                    frame,
                    coloring,
                    baseColors,
                    output,
                    firstRow,
                    lastRow,
                    sampler
            )));
        }

        return tasks;
    }

    private void renderRows(
            long exportId,
            RenderFrame frame,
            ColoringStrategy coloring,
            int[] baseColors,
            int[] output,
            int yFrom,
            int yTo,
            PreciseFractalSampler sampler
    ) {
        SamplePlane data = frame.samplePlane();
        RenderGrid grid = frame.renderGrid();
        FractalCalculator calculator = frame.job().formula().createDirectCalculator();

        for (int y = yFrom; y < yTo; y++) {
            if (shouldCancel(exportId)) {
                throw new CancellationException();
            }

            for (int x = 0; x < data.width(); x++) {
                int index = y * data.width() + x;
                int color = sampler != null ? samplePrecisePixel(sampler, coloring,
                        grid.preciseGrid() != null ? grid.preciseGrid() : frame.job().preciseGrid(),
                        frame.job().maxIterations(), data, baseColors, x, y,
                        () -> shouldCancel(exportId)) : samplePixel(
                        calculator,
                        coloring,
                        grid,
                        frame.request().maxIterations(),
                        data,
                        baseColors,
                        x,
                        y
                );

                output[index] = data.escaped(index)
                        ? dither(color, x, y)
                        : color;
            }
        }
    }

    static int samplePrecisePixel(PreciseFractalSampler sampler, ColoringStrategy coloring,
                                  PreciseRenderGrid grid, int maxIterations,
                                  SamplePlane data, int[] baseColors, int x, int y,
                                  BooleanSupplier cancelled) {
        int firstSize = isBaseEdge(data, baseColors, x, y) ? MAX_SAMPLE_GRID : MIN_SAMPLE_GRID;
        for (int size = firstSize; ; size *= 2) {
            ColorAccumulator accumulator = new ColorAccumulator();
            for (int sy = 0; sy < size; sy++) for (int sx = 0; sx < size; sx++) {
                BigDecimal ox = BigDecimal.valueOf((sx + 0.5) / size - 0.5);
                BigDecimal oy = BigDecimal.valueOf((sy + 0.5) / size - 0.5);
                FractalSample sample = sampler.sample(
                        grid.realAt(x).add(grid.realStep().multiply(ox, grid.mathContext()), grid.mathContext()),
                        grid.imaginaryAt(y).subtract(grid.imaginaryStep().multiply(oy, grid.mathContext()), grid.mathContext()),
                        cancelled);
                if (sample == null) throw new CancellationException();
                accumulator.add(coloring.color(sample.iterations(), sample.smoothIterations(),
                        sample.escaped(), maxIterations, sample.orbitTrapDistance()));
            }
            SampleResult result = accumulator.result();
            double threshold = size == MIN_SAMPLE_GRID ? TWO_BY_TWO_THRESHOLD : FOUR_BY_FOUR_THRESHOLD;
            if (size == MAX_SAMPLE_GRID || result.contrast() <= threshold) return result.color();
        }
    }

    static int dither(int color, int x, int y) {
        int seed = x * 0x1f123bb5 ^ y * 0x5f356495;
        double triangularNoise = unitNoise(mix(seed)) - unitNoise(mix(seed ^ 0x68bc21eb));
        double adjustment = triangularNoise * 0.85;

        int alpha = (color >>> 24) & 0xFF;
        int red = ditherChannel((color >>> 16) & 0xFF, adjustment);
        int green = ditherChannel((color >>> 8) & 0xFF, adjustment);
        int blue = ditherChannel(color & 0xFF, adjustment);

        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    private static double unitNoise(int value) {
        return (value & 0x00FF_FFFF) / 16_777_216.0;
    }

    private static int ditherChannel(int channel, double adjustment) {
        return Math.clamp((int) Math.round(channel + adjustment), 0, 255);
    }

    private static int samplePixel(
            FractalCalculator calculator,
            ColoringStrategy coloring,
            RenderGrid grid,
            int maxIterations,
            SamplePlane baseData,
            int[] baseColors,
            int x,
            int y
    ) {
        if (isSupersamplingCandidate(
                calculator,
                grid,
                maxIterations,
                baseData,
                baseColors,
                x,
                y
        )) {
            return sampleGrid(
                    calculator,
                    coloring,
                    grid,
                    maxIterations,
                    x,
                    y,
                    MAX_SAMPLE_GRID
            ).color();
        }

        SampleResult result = sampleGrid(
                calculator,
                coloring,
                grid,
                maxIterations,
                x,
                y,
                MIN_SAMPLE_GRID
        );

        if (result.contrast() <= TWO_BY_TWO_THRESHOLD) {
            return result.color();
        }

        result = sampleGrid(
                calculator,
                coloring,
                grid,
                maxIterations,
                x,
                y,
                MEDIUM_SAMPLE_GRID
        );

        if (result.contrast() <= FOUR_BY_FOUR_THRESHOLD) {
            return result.color();
        }

        return sampleGrid(
                calculator,
                coloring,
                grid,
                maxIterations,
                x,
                y,
                MAX_SAMPLE_GRID
        ).color();
    }

    static boolean isBaseEdge(
            SamplePlane data,
            int[] colors,
            int x,
            int y
    ) {
        int width = data.width();
        int centerIndex = y * width + x;

        for (int neighborY = Math.max(0, y - 1);
             neighborY <= Math.min(data.height() - 1, y + 1);
             neighborY++) {
            for (int neighborX = Math.max(0, x - 1);
                 neighborX <= Math.min(width - 1, x + 1);
                 neighborX++) {
                int neighborIndex = neighborY * width + neighborX;

                if (data.escaped(centerIndex) != data.escaped(neighborIndex)
                        || colorContrast(colors[centerIndex], colors[neighborIndex]) > BASE_EDGE_THRESHOLD) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Combines the inexpensive image-space detector with an analytic exterior
     * distance estimate when the selected formula provides one.
     */
    static boolean isSupersamplingCandidate(
            FractalCalculator calculator,
            RenderGrid grid,
            int maxIterations,
            SamplePlane data,
            int[] colors,
            int x,
            int y
    ) {
        if (isBaseEdge(data, colors, x, y)) {
            return true;
        }

        if (!calculator.supportsDistanceEstimation()) {
            return false;
        }

        DistanceSample sample = calculator.calculateDistanceSample(
                grid.realAt(x),
                grid.imaginaryAt(y),
                maxIterations
        );
        double pixelRadius = 0.5 * Math.hypot(grid.realStep(), grid.imaginaryStep());

        return sample.hasDistance() && sample.distance() <= pixelRadius;
    }

    static int sampleGridColor(
            FractalCalculator calculator,
            ColoringStrategy coloring,
            RenderGrid grid,
            int maxIterations,
            int pixelX,
            int pixelY,
            int sampleGridSize,
            SamplingPattern samplingPattern
    ) {
        return sampleGrid(
                calculator,
                coloring,
                grid,
                maxIterations,
                pixelX,
                pixelY,
                sampleGridSize,
                samplingPattern
        ).color();
    }

    static int colorSamples(
            FractalSample[] samples,
            ColoringStrategy coloring,
            int maxIterations
    ) {
        ColorAccumulator accumulator = new ColorAccumulator();
        for (FractalSample sample : samples) {
            accumulator.add(coloring.color(
                    sample.iterations(),
                    sample.smoothIterations(),
                    sample.escaped(),
                    maxIterations,
                    sample.orbitTrapDistance()));
        }
        return accumulator.result().color();
    }

    private static SampleResult sampleGrid(
            FractalCalculator calculator,
            ColoringStrategy coloring,
            RenderGrid grid,
            int maxIterations,
            int pixelX,
            int pixelY,
            int sampleGridSize
    ) {
        return sampleGrid(
                calculator,
                coloring,
                grid,
                maxIterations,
                pixelX,
                pixelY,
                sampleGridSize,
                SamplingPattern.REGULAR
        );
    }

    private static SampleResult sampleGrid(
            FractalCalculator calculator,
            ColoringStrategy coloring,
            RenderGrid grid,
            int maxIterations,
            int pixelX,
            int pixelY,
            int sampleGridSize,
            SamplingPattern samplingPattern
    ) {
        double centerReal = grid.realAt(pixelX);
        double centerImaginary = grid.imaginaryAt(pixelY);
        ColorAccumulator accumulator = new ColorAccumulator();

        for (int sampleY = 0; sampleY < sampleGridSize; sampleY++) {
            for (int sampleX = 0; sampleX < sampleGridSize; sampleX++) {
                double offsetX = ((sampleX + sampleOffset(
                        samplingPattern, pixelX, pixelY, sampleX, sampleY, 0
                )) / sampleGridSize) - 0.5;
                double offsetY = ((sampleY + sampleOffset(
                        samplingPattern, pixelX, pixelY, sampleX, sampleY, 1
                )) / sampleGridSize) - 0.5;
                double real = centerReal + offsetX * grid.realStep();
                double imaginary = centerImaginary - offsetY * grid.imaginaryStep();
                FractalSample sample = calculator.calculateSample(real, imaginary, maxIterations);
                int color = coloring.color(
                        sample.iterations(),
                        sample.smoothIterations(),
                        sample.escaped(),
                        maxIterations,
                        sample.orbitTrapDistance()
                );

                accumulator.add(color);
            }
        }

        return accumulator.result();
    }

    private static double sampleOffset(
            SamplingPattern pattern,
            int pixelX,
            int pixelY,
            int sampleX,
            int sampleY,
            int axis
    ) {
        if (pattern == SamplingPattern.REGULAR) {
            return 0.5;
        }

        int seed = pixelX * 0x1f123bb5
                ^ pixelY * 0x5f356495
                ^ sampleX * 0x68bc21eb
                ^ sampleY * 0x02e5be93
                ^ axis * 0x7f4a7c15;
        return unitNoise(mix(seed));
    }

    static double colorContrast(int first, int second) {
        return Math.max(
                Math.max(
                        Math.abs(linearChannel(first, 16) - linearChannel(second, 16)),
                        Math.abs(linearChannel(first, 8) - linearChannel(second, 8))
                ),
                Math.abs(linearChannel(first, 0) - linearChannel(second, 0))
        );
    }

    private static double linearChannel(int color, int shift) {
        return SRGB_TO_LINEAR[(color >>> shift) & 0xFF];
    }

    private static double[] createLinearLookup() {
        double[] lookup = new double[256];

        for (int channel = 0; channel < lookup.length; channel++) {
            double srgb = channel / 255.0;
            lookup[channel] = srgb <= 0.04045
                    ? srgb / 12.92
                    : Math.pow((srgb + 0.055) / 1.055, 2.4);
        }

        return lookup;
    }

    private static int fromLinear(double linear) {
        double srgb = linear <= 0.0031308
                ? linear * 12.92
                : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;

        return Math.clamp((int) Math.round(srgb * 255.0), 0, 255);
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();

        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private synchronized void cancelCurrentFuture() {
        Future<?> export = currentExport;
        currentExport = null;

        if (export != null && !export.isDone()) {
            export.cancel(true);
        }
    }

    private boolean isCurrent(long exportId) {
        return exportId == generation.get();
    }

    private boolean shouldCancel(long exportId) {
        return !isCurrent(exportId) || Thread.currentThread().isInterrupted();
    }

    private void reportError(
            long exportId,
            Throwable exception,
            Consumer<Throwable> onError
    ) {
        if (isCurrent(exportId) && !(exception instanceof CancellationException)) {
            onError.accept(exception);
        }
    }

    @Override
    public synchronized void close() {
        generation.incrementAndGet();
        cancelCurrentFuture();
        coordinator.shutdownNow();
        workers.shutdownNow();
    }

    private record SampleResult(int color, double contrast) {}

    private static final class ColorAccumulator {
        private int count;
        private int alphaSum;
        private double redSum;
        private double greenSum;
        private double blueSum;
        private double minRed = Double.POSITIVE_INFINITY;
        private double minGreen = Double.POSITIVE_INFINITY;
        private double minBlue = Double.POSITIVE_INFINITY;
        private double maxRed = Double.NEGATIVE_INFINITY;
        private double maxGreen = Double.NEGATIVE_INFINITY;
        private double maxBlue = Double.NEGATIVE_INFINITY;

        void add(int color) {
            double red = linearChannel(color, 16);
            double green = linearChannel(color, 8);
            double blue = linearChannel(color, 0);

            count++;
            alphaSum += (color >>> 24) & 0xFF;
            redSum += red;
            greenSum += green;
            blueSum += blue;
            minRed = Math.min(minRed, red);
            minGreen = Math.min(minGreen, green);
            minBlue = Math.min(minBlue, blue);
            maxRed = Math.max(maxRed, red);
            maxGreen = Math.max(maxGreen, green);
            maxBlue = Math.max(maxBlue, blue);
        }

        SampleResult result() {
            int alpha = (alphaSum + count / 2) / count;
            int red = fromLinear(redSum / count);
            int green = fromLinear(greenSum / count);
            int blue = fromLinear(blueSum / count);
            double contrast = Math.max(
                    Math.max(maxRed - minRed, maxGreen - minGreen),
                    maxBlue - minBlue
            );

            return new SampleResult(
                    alpha << 24 | red << 16 | green << 8 | blue,
                    contrast
            );
        }
    }
}
