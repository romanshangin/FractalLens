package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalPreset;
import com.shangin.fractal.formula.FractalSample;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Julia deep zoom with shared high-precision references and pixel perturbations.
 * No Mandelbrot interior shortcuts or rebasing assumptions apply here.
 * Unreliable perturbations fall back to arbitrary-precision pixel iteration.
 */
public final class JuliaDeepZoomRenderBackend implements RenderBackend {
    private static final BigDecimal FOUR = BigDecimal.valueOf(4);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private final int workerCount;
    private final ExecutorService workers;

    public JuliaDeepZoomRenderBackend() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    }

    JuliaDeepZoomRenderBackend(int workerCount) {
        if (workerCount < 1) throw new IllegalArgumentException("Worker count must be positive");
        this.workerCount = workerCount;
        AtomicInteger sequence = new AtomicInteger();
        workers = RenderDiagnostics.workerPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "julia-deep-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public boolean supports(RenderJob job) {
        return job.formula().preset() == FractalPreset.JULIA;
    }

    static PreciseFractalSampler sampler(RenderJob job, MathContext context) {
        if (job.formula().preset() != FractalPreset.JULIA)
            throw new IllegalArgumentException("Julia sampler requires a Julia job");
        // Preserve the exact binary constants used by JuliaFormula across the precision transition.
        BigDecimal cr = new BigDecimal(job.formula().parameters().get("cReal"));
        BigDecimal ci = new BigDecimal(job.formula().parameters().get("cImaginary"));
        OrbitTrap trap = job.formula().orbitTrap();
        return (real, imaginary, cancelled) -> {
            BigDecimal zr = real;
            BigDecimal zi = imaginary;
            double trapDistance = Double.POSITIVE_INFINITY;
            int iteration = 0;
            while (iteration < job.maxIterations()) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
                BigDecimal rr = zr.multiply(zr, context);
                BigDecimal ii = zi.multiply(zi, context);
                int radiusComparison = rr.add(ii, context).compareTo(FOUR);
                // Near |z| = 2, rounding can erase a tiny positive imaginary square.
                if (radiusComparison == 0)
                    radiusComparison = zr.multiply(zr).add(zi.multiply(zi)).compareTo(FOUR);
                if (radiusComparison > 0) break;
                BigDecimal nextReal = rr.subtract(ii, context).add(cr, context);
                zi = zr.multiply(zi, context).multiply(TWO, context).add(ci, context);
                zr = nextReal;
                if (trap != OrbitTrap.NONE)
                    trapDistance = Math.min(trapDistance, trap.distance(zr.doubleValue(), zi.doubleValue()));
                iteration++;
            }
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
            return new FractalSample(iteration, iteration < job.maxIterations(),
                    zr.doubleValue(), zi.doubleValue(), 2.0,
                    Double.isFinite(trapDistance) ? trapDistance : Double.NaN);
        };
    }

    @Override
    public RenderFrame render(RenderFrame frame, BooleanSupplier cancelled,
                              Consumer<RenderRegion> regionCompleted,
                              Consumer<TileTimingStats> timingCompleted) throws InterruptedException {
        if (!supports(frame.job())) throw new IllegalArgumentException("Julia backend requires a Julia job");
        if (frame.isComplete() || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return frame;
        PreciseRenderGrid grid = frame.renderGrid().preciseGrid();
        if (grid == null) grid = frame.job().preciseGrid();
        PreciseFractalSampler sampler = JuliaReferenceSampler.create(frame.job(), grid, cancelled);
        if (sampler == null) return frame;
        BigDecimal[] real = new BigDecimal[frame.job().width()];
        BigDecimal[] imaginary = new BigDecimal[frame.job().height()];
        for (int x = 0; x < real.length; x++) real[x] = grid.realAt(x);
        for (int y = 0; y < imaginary.length; y++) imaginary[y] = grid.imaginaryAt(y);
        ConcurrentLinkedQueue<RenderRegion> queue = new ConcurrentLinkedQueue<>();
        for (RenderRegion tile : MandelbrotPerturbationRenderBackend.orderedTiles(frame.job())) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return frame;
            if (!frame.validity().isRegionReady(tile)) queue.add(tile);
        }
        ConcurrentLinkedQueue<Long> times = new ConcurrentLinkedQueue<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int worker = 0, count = Math.min(workerCount, queue.size()); worker < count; worker++) {
            tasks.add(() -> {
                RenderRegion tile;
                while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()
                        && (tile = queue.poll()) != null) {
                    long started = System.nanoTime();
                    for (RenderRegion span : frame.validity().missingRowSpans(tile, cancelled)) {
                        for (int y = span.y(); y < span.y() + span.height(); y++) {
                            for (int x = span.x(); x < span.x() + span.width(); x++) {
                                FractalSample sample = sampler.sample(real[x], imaginary[y], cancelled);
                                if (sample == null) return null;
                                frame.samplePlane().set(x, y, sample);
                            }
                        }
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
                        frame.validity().markReady(span);
                        regionCompleted.accept(span);
                    }
                    times.add(System.nanoTime() - started);
                }
                return null;
            });
        }
        for (Future<Void> future : workers.invokeAll(tasks)) {
            try { future.get(); }
            catch (ExecutionException exception) {
                throw new IllegalStateException("Julia deep render failed", exception.getCause());
            }
        }
        if (timingCompleted != null && !cancelled.getAsBoolean()) {
            long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
            timingCompleted.accept(sorted.length == 0 ? new TileTimingStats(0, 0, 0, 0)
                    : new TileTimingStats(sorted.length, sorted[0] / 1e6,
                    sorted[sorted.length / 2] / 1e6, sorted[sorted.length - 1] / 1e6));
        }
        return frame;
    }

    @Override
    public void close() { workers.shutdownNow(); }
}
