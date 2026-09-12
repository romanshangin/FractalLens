package com.shangin.fractal.render;

import com.shangin.fractal.coloring.OrbitTrap;
import com.shangin.fractal.formula.FractalSample;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.LongAdder;

/** Julia perturbation: delta z starts at the pixel offset and delta c is zero. */
final class JuliaReferenceSampler implements PreciseFractalSampler {
    private static final BigDecimal FOUR = BigDecimal.valueOf(4);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final double ROUNDING = Math.ulp(1.0) * Math.ulp(1.0);
    private static final int MAX_REFERENCE_ITERATIONS = 65_536;
    private final RenderJob job;
    private final MathContext context;
    private final PreciseFractalSampler fallback;
    private final Reference primary;
    private final java.util.concurrent.ConcurrentMap<Thread, Recovery> recovery =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final LongAdder referenceBuilds = new LongAdder();
    private final LongAdder fallbackPixels = new LongAdder();

    private JuliaReferenceSampler(RenderJob job, PreciseRenderGrid grid, Reference primary) {
        this.job = job;
        this.context = grid.mathContext();
        this.fallback = JuliaDeepZoomRenderBackend.sampler(job, context);
        this.primary = primary;
        referenceBuilds.increment();
    }

    static JuliaReferenceSampler create(RenderJob job, PreciseRenderGrid grid, BooleanSupplier cancelled) {
        Reference primary = reference(job, grid.mathContext(), grid.realAt(job.width() / 2),
                grid.imaginaryAt(job.height() / 2), cancelled);
        return primary == null ? null : new JuliaReferenceSampler(job, grid, primary);
    }

    @Override
    public FractalSample sample(BigDecimal real, BigDecimal imaginary, BooleanSupplier cancelled) {
        if (stopped(cancelled)) return null;
        FractalSample sample = perturb(primary, real, imaginary, cancelled);
        if (sample != null || stopped(cancelled)) return sample;
        Recovery local = recovery.computeIfAbsent(Thread.currentThread(), ignored -> new Recovery());
        if (local.reference != null) {
            sample = perturb(local.reference, real, imaginary, cancelled);
            if (sample != null || stopped(cancelled)) return sample;
        }
        // Bounded worker-local references avoid rebuilding a BigDecimal orbit for every nearby pixel.
        if (local.builds < 8) {
            local.reference = reference(job, context, real, imaginary, cancelled);
            local.builds++;
            if (local.reference == null) return null;
            referenceBuilds.increment();
            if (local.reference.terminal != null) return local.reference.terminal;
        }
        fallbackPixels.increment();
        return fallback.sample(real, imaginary, cancelled);
    }

    long referenceBuildCount() { return referenceBuilds.sum(); }
    long fallbackPixelCount() { return fallbackPixels.sum(); }

    private FractalSample perturb(Reference ref, BigDecimal real, BigDecimal imaginary,
                                   BooleanSupplier cancelled) {
        BigDecimal exactR = real.subtract(ref.startReal, context);
        BigDecimal exactI = imaginary.subtract(ref.startImaginary, context);
        if (exactR.signum() == 0 && exactI.signum() == 0) return ref.terminal;
        double dr = exactR.doubleValue(), di = exactI.doubleValue();
        // Retain the arbitrary-precision path when double cannot safely carry a delta.
        if (!representable(exactR, dr) || !representable(exactI, di)) return null;
        Wide deltaR = Wide.from(exactR), deltaI = Wide.from(exactI);
        double error = Math.ulp(deltaR.low) + Math.ulp(deltaI.low);
        double trapDistance = Double.POSITIVE_INFINITY;
        OrbitTrap trap = job.formula().orbitTrap();
        int referenceIndex = 0;
        for (int n = 0; ; n++) {
            if ((n & 31) == 0 && stopped(cancelled)) return null;
            Wide referenceR = new Wide(ref.real[referenceIndex], ref.realLow[referenceIndex]);
            Wide referenceI = new Wide(ref.imaginary[referenceIndex], ref.imaginaryLow[referenceIndex]);
            Wide stateR = referenceR.add(deltaR), stateI = referenceI.add(deltaI);
            double zr = stateR.value(), zi = stateI.value();
            double magnitude = Math.sqrt(zr * zr + zi * zi);
            double deltaMagnitude = Math.sqrt(dr * dr + di * di);
            double stateError = error + 4 * ROUNDING * (ref.magnitude[referenceIndex] + deltaMagnitude);
            if (!Double.isFinite(magnitude) || !Double.isFinite(stateError)
                    || stateError > 1e-12 * Math.max(1.0, magnitude)) return null;
            if (n > 0 && trap != OrbitTrap.NONE)
                trapDistance = Math.min(trapDistance, trap.distance(zr, zi));
            // JuliaFormula treats escape on the final permitted iteration as capped.
            if (n == job.maxIterations()) return sample(n, false, zr, zi, trapDistance);
            if (Math.abs(magnitude - 2.0) <= stateError + 8 * Math.ulp(1.0)) return null;
            if (magnitude > 2.0) return sample(n, true, zr, zi, trapDistance);
            // All Julia reference positions evolve under the same fixed c. Reindexing
            // is valid, but the accumulated absolute error must survive the rebase.
            if (referenceIndex + 1 == ref.real.length) {
                if (ref.real.length < 2) return null;
                referenceIndex = 0;
                referenceR = new Wide(ref.real[0], ref.realLow[0]);
                referenceI = new Wide(ref.imaginary[0], ref.imaginaryLow[0]);
                deltaR = stateR.subtract(referenceR);
                deltaI = stateI.subtract(referenceI);
                dr = deltaR.value();
                di = deltaI.value();
                deltaMagnitude = Math.sqrt(dr * dr + di * di);
                error = stateError + 4 * ROUNDING * (magnitude + ref.magnitude[0]);
            }
            double referenceMagnitude = ref.magnitude[referenceIndex];
            Wide nextR = referenceR.multiply(deltaR).subtract(referenceI.multiply(deltaI)).twice()
                    .add(deltaR.multiply(deltaR)).subtract(deltaI.multiply(deltaI));
            Wide nextI = referenceR.multiply(deltaI).add(referenceI.multiply(deltaR)).twice()
                    .add(deltaR.multiply(deltaI).twice());
            error = (2 * (magnitude + stateError) + error) * error
                    + 32 * ROUNDING * (2 * referenceMagnitude * deltaMagnitude + deltaMagnitude * deltaMagnitude)
                    + Double.MIN_VALUE;
            deltaR = nextR;
            deltaI = nextI;
            dr = nextR.value();
            di = nextI.value();
            referenceIndex++;
        }
    }

    private static boolean representable(BigDecimal exact, double value) {
        return exact.signum() == 0 || Double.isFinite(value) && Math.abs(value) >= Double.MIN_NORMAL;
    }

    private static FractalSample sample(int n, boolean escaped, double r, double i, double distance) {
        return new FractalSample(n, escaped, r, i, 2,
                Double.isFinite(distance) ? distance : Double.NaN);
    }

    private static Reference reference(RenderJob job, MathContext context, BigDecimal startReal,
                                       BigDecimal startImaginary, BooleanSupplier cancelled) {
        BigDecimal cr = new BigDecimal(job.formula().parameters().get("cReal"));
        BigDecimal ci = new BigDecimal(job.formula().parameters().get("cImaginary"));
        int limit = Math.min(job.maxIterations(), MAX_REFERENCE_ITERATIONS);
        double[] real = new double[Math.min(limit + 1, 256)];
        double[] imaginary = new double[real.length];
        double[] realLow = new double[real.length], imaginaryLow = new double[real.length];
        double[] magnitude = new double[real.length];
        BigDecimal r = startReal, i = startImaginary;
        double trapDistance = Double.POSITIVE_INFINITY;
        OrbitTrap trap = job.formula().orbitTrap();
        for (int n = 0; ; n++) {
            if (stopped(cancelled)) return null;
            if (n == real.length) {
                real = Arrays.copyOf(real, Math.min(limit + 1, real.length * 2));
                imaginary = Arrays.copyOf(imaginary, real.length);
                realLow = Arrays.copyOf(realLow, real.length);
                imaginaryLow = Arrays.copyOf(imaginaryLow, real.length);
                magnitude = Arrays.copyOf(magnitude, real.length);
            }
            real[n] = r.doubleValue();
            imaginary[n] = i.doubleValue();
            magnitude[n] = Math.hypot(real[n], imaginary[n]);
            realLow[n] = r.subtract(new BigDecimal(real[n])).doubleValue();
            imaginaryLow[n] = i.subtract(new BigDecimal(imaginary[n])).doubleValue();
            BigDecimal rr = r.multiply(r, context), ii = i.multiply(i, context);
            int radius = rr.add(ii, context).compareTo(FOUR);
            if (radius == 0) radius = r.multiply(r).add(i.multiply(i)).compareTo(FOUR);
            if (radius > 0 || n == limit) {
                FractalSample terminal = radius > 0 || n == job.maxIterations()
                        ? sample(n, n < job.maxIterations(), real[n], imaginary[n], trapDistance) : null;
                return new Reference(startReal, startImaginary, Arrays.copyOf(real, n + 1),
                        Arrays.copyOf(imaginary, n + 1), Arrays.copyOf(realLow, n + 1),
                        Arrays.copyOf(imaginaryLow, n + 1), Arrays.copyOf(magnitude, n + 1), terminal);
            }
            BigDecimal nextR = rr.subtract(ii, context).add(cr, context);
            i = r.multiply(i, context).multiply(TWO, context).add(ci, context);
            r = nextR;
            if (trap != OrbitTrap.NONE)
                trapDistance = Math.min(trapDistance, trap.distance(r.doubleValue(), i.doubleValue()));
        }
    }

    private static boolean stopped(BooleanSupplier cancelled) {
        return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    private record Reference(BigDecimal startReal, BigDecimal startImaginary,
                             double[] real, double[] imaginary, double[] realLow, double[] imaginaryLow, double[] magnitude, FractalSample terminal) {}
    /** Two-component arithmetic keeps Julia's tiny first-step displacement through c addition. */
    private record Wide(double high, double low) {
        static Wide from(BigDecimal value) {
            double high = value.doubleValue();
            return new Wide(high, value.subtract(new BigDecimal(high)).doubleValue());
        }
        double value() { return high + low; }
        Wide add(Wide other) {
            double sum = high + other.high;
            double virtual = sum - high;
            double tail = (high - (sum - virtual)) + (other.high - virtual) + low + other.low;
            double result = sum + tail;
            return new Wide(result, tail - (result - sum));
        }
        Wide subtract(Wide other) { return add(new Wide(-other.high, -other.low)); }
        Wide twice() { return new Wide(high * 2, low * 2); }
        Wide multiply(Wide other) {
            double product = high * other.high;
            double tail = Math.fma(high, other.high, -product)
                    + high * other.low + low * other.high + low * other.low;
            double result = product + tail;
            return new Wide(result, tail - (result - product));
        }
    }

    private static final class Recovery {
        Reference reference;
        int builds;
    }
}
