package com.shangin.fractal.export;

import com.shangin.fractal.coloring.ColoringStrategy;
import com.shangin.fractal.formula.DistanceSample;
import com.shangin.fractal.formula.FractalSample;
import com.shangin.fractal.render.FractalCalculator;
import com.shangin.fractal.render.RenderBackend;
import com.shangin.fractal.render.RenderTarget;
import com.shangin.fractal.render.RenderJob;
import com.shangin.fractal.render.FormulaDefinition;
import com.shangin.fractal.render.DirectDoubleRenderBackend;
import com.shangin.fractal.render.PrecisionSelectingRenderBackend;
import com.shangin.fractal.render.MandelbrotPerturbationRenderBackend;
import com.shangin.fractal.render.JuliaDeepZoomRenderBackend;
import com.shangin.fractal.scene.FractalScene;
import com.shangin.fractal.render.FractalColorizer;
import com.shangin.fractal.render.SamplePlane;
import com.shangin.fractal.render.RenderFrame;
import com.shangin.fractal.render.RenderGrid;

import com.shangin.fractal.render.PreciseFractalSampler;
import com.shangin.fractal.render.PreciseRenderGrid;
import java.math.BigDecimal;
import java.util.function.BooleanSupplier;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
import java.util.function.IntConsumer;

/** Exports scene snapshots or completed frames with adaptive 2x2–8x8 sampling. */
public final class AdaptivePngExportService implements AutoCloseable {

    static final int MIN_SAMPLE_GRID = 2;
    static final int MEDIUM_SAMPLE_GRID = 4;
    static final int MAX_SAMPLE_GRID = 8;

    private static final int TILE_SIZE = 32;
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

    private final RenderBackend backend;
    private boolean closed;

    public AdaptivePngExportService() {
        this(new PrecisionSelectingRenderBackend(new DirectDoubleRenderBackend(),
                new PrecisionSelectingRenderBackend(new MandelbrotPerturbationRenderBackend(),
                        new JuliaDeepZoomRenderBackend())));
    }

    AdaptivePngExportService(RenderBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /** Limits full-frame sample/color storage before allocation (including PNG staging). */
    public static void validateTarget(RenderTarget target) {
        Objects.requireNonNull(target);
        long pixels = (long) target.width() * target.height();
        long limit = Math.min(16_777_216L, Runtime.getRuntime().maxMemory() / 3 / 64);
        if (pixels > limit) {
            throw new IllegalArgumentException("Export exceeds the memory budget (maximum "
                    + limit + " pixels)");
        }
    }

    /** Renders an immutable scene independently of the interactive surface and GPU policy. */
    public synchronized void export(FractalScene scene, RenderTarget target, Path path,
                                    Consumer<Path> onSuccess, Consumer<Throwable> onError) {
        export(scene, target, path, ignored -> {}, onSuccess, onError);
    }

    /** Reports integer stage progress; callbacks run on export threads and never after cancellation. */
    public synchronized void export(FractalScene scene, RenderTarget target, Path path,
                                    IntConsumer onProgress,
                                    Consumer<Path> onSuccess, Consumer<Throwable> onError) {
        Objects.requireNonNull(scene);
        Objects.requireNonNull(path);
        Objects.requireNonNull(onSuccess);
        Objects.requireNonNull(onError);
        validateTarget(target);
        ensureOpen();
        RenderJob job = new RenderJob(FormulaDefinition.forScene(scene), scene.viewport(),
                target.width(), target.height(), scene.iterations().maxIterations(
                        scene.fractal().defaultViewport().scaleExact(), scene.viewport().scaleExact()),
                target.priority(), java.util.Optional.empty());
        if (!backend.supports(job)) {
            throw new IllegalArgumentException("No CPU backend supports this export precision");
        }
        long exportId = generation.incrementAndGet();
        Progress progress = new Progress(exportId, Objects.requireNonNull(onProgress));
        coordinator.submit(() -> {
            try {
                if (shouldCancel(exportId)) return;
                progress.report(0);
                AtomicLong basePixels = new AtomicLong();
                long totalPixels = (long) target.width() * target.height();
                RenderFrame frame = backend.render(RenderFrame.create(job),
                        () -> shouldCancel(exportId), region -> {
                            // Mirrored regions may overlap; only backend return completes this stage.
                            long pixels = basePixels.addAndGet((long) region.width() * region.height());
                            progress.report((int) Math.min(44, 45 * pixels / totalPixels));
                        }, null);
                if (shouldCancel(exportId)) return;
                runExport(exportId, frame, scene.coloring().createStrategy(frame.samplePlane()),
                        path, onSuccess, onError, progress);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException exception) {
                reportError(exportId, exception, onError);
            }
        });
    }

    private final class Progress {
        private final long exportId;
        private final IntConsumer listener;
        private int last = -1;

        private Progress(long exportId, IntConsumer listener) {
            this.exportId = exportId;
            this.listener = listener;
        }

        private void report(int percent) {
            synchronized (AdaptivePngExportService.this) {
                if (shouldCancel(exportId) || percent <= last) return;
                last = Math.min(100, percent);
                listener.accept(last);
            }
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Export service is closed");
    }

    /** Invalidates the generation; queued exports wait for actual worker exit. */
    public synchronized void cancelCurrent() {
        generation.incrementAndGet();
    }

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

        validateTarget(new RenderTarget(sourceFrame.job().width(), sourceFrame.job().height()));
        ensureOpen();
        long exportId = generation.incrementAndGet();

        coordinator.submit(() -> runExport(
                exportId,
                sourceFrame,
                coloring,
                target,
                onSuccess,
                onError,
                new Progress(exportId, ignored -> {})
        ));
    }

    private void runExport(
            long exportId,
            RenderFrame sourceFrame,
            ColoringStrategy coloring,
            Path target,
            Consumer<Path> onSuccess,
            Consumer<Throwable> onError,
            Progress progress
    ) {
        try {
            if (shouldCancel(exportId)) return;
            SamplePlane data = sourceFrame.samplePlane();

            if (!sourceFrame.isComplete()) {
                throw new IllegalArgumentException("Adaptive export requires a completed frame");
            }

            progress.report(45);
            int[] baseColors = colorBaseFrame(data, coloring);
            if (shouldCancel(exportId)) return;
            int[] output = new int[data.size()];
            List<Future<?>> tasks = createTasks(
                    exportId,
                    sourceFrame,
                    coloring,
                    baseColors,
                    output,
                    progress
            );

            awaitWorkers(tasks);

            if (shouldCancel(exportId)) {
                return;
            }

            progress.report(95);
            writeAndPublish(exportId, target, data.width(), data.height(), output, path -> {
                progress.report(100);
                onSuccess.accept(path);
            });
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
            long exportId, RenderFrame frame, ColoringStrategy coloring,
            int[] baseColors, int[] output, Progress progress
    ) throws InterruptedException, ExecutionException {
        List<Future<?>> tasks = new ArrayList<>();
        AtomicInteger completedTiles = new AtomicInteger();
        int totalTiles = ((frame.job().width() + TILE_SIZE - 1) / TILE_SIZE)
                * ((frame.job().height() + TILE_SIZE - 1) / TILE_SIZE);
        boolean deep = frame.job().formula().preset() != null
                && frame.job().formula().preset().supportsDeepZoom()
                && !frame.job().formula().preset().hasSufficientDirectPrecision(
                frame.job().viewport(), frame.job().width(), frame.job().height());
        PreciseFractalSampler sampler = deep ? PreciseFractalSampler.create(
                frame.job(), () -> shouldCancel(exportId)).orElseThrow(CancellationException::new) : null;
        try {
            for (int y = 0; y < frame.job().height(); y += TILE_SIZE) {
                for (int x = 0; x < frame.job().width(); x += TILE_SIZE) {
                    if (shouldCancel(exportId)) throw new CancellationException();
                    int xFrom = x;
                    int yFrom = y;
                    tasks.add(workers.submit(() -> {
                        renderTile(exportId, frame, coloring, baseColors, output, xFrom, yFrom, sampler);
                        progress.report(45 + 50 * completedTiles.incrementAndGet() / totalTiles);
                    }));
                }
            }
        } catch (RuntimeException exception) {
            awaitWorkers(tasks);
            throw exception;
        }
        return tasks;
    }

    /** A cancelled Future is not evidence that its worker has stopped writing. */
    private static void awaitWorkers(List<Future<?>> tasks)
            throws InterruptedException, ExecutionException {
        boolean interrupted = false;
        ExecutionException failure = null;
        for (Future<?> task : tasks) {
            for (;;) {
                try {
                    task.get();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (ExecutionException exception) {
                    if (failure == null) failure = exception;
                    break;
                }
            }
        }
        if (interrupted) throw new InterruptedException();
        if (failure != null) throw failure;
    }

    private void renderTile(long exportId, RenderFrame frame, ColoringStrategy coloring,
                            int[] baseColors, int[] output, int xFrom, int yFrom,
                            PreciseFractalSampler sampler) {
        SamplePlane data = frame.samplePlane();
        RenderGrid grid = frame.renderGrid();
        FractalCalculator calculator = frame.job().formula().createDirectCalculator();
        for (int y = yFrom; y < Math.min(yFrom + TILE_SIZE, data.height()); y++) {
            for (int x = xFrom; x < Math.min(xFrom + TILE_SIZE, data.width()); x++) {
                if (shouldCancel(exportId)) throw new CancellationException();
                int index = y * data.width() + x;
                int color = sampler != null ? samplePrecisePixel(sampler, coloring,
                        grid.preciseGrid() != null ? grid.preciseGrid() : frame.job().preciseGrid(),
                        frame.job().maxIterations(), data, baseColors, x, y,
                        () -> shouldCancel(exportId)) : samplePixel(calculator, coloring, grid,
                        frame.job().maxIterations(), data, baseColors, x, y);
                output[index] = data.escaped(index) ? dither(color, x, y) : color;
            }
        }
    }

    private void writeAndPublish(long exportId, Path target, int width, int height,
                                 int[] output, Consumer<Path> onSuccess) throws IOException {
        Path destination = target.toAbsolutePath();
        Path temporary = Files.createTempFile(destination.getParent(), ".fractallens-export-", ".png");
        try {
            PngExporter.write(temporary, width, height, output, () -> shouldCancel(exportId));
            synchronized (this) {
                if (shouldCancel(exportId)) return;
                // Serialize the final replacement with cancellation and supersession.
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                onSuccess.accept(target);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
        double centerReal = grid.realAt(pixelX);
        double centerImaginary = grid.imaginaryAt(pixelY);
        ColorAccumulator accumulator = new ColorAccumulator();

        for (int sampleY = 0; sampleY < sampleGridSize; sampleY++) {
            for (int sampleX = 0; sampleX < sampleGridSize; sampleX++) {
                double offsetX = ((sampleX + 0.5) / sampleGridSize) - 0.5;
                double offsetY = ((sampleY + 0.5) / sampleGridSize) - 0.5;
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

    private boolean isCurrent(long exportId) {
        return exportId == generation.get();
    }

    private boolean shouldCancel(long exportId) {
        return !isCurrent(exportId) || Thread.currentThread().isInterrupted();
    }

    private synchronized void reportError(
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
        if (closed) return;
        closed = true;
        cancelCurrent();
        // Cleanup runs after the current operation has drained all registered workers.
        coordinator.submit(() -> {
            try {
                backend.close();
            } finally {
                workers.shutdown();
            }
        });
        coordinator.shutdown();
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
