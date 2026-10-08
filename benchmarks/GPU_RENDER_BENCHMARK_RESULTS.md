# GPU calculation decision gate (roadmap 8.4)

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../docs/development/PUBLICATION.md).

## Decision

**Conformance passes on Apple M3 Pro; the performance gate fails. Keep CPU as
the default, retain GPU calculation as an opt-in experiment, and defer GPU residency expansion (8.6).**
All ten measured workload/size combinations are slower through JavaFX with the
hybrid GPU backend. Expanding GPU residency or packaging is not justified by
these measurements. A future optimization should first repeat this gate on the
limited backend; this result is not a claim that every possible GPU design is slower.

Measured 2026-09-03 on Apple M3 Pro, 12 logical processors, macOS 26.6.2,
ARM64 Java 26.0.2, JavaFX 26.0.2, LWJGL 3.4.2 / FFM, MoltenVK Vulkan 1.1.350.
The JVM heap was capped at 2 GiB. No timing runs overlapped the test suite.

Raw data:

- Main uninstrumented run (private archive: `GPU_RENDER_BENCHMARK_RESULTS.csv`): eight cases,
  224 frame rows, including warmup and first-use observations.
- Uninstrumented Refined run (private archive: `GPU_RENDER_BENCHMARK_REFINED.csv`): two cases,
  56 frame rows. This runs the application's default presentation mode.
- Separate component profiling run (private archive: `GPU_RENDER_BENCHMARK_PROFILE.csv`): six
  base-pass cases, 108 frame rows. Timestamp queries are enabled only here.

## End-to-end results

Each primary case has one first-use pair, three warmup pairs and ten measured
pairs. CPU/GPU order alternates; every pair uses the same scene and iteration
budget. Tables exclude first-use and warmup rows. P90 uses the nearest-rank
method on ten observations, so it is descriptive rather than a tail guarantee.
Times are milliseconds. Slowdown is GPU median / CPU median.

| Buffer | Workload | CPU median / P90 | GPU median / P90 | GPU slowdown |
| --- | --- | ---: | ---: | ---: |
| 1512×982 | Overview, base | 15.26 / 18.17 | 148.23 / 156.29 | 9.71× |
| 1512×982 | Exterior, base | 17.33 / 20.16 | 133.62 / 138.63 | 7.71× |
| 1512×982 | Seahorse 100×, base | 33.45 / 35.12 | 275.98 / 298.42 | 8.25× |
| 1512×982 | Overview, Fast + AA | 246.16 / 252.48 | 388.89 / 403.75 | 1.58× |
| 1512×982 | Overview, Refined + AA | 246.35 / 251.56 | 398.25 / 409.04 | 1.62× |
| 3024×1964 | Overview, base | 52.56 / 54.28 | 592.52 / 607.80 | 11.27× |
| 3024×1964 | Exterior, base | 57.70 / 59.12 | 542.53 / 564.08 | 9.40× |
| 3024×1964 | Seahorse 100×, base | 132.87 / 138.32 | 1113.38 / 1120.57 | 8.38× |
| 3024×1964 | Overview, Fast + AA | 1250.24 / 1443.89 | 1780.47 / 1891.29 | 1.42× |
| 3024×1964 | Overview, Refined + AA | 1175.21 / 1190.39 | 1708.19 / 1717.97 | 1.45× |

The Retina overview accepts 5,324,206 samples (89.65%) and recovers 614,930.
The exterior accepts all 5,939,136 samples but remains slower. Seahorse recovers
every sample: speculative GPU calculation adds cost without replacing CPU work.

## First publication and presentation cost

These are JavaFX **PixelBuffer publication** boundaries, not photon/scanout
measurements. Base rows use Fast preview. Fast + AA publishes base regions first;
Refined + AA withholds base progress and publishes the first completed AA tile.
FX cost sums CPU coloring and buffer-update callbacks (plus AA tile application
and final promotion); it overlaps background calculation and is not an additive
end-to-end component. First regions retain production tile sizes (CPU 32×32,
GPU up to 128×128); this is not an equal-area throughput comparison. Medians, milliseconds:

| Buffer | Workload | First publication CPU / GPU | FX work CPU / GPU |
| --- | --- | ---: | ---: |
| 1512×982 | Overview, base | 3.16 / 5.63 | 4.98 / 4.25 |
| 1512×982 | Exterior, base | 1.56 / 4.74 | 5.31 / 4.08 |
| 1512×982 | Seahorse 100×, base | 4.11 / 9.29 | 5.30 / 4.21 |
| 1512×982 | Overview, Fast + AA | 1.11 / 5.28 | 6.94 / 6.04 |
| 1512×982 | Overview, Refined + AA | 18.86 / 168.48 | 6.35 / 6.47 |
| 3024×1964 | Overview, base | 3.66 / 6.44 | 24.80 / 16.73 |
| 3024×1964 | Exterior, base | 4.60 / 7.56 | 27.48 / 18.05 |
| 3024×1964 | Seahorse 100×, base | 6.60 / 9.27 | 24.14 / 16.68 |
| 3024×1964 | Overview, Fast + AA | 17.24 / 17.85 | 48.90 / 35.94 |
| 3024×1964 | Overview, Refined + AA | 76.11 / 603.57 | 33.32 / 33.46 |

JavaFX reports output scale 2.0. The 1512×982 and 3024×1964 physical buffers
are published through `FractalSurface`, with the window fitted inside the screen.
The larger image can be scaled to fit; this is not full-screen 1:1 scanout testing.
The session emitted `CVDisplayLink... error: -6661`; buffer updates and JavaFX
pulses completed, but hardware-vsync behavior is not validated. `next_pulse_ms`
is only the interval to a subsequent AnimationTimer callback, recorded separately
and excluded from frame time. Physical display latency remains unmeasured.

## Component costs

Separate instrumented run: one first-use pair, three warmups, five measured
pairs per case. The production path normally creates no query pool and collects
none of these component counters. The table shows Retina GPU medians in ms;
full-size and smaller raw rows are in the profile CSV.

| Workload | Pack | Upload | Submit/fence | Readback | GPU kernel | Certify/recover/publish | Frame |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Overview, base | 35.32 | 4.69 | 221.18 | 7.64 | 138.81 | 544.73 | 643.12 |
| Exterior, base | 33.40 | 3.89 | 94.65 | 6.50 | 20.20 | 515.57 | 557.89 |
| Seahorse 100×, base | 34.42 | 4.11 | 354.33 | 7.43 | 243.15 | 1030.88 | 1151.52 |

The coordinator's serial certification, sample conversion, CPU recovery and
sample-plane writes dominate even when all exterior samples are certified.
`recover_publish_ms` measures that entire host region, including enqueueing
progress, not just recomputed pixels. GPU kernel time alone also exceeds the
CPU overview base-frame median. This points to host certification and the
interval kernel as costs to investigate before adding features.

Kernel time uses two Vulkan timestamp queries around dispatch, with the selected
queue's `timestampValidBits` and the device's `timestampPeriod`. A missing timestamp
capability is represented by an empty CSV field, not zero. See the official
[Vulkan timestamp example](https://docs.vulkan.org/samples/latest/samples/api/timestamp_queries/README.html).
`dispatch_ms` is host encode/submit/fence/query-read wall time and includes kernel
execution. Upload is the array-to-mapped-buffer copy and any required flush;
readback includes invalidation and the mapped-buffer-to-array copy. The next
submission overlaps host recovery of the preceding batch, so component sums
must not be added together. Profiling changes timings: use the uninstrumented
run for the performance decision.

## Memory and transfer volume

Native input/output allocations measured 1,310,720 bytes (1.25 MiB) total on
M3 Pro, within the existing 4 MiB buffer-allocation ceiling. The optional query
pool, pipeline, driver/Metal allocations and JavaFX textures are not included
in that number. Native buffers are fixed-size, not full-frame allocations.
Two Java request batches contain 2,752,512 bytes (2.625 MiB) of primitive array
payload, plus headers and up to 16,384 staged sample objects/references for a
region. Full-frame sample storage remains CPU-resident: its four primitive
planes require 21 bytes/pixel before headers, validity masks and presentation
buffers (about 118.95 MiB for 3024×1964).

A Retina full frame dispatches 384 batches. The request/readback payload is
32 + 48 bytes per pixel: 190,052,352 bytes uploaded and 285,078,528 bytes read
back (453.12 MiB combined). Smaller frames dispatch 96 batches. These are host
copy volumes on unified memory, not measurements of a discrete-GPU bus.

CSV rows record heap used before/after each frame, the sum of heap-pool peak
usage after resetting their counters, and GC count/time changes. Peak sums
are upper bounds across memory pools, not synchronized process RSS peaks.
They include the retained display frame, the paired oracle frame, AA caches,
garbage awaiting collection and JavaFX; they cannot establish incremental GPU
memory overhead. The highest primary measured peak-pool sum was 1651.3 MiB.
GC pauses are included in frame latency. No process/driver-memory leak or
long-duration memory-stability claim is made by this short gate.

## Conformance and selection transitions

The benchmark compares every base sample after both timed frames complete:
exact escaped flag and iteration count, finite smooth values, absolute smooth
error <= 0.01. This is outside all timing intervals. Main and Refined runs
performed 519,674,400 sample comparisons (including repeated warmup coordinates);
the profile run adds 200,445,840. No mismatch or silent CPU fallback occurred.
The largest observed smooth error was 0.000178569. AA executes the existing
CPU refinement service; the contract does not require bit-identical final RGB
for arbitrary palettes or identical AA candidate masks.

The native integration test also crosses 999 → 1000 → 1001 → 1000 iterations,
requiring CPU fallback only at 1001. It brackets two nearby actual-grid scales
with opposite coordinate-gate results, renders allowed → rejected → allowed,
and checks the original-coordinate CPU oracle and runtime availability.
At 129×65 around the seahorse center, the recorded rejected scale was
3.0520265039272856e-5 and the allowed scale was 3.0520265039308384e-5.
These are observed grid-specific values, not a global scale threshold.
The existing tests cover pan/cache accuracy separation, unsupported modes,
device loss, missing shaderc and cancellation before/after submission.
The separate 34-case shader conformance matrix remains in
[GPU_FP32_NATIVE.md](GPU_FP32_NATIVE.md).

Validation on the recorded revision:

- `mvn clean test`: 317 tests, zero failures/errors, five opt-in native tests skipped.
- Combined native run with profiling and the full precision matrix: five tests,
  zero failures/errors/skips. Every one of the 34 matrix rows matched the saved
  acceptance counts and errors; shader SHA-256 remained
  `f70d438deab62fb9c64bb62dd33ea50aeccb3cda35bc0c35a10569dd28eed8ea`.
- Missing-shaderc run: exact CPU recovery passed. The separate selection-boundary
  method intentionally returns in this unavailable-runtime configuration.

## Reproduce and scope

```sh
# Main run (the archived initial run predates addition of overview-refined).
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.benchmark.scenes=overview,exterior,seahorse,overview-aa -Dfractal.benchmark.output=benchmarks/GPU_RENDER_BENCHMARK_RESULTS.csv' mvn -Pgpu-render-benchmark javafx:run

# Default Refined presentation mode, same primary sample counts.
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.benchmark.scenes=overview-refined -Dfractal.benchmark.output=benchmarks/GPU_RENDER_BENCHMARK_REFINED.csv' mvn -Pgpu-render-benchmark javafx:run

# Separate diagnostic run; not the basis for the primary latency table.
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true -Dfractal.gpu.mandelbrot.profile=true -Dfractal.benchmark.samples=5 -Dfractal.benchmark.scenes=overview,exterior,seahorse -Dfractal.benchmark.output=benchmarks/GPU_RENDER_BENCHMARK_PROFILE.csv' mvn -Pgpu-render-benchmark javafx:run

mvn clean test
mvn -Pgpu-smoke -Dfractal.gpu.fp32Native=true -Dfp32.native.full=true -Dfractal.gpu.mandelbrot.profile=true '-Dtest=GpuRuntimeNativeTest,PaletteRecolorNativeTest,GpuMandelbrotRenderBackendNativeTest,MandelbrotPrecisionNativeTest' test
mvn -Pgpu-smoke -Dtest=GpuMandelbrotRenderBackendNativeTest -Dfractal.gpu.expectUnavailable=true -Dorg.lwjgl.shaderc.libname=/private/tmp/fractallens-missing-shaderc.dylib test
```

The benchmark defaults to both sizes, all five workloads, three warmup pairs
and ten measured pairs. `fractal.benchmark.sizes`, `.scenes`, `.warmup`, `.samples`
and `.output` can override them. A missing GPU or fallback is a benchmark failure.
Each render uses a new frame and empty AA reuse state. CPU keeps its production
parallelism, symmetry and interior shortcuts; GPU keeps its 128×128 batches,
interval certificate, bounded readback and original-coordinate CPU recovery.
The stopwatch starts before frame allocation and FX staging, and ends after
base promotion or final AA publication. Runtime device initialization occurs
before timing; first GPU use includes shader/pipeline initialization. Other
`cold` rows mean first use of a workload, not a newly created device or JVM.

This isolates the production render service/surface and AA flow, not the entire
controller/event loop: input dispatch, preview transforms, histogram, export,
frame-cache lookup and pan reuse are outside this timing experiment. Pan and
fallback correctness are tested separately. Windows and Intel/AMD Mac remain
separate roadmap stages 8.7 and 8.8; no M3 Pro result is generalized to them.
