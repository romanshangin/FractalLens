# GPU calculation decision gate (roadmap 8.4)

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

## Decision

**M3 Pro conformance passes, performance fails: CPU default, GPU opt-in, 8.6
residency deferred.** All ten JavaFX cases lose; no residency/packaging expansion
justified. Optimize limited backend/repeat gate first. Other GPU designs are not
proven universally slower.

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

**PixelBuffer publication**, not photons/scanout. Fast base/AA shows base first;
Refined withholds until AA. FX sums coloring/updates/AA application/promotion,
overlapping background, never additive. Production first regions differ (CPU
32×32, GPU up to 128×128), not equal-area throughput. Median milliseconds:

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

Output scale 2.0; `FractalSurface` publishes 1512×982/3024×1964 buffers in
screen-fitted window, possibly scaled, not fullscreen 1:1 scanout.
`CVDisplayLink... error: -6661` occurred; pulses/updates completed, hardware vsync
unverified. Separate `next_pulse_ms` ends at AnimationTimer, excluded from frame
latency; physical display unmeasured.

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

Serial host certification/conversion/recovery/plane writes dominate even certified
exterior. `recover_publish_ms` includes all host work/progress enqueue, not just
rejected pixels. Kernel alone exceeds CPU overview; investigate host/interval
costs before features.

Two dispatch-bracketing queries use queue `timestampValidBits`/`timestampPeriod`;
unavailable → empty CSV, never zero.
[Vulkan example](https://docs.vulkan.org/samples/latest/samples/api/timestamp_queries/README.html).
`dispatch_ms` includes host encode/submit/fence/query-read and kernel. Upload:
array→mapped/flush; readback: invalidate/mapped→array. Overlap with prior recovery
makes sums nonadditive. Use separate uninstrumented decision run.

## Memory and transfer volume

Fixed native I/O: 1,310,720 bytes (1.25 MiB), below 4 MiB; excludes queries/
pipeline/driver-Metal/FX textures. Java two batches: 2,752,512 bytes (2.625 MiB)
plus headers/up to 16,384 staged objects/references. CPU-resident four sample
planes: 21 bytes/pixel plus headers/masks/display, ~118.95 MiB at 3024×1964.

A Retina full frame dispatches 384 batches. The request/readback payload is
32 + 48 bytes per pixel: 190,052,352 bytes uploaded and 285,078,528 bytes read
back (453.12 MiB combined). Smaller frames dispatch 96 batches. These are host
copy volumes on unified memory, not measurements of a discrete-GPU bus.

CSV heap-before/after, reset pool-peak sums/GC counts-time include retained/oracle
frames, AA/FX/garbage. Pool peaks are nonsynchronized upper bounds, not RSS or
incremental GPU overhead. Primary max 1651.3 MiB; pauses included in latency.
Short gate claims no leak/sustained stability result.

## Conformance and selection transitions

Untimed full base comparison: exact escape/iterations, finite smooth/error <=0.01.
Main/Refined 519,674,400 samples (warmup repeats included), profile 200,445,840;
no mismatch/silent fallback, max smooth 0.000178569. AA uses CPU; arbitrary-palette
RGB/AA candidate-mask equality is not required.

Native transitions 999 →1000 →1001 →1000 require fallback only at 1001.
Allowed/rejected/allowed actual grids check original-coordinate oracle/runtime.
Seahorse 129×65: rejected 3.0520265039272856e-5, allowed 3.0520265039308384e-5;
grid observations, not global scale limits. Other tests cover accuracy-isolated
pan/cache, unsupported modes/loss/missing shaderc/pre-post submit cancel.
[34-case shader matrix](GPU_FP32_NATIVE.md).

Validation on the recorded revision:

- `mvn clean test`: 317 tests, no failures/errors, five opt-in native tests skipped.
- Combined native run with profiling and the full precision matrix: five tests, no
  failures/errors/skips. Every one of the 34 matrix rows matched the saved acceptance
  counts and errors; shader SHA-256 remained
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

Defaults: both sizes/five workloads, three warmup/ten measured pairs. Override
`fractal.benchmark.sizes`, `.scenes`, `.warmup`, `.samples`, `.output`. No GPU/fallback
fails benchmark. New frame/empty AA each render; CPU production parallelism/
symmetry/interior, GPU 128×128/interval/bounded readback/original-coordinate recovery.
Time before allocation/FX stage →base promotion/final AA. Device initialized
before timing; first GPU includes shader/pipeline. Other `cold` means first workload,
not new device/JVM.

Production service/surface/AA only, excluding controller/input/preview/histogram/
export/frame-cache lookup/pan. Pan/fallback correctness separate. Windows/Intel-AMD
8.7/8.8 gates remain; M3 Pro results never generalize.
