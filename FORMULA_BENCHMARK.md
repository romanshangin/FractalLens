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
