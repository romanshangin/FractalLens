# Mandelbrot and Julia calculation benchmark

Run the single-threaded formula benchmark with:

```shell
mvn -Pformula-benchmark verify -DskipTests
```

The benchmark measures the calculation kernel independently from worker
scheduling, progressive callbacks, JavaFX, and colorization. It covers both
formulas at overview, 100x, and 10,000x zoom levels and reports:

- p50 and p95 elapsed calculation time;
- nanoseconds per pixel and megapixels per second;
- mean, p50, and p95 iteration counts;
- percentage of points that reached the maximum iteration limit.

Defaults use 1920x1080, one warm-up, and three measured runs. Override them with
`formulaBenchmark.width`, `formulaBenchmark.height`,
`formulaBenchmark.baseIterations`, `formulaBenchmark.warmups`, and
`formulaBenchmark.runs` system properties.

Run the HiDPI-equivalent baseline with:

```shell
mvn -Pformula-benchmark verify -DskipTests \
  -DformulaBenchmark.width=2560 -DformulaBenchmark.height=1440
```

Record the environment and complete result table before and after every kernel
optimization. Performance changes should also retain the formula correctness
tests and bit-for-bit sample comparisons where the optimization permits them.

## Deep-zoom gate

Run the Mandelbrot deep-zoom gate with:

```shell
mvn test
mvn -Pperturbation-benchmark verify -DskipTests
```

The test suite checks the perturbation backend against direct arbitrary-
precision samples at deep control points, verifies glitch rebasing and precise
subpixel sampling, and covers reference-orbit cancellation, bounded cache
eviction, deep backend selection, and progressive tile delivery. The benchmark
reports cold and cached reference-orbit startup separately, time to the first
calculated region, total render time, tile timings, modified rebase counts,
additional reference orbits, BLA preparation time, accepted BLA blocks and
covered iterations, average logical/scalar iterations, effective calculation
throughput, and the backend selected on each side of the hardware-double
precision threshold. Logical iterations include reference retries and those
covered by BLA; scalar iterations subtract the BLA-covered work.

The first-region timing starts inside the backend; it excludes interaction
debouncing, frame preparation in the controller, progress dispatch, and the
JavaFX display pulse. Wheel events now use a 30 ms debounce, explicit pinch
completion flushes any pending render immediately, and the first progress
batch has no artificial scheduling delay (later batches retain 16 ms).
These scheduling policies are covered by deterministic tests; the backend
benchmark does not measure the end-to-end input-to-display latency.

Production renders poll mutable generation state for cancellation. The
benchmark therefore uses a non-cancelled `AtomicBoolean` supplier instead of a
constant lambda, preventing the JIT from folding cancellation polling out of
the measured kernel.

By default each viewport uses the production `IterationSettings` policy: 300
base iterations plus 50 iterations per binary zoom level. Use
`-Dperturbation.iterations=<count>` only when a fixed cap is intentionally
required for an isolated comparison. The other supported properties are
`perturbation.width`, `perturbation.height`, `perturbation.warmups`,
`perturbation.runs`, and `perturbation.deepScale`. The default production
viewport is a glitch-heavy boundary workload at scale `1.6e-13`; it is kept in
the gate so modified rebasing and any remaining reference-orbit retry costs stay
visible in performance results.

Compare BLA with the unchanged scalar/rebasing kernel on the same deeper
base-frame workload using:

```shell
mvn -Pperturbation-benchmark verify -DskipTests -Dperturbation.deepScale=1e-30 -Dperturbation.bla=false
mvn -Pperturbation-benchmark verify -DskipTests -Dperturbation.deepScale=1e-30
```

`perturbation.bla=false` disables BLA for the benchmark's base-frame backend
only; the subsequent deep-AA pass still uses the production sampler. BLA
correctness tests compare complete sample planes with scalar output, check
BigDecimal controls and subpixels, and cover iteration caps, critical points,
out-of-viewport sampling, cancellation, and rebuilding radii after a zoom while
the reference orbit is reused.
