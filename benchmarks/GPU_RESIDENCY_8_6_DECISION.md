# GPU residency decision gate (roadmap 8.6)

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

## Decision

**Defer GPU adaptive edge/distance detection.** First residency increment fails
M3 Pro end-to-end gate; other 8.6 work awaits improved base/repeated paired gate.

Separate detection from sampling/cache-color. Sum candidate CPU across workers;
`candidate critical path` is busiest worker's accumulated detection, an estimate
of removable wall time, not free-GPU evidence.

Measured 2026-09-05 on Apple M3 Pro, 12 logical processors, macOS 26.6.2,
ARM64 Java 26.0.2, JavaFX 26.0.2, LWJGL 3.4.2 / FFM, MoltenVK Vulkan 1.1.350.
The JVM heap was capped at 2 GiB. Each case has one cold pair, two warmup pairs
and five measured CPU/GPU pairs. CPU/GPU order alternates.

Raw data: GPU_RESIDENCY_8_6_PROFILE.csv (private archive: `GPU_RESIDENCY_8_6_PROFILE.csv`).

## Results

Median ms. `Base gap` = GPU minus CPU base. `Ideal GPU total` removes full
candidate critical path, optimistically excluding new dispatch/sync/storage/transfers.

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

Current samples return to CPU before AA. Detection adds resident dispatch,
no-false-negative distance criterion, storage/sync/exact CPU fallback transfers.
Even free ideal loses, so this gate justifies none of those costs.

Sampling/storage depend on rejected detection and expand certificates/lifetimes.
Traps/histogram/cache/pan/export are later dependencies, no bypass of this gate.

Reconsider 8.6 only after the certified GPU base pass closes the current
37-130 ms base-frame deficit on the tested workloads, then repeat an
uninstrumented paired gate before changing the default backend.

## Instrumentation

`InteractiveAntialiasService` opt-in immutable `fractal.aa.profile=true` profile:
AA wall/base preparation, summed worker CPU/busiest detection, tested/accepted
pixels/subsample count. Normal rendering takes no pixel clocks/counters.

## Reproduce

```sh
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.aa.profile=true -Dfractal.benchmark.warmup=2 -Dfractal.benchmark.samples=5 -Dfractal.benchmark.scenes=overview-aa,overview-refined -Dfractal.benchmark.output=benchmarks/GPU_RESIDENCY_8_6_PROFILE.csv' mvn -Pgpu-render-benchmark javafx:run
```
