# Palette recoloring: CPU / Vulkan experiment (roadmap 8.2)

Measured 2026-09-02 on Apple M3 Pro (12 logical processors), macOS 26.6.2,
ARM64 Java 26.0.2, JavaFX 26.0.2, LWJGL 3.3.6 / MoltenVK Vulkan 1.2.296.
JavaFX reported output scale 2.0. Raw per-frame measurements are preserved in
[PALETTE_BENCHMARK_RESULTS.csv](PALETTE_BENCHMARK_RESULTS.csv).

These measurements predate the LWJGL 3.4.2 / FFM update that removes Unsafe
startup warnings. They are retained as historical results; rerun the benchmark
before making performance claims about the updated runtime.

## Decision

Keep CPU as the default and GPU as an opt-in experiment. At 3024×1964 with AA,
GPU recoloring alone was about 9% faster, but the measured time through the
JavaFX buffer was about 17% slower. Base-only coloring clearly favored CPU.
These results do not justify default GPU palette recoloring or broader GPU
residency yet. They do establish a working, tested compute/presentation boundary.

## Reproduce

```sh
mvn -Ppalette-benchmark javafx:run
```

This opens a JavaFX window, prepares fixed Mandelbrot samples, alternates CPU
and GPU operations, checks their output, and writes `target/palette-benchmark.csv`.
A missing/failing GPU is an error, not a successful CPU-only comparison.

```sh
# Optional larger run; JAVA_TOOL_OPTIONS reaches the JavaFX child JVM.
JAVA_TOOL_OPTIONS='-Dfractal.benchmark.warmup=30 -Dfractal.benchmark.samples=120 -Dfractal.benchmark.sizes=1512x982,3024x1964' mvn -Ppalette-benchmark javafx:run

# Explicit CPU-only measurement, usable without GPU natives.
JAVA_TOOL_OPTIONS='-Dfractal.gpu.enabled=false -Dfractal.benchmark.cpuOnly=true' mvn -Ppalette-benchmark javafx:run

# Enable the experimental backend in the regular application.
JAVA_TOOL_OPTIONS=-Dfractal.gpu.palette.enabled=true mvn javafx:run
```

The recorded run used the defaults: 12 warmup pairs and 40 measured pairs per
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
- Results are one controlled run on one Mac. JVM scheduling, common-pool workers,
  window rendering, GPU power state and other desktop work affect timings.

## Steady-state results

Times are milliseconds. Median and p95 use nearest-rank percentiles over the
40 measured samples. `To buffer` includes recolor, FX queue delay and publication.

| Buffer / workload | Backend | Recolor median | Recolor p95 | Publication median | To buffer median |
| --- | --- | ---: | ---: | ---: | ---: |
| 1512×982 / base | CPU | 0.882 | 1.061 | 0.460 | 1.408 |
| 1512×982 / base | GPU | 1.547 | 1.662 | 0.501 | 2.108 |
| 1512×982 / AA | CPU | 1.310 | 3.292 | 0.373 | 1.746 |
| 1512×982 / AA | GPU | 1.911 | 2.338 | 0.363 | 2.593 |
| 3024×1964 / base | CPU | 1.054 | 1.295 | 1.218 | 2.550 |
| 3024×1964 / base | GPU | 3.502 | 4.490 | 1.073 | 4.954 |
| 3024×1964 / AA | CPU | 4.317 | 4.685 | 0.892 | 5.198 |
| 3024×1964 / AA | GPU | 3.923 | 4.794 | 0.843 | 6.089 |

GPU component medians:

| Buffer / workload | Preparation | Upload | Dispatch + wait | Readback | FX queue |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1512×982 / base | 0.014 | <0.001 | 1.225 | 0.244 | 0.052 |
| 1512×982 / AA | 0.012 | <0.001 | 1.513 | 0.266 | 0.040 |
| 3024×1964 / base | 0.011 | <0.001 | 2.181 | 1.136 | 0.805 |
| 3024×1964 / AA | 0.009 | <0.001 | 2.786 | 0.832 | 1.246 |

Component medians are independent and need not sum to the median total.
Total wall time also includes runtime locking and orchestration.
Every steady-state GPU sample uploaded **zero buffer bytes**; only the 32-byte
push-constant block changed. First-use uploads were 3.68 MB (initial base/tables),
3.34 MB (small AA), 12.62 MB (new large base), and 13.36 MB (large AA).
The initial GPU recolor took 112.9 ms including shader compilation and setup,
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

Both passed on the recorded Mac. Fresh-JVM missing-native and missing-shaderc
checks also passed. Intel Macs, Windows, non-coherent memory and validation-layer
runs are not covered by these measurements.
