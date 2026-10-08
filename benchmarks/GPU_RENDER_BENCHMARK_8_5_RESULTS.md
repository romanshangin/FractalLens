# GPU calculation decision gate repeat (roadmap 8.5)

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

## Decision

**The optimization substantially improves the certified hybrid backend, but the
repeated performance gate still fails. Keep CPU calculation as the default,
retain GPU calculation as opt-in, and do not start residency expansion in 8.6.**
All ten paired workload/size cases remain slower end to end on GPU. The largest
relative gap left in the primary run is the Retina overview base pass (3.32x);
the smallest is Retina Refined + AA (1.11x).

Measured 2026-09-05 on Apple M3 Pro, 12 logical processors, macOS 26.6.2,
ARM64 Java 26.0.2, JavaFX 26.0.2, LWJGL 3.4.2 / FFM, MoltenVK Vulkan 1.1.350.
The JVM heap was capped at 2 GiB. No timing run overlapped the test suite.

Raw data:

- Main uninstrumented run (private archive: `GPU_RENDER_BENCHMARK_8_5_RESULTS.csv`): eight cases,
  224 frame rows including cold and warmup observations.
- Uninstrumented Refined run (private archive: `GPU_RENDER_BENCHMARK_8_5_REFINED.csv`): two cases,
  56 frame rows using the application's default presentation mode.
- Separate component profile (private archive: `GPU_RENDER_BENCHMARK_8_5_PROFILE.csv`): six base
  cases, 108 frame rows. Timestamp queries and host-stage timers are enabled
  only in this diagnostic run.

## Implemented optimization

Certificate conversion and rejected-sample recovery now run on a bounded pool
of at most ten host workers. Two reusable primitive staging areas hold iteration,
smooth and state values; a region enters the frame only after every worker has
finished and the generation/cancellation checks pass. Cancellation interrupts
and drains the workers before a batch can be reused.

The certificate decodes the shader output and computes the candidate smooth
value once. Publication writes that staged smooth value directly into the
structure-of-arrays sample plane, avoiding a temporary `FractalSample` for every
certified pixel and the previous second smooth calculation.

The shader has an analytic interior shortcut for the main cardioid and period-2
bulb. It applies the existing outward-rounded interval operations to the original
coordinate enclosure and accepts only when the upper bound of the defining left
side is strictly below the lower bound of the right side. An interval touching
or crossing either boundary is not shortcut-certified and continues through the
original interval recurrence or exact CPU recovery. The full native boundary and
random matrix found zero false accepts.

Production regions increased from 128x128 to 192x192. This reduces Retina
submissions from 384 to 176 while keeping fixed native buffers at 2.8125 MiB,
below the existing 4 MiB ceiling. The size and worker count remain bounded and
diagnostically configurable. Conjugate-symmetry dispatch was evaluated but not
added: it only applies to selected uncached grids, complicates progressive/pan
generation accounting, and cannot change the failed continuation decision for
general workloads.

The repeated publication gate also exposed a later resize-path regression that
cloned the full Retina AA-validity mask for every base region. Preservation now
uses a compact region snapshot and an empty-mask fast path, so region size no
longer biases the CPU/GPU comparison. Refined pixels remain protected.

## End-to-end results

Each case has one cold pair, three warmup pairs and ten primary pairs. CPU/GPU
order alternates. The table excludes cold and warmup rows. P90 is nearest-rank
over ten observations. Times are milliseconds; slowdown is GPU median divided
by CPU median.

| Buffer | Workload | CPU median / P90 | GPU median / P90 | GPU slowdown |
| --- | --- | ---: | ---: | ---: |
| 1512x982 | Overview, base | 21.28 / 23.31 | 65.36 / 67.99 | 3.07x |
| 1512x982 | Exterior, base | 23.37 / 25.21 | 44.98 / 47.76 | 1.93x |
| 1512x982 | Seahorse 100x, base | 42.60 / 44.29 | 92.91 / 94.05 | 2.18x |
| 1512x982 | Overview, Fast + AA | 311.98 / 318.03 | 360.19 / 365.79 | 1.15x |
| 1512x982 | Overview, Refined + AA | 319.65 / 325.27 | 391.15 / 421.32 | 1.22x |
| 3024x1964 | Overview, base | 71.93 / 113.75 | 238.67 / 263.70 | 3.32x |
| 3024x1964 | Exterior, base | 69.94 / 73.42 | 165.06 / 169.41 | 2.36x |
| 3024x1964 | Seahorse 100x, base | 164.12 / 219.19 | 363.00 / 368.89 | 2.21x |
| 3024x1964 | Overview, Fast + AA | 1345.79 / 1373.87 | 1526.22 / 1570.30 | 1.13x |
| 3024x1964 | Overview, Refined + AA | 1378.67 / 1398.73 | 1534.89 / 1551.34 | 1.11x |

Against the 8.4 GPU medians, the three Retina base workloads improve by 59.7%,
69.6% and 67.4% respectively. Fast + AA improves by 14.3% and Refined + AA by
10.1%. These cross-run comparisons explain the optimization effect; the decision
itself uses the paired CPU/GPU rows from this run.

The analytic certificate accepts 5,649,508 of 5,939,136 Retina overview samples
(95.12%) and recovers 289,628. Exterior accepts every sample. Seahorse now
shortcut-certifies 1,868,862 known-interior samples (31.47%) and recovers the
remaining 4,070,274 exactly; its observed maximum smooth error is therefore zero.

## First publication and JavaFX work

These are PixelBuffer publication boundaries, not physical scanout latency.
FX work is a sum of coloring/update callbacks and overlaps background calculation.
Medians are milliseconds.

| Buffer | Workload | First publication CPU / GPU | FX work CPU / GPU |
| --- | --- | ---: | ---: |
| 1512x982 | Overview, base | 2.78 / 6.52 | 7.84 / 4.85 |
| 1512x982 | Exterior, base | 2.39 / 3.90 | 7.97 / 5.02 |
| 1512x982 | Seahorse 100x, base | 5.03 / 7.58 | 7.86 / 5.24 |
| 1512x982 | Overview, Fast + AA | 3.62 / 7.19 | 7.82 / 5.96 |
| 1512x982 | Overview, Refined + AA | 27.03 / 96.29 | 7.97 / 8.36 |
| 3024x1964 | Overview, base | 5.87 / 9.19 | 40.68 / 21.99 |
| 3024x1964 | Exterior, base | 4.66 / 7.51 | 42.87 / 22.48 |
| 3024x1964 | Seahorse 100x, base | 7.33 / 10.35 | 34.00 / 21.97 |
| 3024x1964 | Overview, Fast + AA | 7.80 / 12.37 | 56.08 / 36.40 |
| 3024x1964 | Overview, Refined + AA | 92.26 / 244.50 | 36.03 / 36.43 |

The larger GPU region reduces total work but delays the first base publication
by several milliseconds. Refined mode intentionally withholds base progress, so
its first publication is an AA tile and includes the slower base completion.

## Component profile

The table shows Retina GPU medians from the separate five-sample profile.
`Submit/fence` includes kernel execution. Host total contains certification,
recovery and publication; the next GPU submission overlaps these host stages,
so columns must not be added to estimate frame time.

| Workload | Pack | Submit/fence | Kernel | Host total | Certify | CPU recovery | Publish | Frame |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Overview | 43.94 | 123.50 | 66.47 | 139.08 | 74.53 | 37.15 | 27.30 | 249.35 |
| Exterior | 43.48 | 65.85 | 17.70 | 109.15 | 83.29 | 0.00 | 25.27 | 173.18 |
| Seahorse 100x | 43.87 | 193.22 | 130.19 | 251.53 | 39.75 | 185.26 | 26.79 | 368.68 |

For comparison, the 8.4 Retina overview measured 221.18 ms submit/fence,
138.81 ms kernel and 544.73 ms combined certification/recovery/publication.
The repeated figures are 123.50, 66.47 and 139.08 ms. Seahorse host work falls
from 1030.88 to 251.53 ms and kernel time from 243.15 to 130.19 ms.

## Conformance and memory

Every timed pair is checked after timing for exact escaped flag and iteration
count, finite smooth values, and absolute smooth error <= 0.01. The primary and
profile runs performed 482,554,800 per-sample comparisons with no mismatch or
fallback. Maximum observed accepted smooth error was 0.000200886. The native
matrix covers cardioid/bulb boundary neighbors, seeded random points, full Retina
overview/seahorse frames, panned grids and limits 32/64/300/1000; it reported zero
false accepts with SPIR-V SHA-256
`406c03880bb773cff6c3872bf4aee94658af33c093c22aebd1e96bf6923deb40`.

Two native mapped buffers use 2,949,120 bytes. Two Java request batches use
6,193,152 bytes of primitive payload, and their reusable staging adds 958,464
bytes; none scales with frame size. Per-pixel upload/readback volume is unchanged.
The highest primary peak-pool sum was 1,927.9 MiB on a CPU row immediately before
a collection; the highest GPU-row value was 1,674.3 MiB. These counters include
paired frames, AA caches, JavaFX and uncollected garbage and are not synchronized
RSS. The short gate found no unbounded GPU staging or native allocation.

Validation on this branch:

- `mvn clean test -Djavafx.cachedir=/tmp/fractallens-javafx-cache`: 344 tests,
  zero failures/errors; 19 opt-in native/FX tests skipped.
- Opt-in JavaFX integration: 16 tests, zero failures/errors, two fullscreen
  methods skipped.
- Combined native runtime, palette, integrated Mandelbrot and full FP32 matrix:
  five tests, zero failures/errors/skips.
- Missing-shaderc CPU fallback: two tests, zero failures/errors/skips.

## Reproduce

```sh
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.benchmark.scenes=overview,exterior,seahorse,overview-aa -Dfractal.benchmark.output=benchmarks/GPU_RENDER_BENCHMARK_8_5_RESULTS.csv' mvn -Pgpu-render-benchmark javafx:run

JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.benchmark.scenes=overview-refined -Dfractal.benchmark.output=benchmarks/GPU_RENDER_BENCHMARK_8_5_REFINED.csv' mvn -Pgpu-render-benchmark javafx:run

JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.gpu.mandelbrot.profile=true -Dfractal.benchmark.samples=5 -Dfractal.benchmark.scenes=overview,exterior,seahorse -Dfractal.benchmark.output=benchmarks/GPU_RENDER_BENCHMARK_8_5_PROFILE.csv' mvn -Pgpu-render-benchmark javafx:run

mvn clean test
mvn -Pgpu-smoke -Dfractal.gpu.fp32Native=true -Dfp32.native.full=true -Dfractal.gpu.mandelbrot.profile=true '-Dtest=GpuRuntimeNativeTest,PaletteRecolorNativeTest,GpuMandelbrotRenderBackendNativeTest,MandelbrotPrecisionNativeTest' test
```

As in 8.4, the benchmark covers the production render service, JavaFX surface
and CPU AA flow but not physical display latency, controller input, histogram,
export or long-duration memory stability. Windows and Intel/AMD Mac validation
remain separate roadmap items 8.7 and 8.8.
