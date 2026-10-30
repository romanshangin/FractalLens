# Palette recoloring: CPU / Vulkan experiment (roadmap 8.2)

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Measured 2026-09-03 on Apple M3 Pro (12 logical processors), macOS 26.6.2,
ARM64 Java 26.0.2, JavaFX 26.0.2, LWJGL 3.4.2 / FFM / MoltenVK Vulkan 1.1.350.
JavaFX reported output scale 2.0. Raw per-frame measurements are preserved in
PALETTE_BENCHMARK_RESULTS.csv (private archive: `PALETTE_BENCHMARK_RESULTS.csv`).

Current 30 warmup/120 measured pairs; private original-length 12/40:
PALETTE_BENCHMARK_LWJGL342_DEFAULT.csv (`PALETTE_BENCHMARK_LWJGL342_DEFAULT.csv`).
Original 2026-09-02 LWJGL 3.3.6:
PALETTE_BENCHMARK_LWJGL336.csv (`PALETTE_BENCHMARK_LWJGL336.csv`), private archive.
Both new runs pass per-frame comparison, no fallback/Unsafe warnings.

## Decision

CPU default/GPU opt-in. Long 3024×1964 AA: ~28% faster recolor/~16% lower
through-buffer (4.717 vs 5.633 ms), original-length ~19%/~3%. Variation limits
end-to-end precision; CPU wins other three workloads in both runs.

No global GPU promotion. Large AA benefits; residency needs separate conformance/
performance. Different warmups/scheduling/desktops prevent causal LWJGL-version claims.

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

Main: 30 warmup/120 measured plus separate `cold` pair. Pair offsets match/order
alternates; wait next FX pulse after publication. Untimed comparison/warmups excluded
from CSV. `cold` is case first use, not fresh process; later cases reuse runtime/data.

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

Component medians need not sum; total adds locking/orchestration. Steady GPU
uploads **zero buffer bytes**, only 32-byte push constants. First uploads:
3.68 MB base/tables, 3.34 MB small AA, 12.62 MB large base, 13.36 MB large AA.
Initial compilation/setup recolor 147.5 ms main/197.7 ms original-length,
materially above warm latency.

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

Portable: one-lookup CPU, 65,536 wrap/round addresses/100 seeded offsets,
invalidation/cancel/full CPU fallback. Hardware: all palettes, escape/interior,
1/4/16/32 AA, packed tails, size/scale/AA/palette changes, residency, post-dispatch
loss/reopen.

Base RGB and all alpha channels must match exactly. AA RGB permits a maximum
one-level error per channel from FP32 linear-light averaging. The interactive
benchmark checks these bounds for every compared frame.

```sh
mvn test
mvn -Pgpu-smoke test
```

Mac lifecycle/palette passes after 3.4.2 (2026-09-02); both 2026-09-03 runs
pass pixels. Portable 2026-09-03: 303/no failures-errors/two native skips.
Original fresh-JVM missing natives/shaderc pass. No Intel/Windows/noncoherent/
validation-layer coverage.
