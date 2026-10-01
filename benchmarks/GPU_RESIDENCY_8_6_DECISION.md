# GPU residency decision gate (roadmap 8.6)

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../PUBLICATION.md).

## Decision

**Do not move adaptive edge/distance candidate detection onto the GPU yet.**
The first proposed residency increment does not pass an end-to-end performance
gate on Apple M3 Pro. The remaining 8.6 increments stay deferred behind a future
base-pass improvement and a repeated paired benchmark.

The diagnostic run measured candidate detection independently from subpixel
sampling and cache/color work. Candidate CPU time is summed across workers;
`candidate critical path` is the cumulative candidate time on the busiest AA
worker and estimates the largest directly removable part of the observed wall
time without claiming that a GPU implementation is free.

Measured 2026-09-05 on Apple M3 Pro, 12 logical processors, macOS 26.6.2,
ARM64 Java 26.0.2, JavaFX 26.0.2, LWJGL 3.4.2 / FFM, MoltenVK Vulkan 1.1.350.
The JVM heap was capped at 2 GiB. Each case has one cold pair, two warmup pairs
and five measured CPU/GPU pairs. CPU/GPU order alternates.

Raw data: GPU_RESIDENCY_8_6_PROFILE.csv (private archive: `GPU_RESIDENCY_8_6_PROFILE.csv`).

## Results

Medians are milliseconds. `Base gap` is GPU base-frame time minus CPU
base-frame time. `Ideal GPU total` subtracts the complete observed GPU candidate
critical path from GPU end-to-end time. This deliberately optimistic bound
ignores every new GPU dispatch, synchronization, storage and transfer cost.

| Buffer | Mode | CPU total | GPU total | Base gap | Candidate critical path CPU / GPU | Ideal GPU total | Ideal result |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1512x982 | Fast + AA | 274.88 | 309.00 | 37.26 | 18.72 / 17.09 | 291.91 | 1.06x slower |
| 1512x982 | Refined + AA | 270.99 | 308.76 | 40.19 | 18.97 / 17.92 | 290.84 | 1.07x slower |
| 3024x1964 | Fast + AA | 1151.90 | 1257.16 | 117.50 | 72.33 / 72.61 | 1184.54 | 1.03x slower |
| 3024x1964 | Refined + AA | 1156.28 | 1259.05 | 130.25 | 72.98 / 73.71 | 1185.34 | 1.03x slower |

The candidate set is sparse: 52,858 of 1,484,784 pixels (3.56%) at 1512x982
and 164,354 of 5,939,136 pixels (2.77%) at 3024x1964. The regular 4x4 pass
therefore calculates 845,728 and 2,629,664 subpixel samples respectively.
Moving detection alone cannot remove the larger subpixel sampling and
palette-independent cache/color work.

The paired output gate remained exact for escaped classification and iteration
count, with maximum accepted smooth error 0.000200886 and no CPU fallback.

## Why expansion stops here

The current GPU backend reads the certified base samples back to the CPU before
AA. GPU detection would require at least another dispatch over resident base
data, a conservative distance criterion with no false negatives, candidate
storage, synchronization and an exact CPU fallback transfer. The idealized
zero-cost result above already loses, so implementing those costs cannot be
justified by this gate.

Subpixel sampling and AA storage depend on the rejected first increment and
would expand both the numeric certificate and resource lifetime substantially.
Orbit traps, histogram reduction, frame caching, pan reuse and export are later
dependencies in the roadmap and are not candidates for skipping this gate.

Reconsider 8.6 only after the certified GPU base pass closes the current
37-130 ms base-frame deficit on the tested workloads, then repeat an
uninstrumented paired gate before changing the default backend.

## Instrumentation

`InteractiveAntialiasService` exposes an opt-in immutable profile when
`fractal.aa.profile=true`. Production runs do not take per-pixel timestamps or
update profile counters. The benchmark CSV records AA wall time, base-color
preparation, summed worker CPU times, busiest-worker candidate time, tested and
accepted pixel counts, and subpixel sample count.

## Reproduce

```sh
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.aa.profile=true -Dfractal.benchmark.warmup=2 -Dfractal.benchmark.samples=5 -Dfractal.benchmark.scenes=overview-aa,overview-refined -Dfractal.benchmark.output=benchmarks/GPU_RESIDENCY_8_6_PROFILE.csv' mvn -Pgpu-render-benchmark javafx:run
```

