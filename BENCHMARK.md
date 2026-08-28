# Frame reuse benchmark

Run the dedicated benchmark profile:

```shell
mvn -Pbenchmark verify
```

The default run compares fresh and reused pan renders at 1920x1080 and at a
HiDPI-equivalent 2560x1440 render resolution. Each configuration covers short,
medium, and near-full-frame horizontal pans with tile sizes 16, 32, and 64.

The report includes p50 and p95 time to the first correct region, total render
time, calculation and colorization time, calculated tile count, reused-pixel
percentage, and median speedup relative to a fresh render.

Defaults favor a reasonably short local run. Increase repetitions when taking
numbers for a performance decision:

```shell
mvn -Pbenchmark verify -Dbenchmark.warmups=3 -Dbenchmark.runs=10
```

Other supported properties are `benchmark.iterations`, `benchmark.workers`,
and a comma-separated `benchmark.tileSizes` list.

The benchmark measures core CPU calculation, reuse planning/data transfer, and
colorization. It intentionally excludes JavaFX scheduling, PixelBuffer upload,
and displayed-image shifting; those require a separate UI-level benchmark.
