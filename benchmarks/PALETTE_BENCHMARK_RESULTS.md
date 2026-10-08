# Palette recoloring: CPU / Vulkan experiment (roadmap 8.2)

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

Measured 2026-09-03 on Apple M3 Pro (12 logical processors), macOS 26.6.2,
ARM64 Java 26.0.2, JavaFX 26.0.2, LWJGL 3.4.2 / FFM / MoltenVK Vulkan 1.1.350.
JavaFX reported output scale 2.0. Raw per-frame measurements are preserved in
PALETTE_BENCHMARK_RESULTS.csv (private archive: `PALETTE_BENCHMARK_RESULTS.csv`).

The current results use 30 warmup pairs and 120 measured pairs per case.
A preceding repeat with the original 12/40 settings is saved in
PALETTE_BENCHMARK_LWJGL342_DEFAULT.csv (private archive: `PALETTE_BENCHMARK_LWJGL342_DEFAULT.csv`).
The original 2026-09-02 LWJGL 3.3.6 data is preserved in
PALETTE_BENCHMARK_LWJGL336.csv (private archive: `PALETTE_BENCHMARK_LWJGL336.csv`).
Both new runs passed their per-frame output comparisons without CPU fallback.
Neither run emitted Unsafe warnings.

## Decision

Keep CPU as the default and GPU as an opt-in experiment. In the longer run,
3024×1964 AA recoloring was about 28% faster on GPU, and time through the JavaFX
buffer was about 16% lower (4.717 vs 5.633 ms). The original-length repeat showed
about 19% and 3% gains respectively. This variation limits the precision of the
end-to-end claim. CPU was faster in all three other workloads in both runs.

The update therefore does not justify enabling GPU recoloring globally. The
large-AA workload benefits, but broader GPU residency still requires its own
conformance and performance gate. These runs are not a controlled causal
comparison of LWJGL versions: warmup, scheduling and desktop conditions differ.

## Reproduce

```sh
mvn -Ppalette-benchmark javafx:run
```

This opens a JavaFX window, prepares fixed Mandelbrot samples, alternates CPU
and GPU operations, checks their output, and writes `target/palette-benchmark.csv`.
A missing/failing GPU is an error, not a successful CPU-only comparison.

```sh
# Current reported run; JAVA_TOOL_OPTIONS reaches the JavaFX child JVM.
JAVA_TOOL_OPTIONS='-Dfractal.benchmark.warmup=30 -Dfractal.benchmark.samples=120 -Dfractal.benchmark.sizes=1512x982,3024x1964' mvn -Ppalette-benchmark javafx:run

# Explicit CPU-only measurement, usable without GPU natives.
JAVA_TOOL_OPTIONS='-Dfractal.gpu.enabled=false -Dfractal.benchmark.cpuOnly=true' mvn -Ppalette-benchmark javafx:run

# Enable the experimental backend in the regular application.
JAVA_TOOL_OPTIONS=-Dfractal.gpu.palette.enabled=true mvn javafx:run
```

The main recorded run used 30 warmup pairs and 120 measured pairs per
case, plus one separately recorded first-use (`cold`) pair. Both backends use
the same offset in each pair, and execution order alternates. Each publication
waits for the next JavaFX animation pulse before the next operation. Result
comparison is outside timed regions. Warmup rows are excluded from the CSV.
The `cold` row means first use of that case; later cases reuse the runtime and
unchanged resident data, rather than representing a fresh process.

## Workload and limits

- Mandelbrot viewport: center (-0.75, 0), scale 2.4, 300 iterations, ICE palette.
  The animation offset advances by 0.03713 per pair.
- The AA case uses a fixed 6.25% pixel mask, alternating 2×2 and 4×4 grids:
  92,799 AA pixels at 1512×982 and 371,196 at 3024×1964. Insertion order is
  deterministic. This controls AA workload; it is not a measurement of the
  application's adaptive candidate selection.
- Formula calculation, fixture construction, phase encoding, and the original
  gradient-palette construction are outside the timed palette loop. Per-frame
  CPU lookup preparation, GPU first-use compilation/allocation, changed-data
  upload, dispatch and readback are included.
- The CPU baseline is the original parallel base + AA recoloring, with one
  thread-local lookup update and no extra LUT copies.
- The visible image was 756×491 logical pixels for the smaller buffer. The large
  buffer was fitted into a 1512×894 logical view to fit the desktop. The full
  3024×1964 array still crossed the JavaFX buffer boundary. This is a Retina
  buffer-cost experiment, not a full-screen 1:1 scanout benchmark.
- Results are two controlled runs on one Mac. JVM scheduling, common-pool workers,
  window rendering, GPU power state and other desktop work affect timings.

## Steady-state results

Times are milliseconds. Median and p95 use nearest-rank percentiles over the
120 measured samples in the main run. `To buffer` includes recolor, FX queue
delay and publication.

| Buffer / workload | Backend | Recolor median | Recolor p95 | Publication median | To buffer median |
| --- | --- | ---: | ---: | ---: | ---: |
| 1512×982 / base | CPU | 0.539 | 0.803 | 0.315 | 0.936 |
| 1512×982 / base | GPU | 1.344 | 1.639 | 0.522 | 1.927 |
| 1512×982 / AA | CPU | 1.117 | 1.848 | 0.247 | 1.449 |
| 1512×982 / AA | GPU | 1.619 | 2.003 | 0.298 | 2.100 |
| 3024×1964 / base | CPU | 0.944 | 1.193 | 0.988 | 2.442 |
| 3024×1964 / base | GPU | 3.371 | 3.820 | 0.823 | 4.470 |
| 3024×1964 / AA | CPU | 4.411 | 5.703 | 0.655 | 5.633 |
| 3024×1964 / AA | GPU | 3.174 | 4.375 | 0.610 | 4.717 |

GPU component medians:

| Buffer / workload | Preparation | Upload | Dispatch + wait | Readback | FX queue |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1512×982 / base | 0.011 | <0.001 | 0.991 | 0.269 | 0.066 |
| 1512×982 / AA | 0.008 | <0.001 | 1.166 | 0.308 | 0.057 |
| 3024×1964 / base | 0.007 | <0.001 | 2.182 | 1.106 | 0.874 |
| 3024×1964 / AA | 0.003 | <0.001 | 2.550 | 0.809 | 0.990 |

Component medians are independent and need not sum to the median total.
Total wall time also includes runtime locking and orchestration.
Every steady-state GPU sample uploaded **zero buffer bytes**; only the 32-byte
push-constant block changed. First-use uploads were 3.68 MB (initial base/tables),
3.34 MB (small AA), 12.62 MB (new large base), and 13.36 MB (large AA).
The initial GPU recolor took 147.5 ms in the main run and 197.7 ms in the
original-length repeat, including shader compilation and setup,
so first-use latency remains materially higher than warmed operation.

## What the timers measure

- Preparation: CPU lookup or GPU request/setup/allocation work.
- Upload: packing/copying changed inputs into persistent mapped buffers and
  flushing when necessary. Apple Silicon uses unified memory.
- Dispatch: host command encoding, queue submission and fence wait for both
  base and AA passes. This is not pure shader time from GPU timestamp queries.
- Readback: invalidate (if needed), then copy the mapped ARGB output into the
  caller's reusable Java array.
- Publication: the application's shared `SurfaceBuffer.publish` array copy
  and `PixelBuffer.updateBuffer` call, on the FX thread.
- `next_pulse_ns`: separate wait until the next animation pulse. Neither this
  nor publication is a measurement of completed rasterization or display scanout.

## Correctness and fallback checks

The portable suite verifies one-lookup CPU behavior, all 65,536 phase addresses
at wrap/rounding boundaries and 100 fixed-seed random offsets, snapshot
invalidation, cancellation, and full-output CPU fallback after native failure.
The hardware gate checks all palette presets, escaped/interior samples,
1/4/16/32-sample AA, packed tails, changed dimensions/scale/AA/palette,
resident-data reuse, simulated device loss after dispatch, and runtime reopen.

Base RGB and all alpha channels must match exactly. AA RGB permits a maximum
one-level error per channel from FP32 linear-light averaging. The interactive
benchmark checks these bounds for every compared frame.

```sh
mvn test
mvn -Pgpu-smoke test
```

The lifecycle/palette suite passed on this Mac after the LWJGL 3.4.2 update
(2026-09-02); both 2026-09-03 benchmark runs passed their per-frame checks.
The portable suite also passed on 2026-09-03: 303 tests, zero failures/errors,
two native-gate skips. Fresh-JVM missing-native and missing-shaderc checks passed
in the original validation. Intel Macs, Windows, non-coherent memory and
validation-layer runs are not covered by these measurements.
