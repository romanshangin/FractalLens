# GPU runtime boundary

The macOS runtime now loads MoltenVK, creates a headless Vulkan instance,
enumerates physical devices and numeric/compute features, selects a GPU, and
owns a logical device and compute queue. A ready runtime reports `AVAILABLE`.
CPU calculation remains the default. An opt-in Mandelbrot base pass now certifies
FP32 samples and recovers rejected pixels on CPU. The optional Vulkan palette kernel
recolors compact base and AA smooth phases and returns ARGB pixels through the
existing JavaFX buffer. CPU recoloring remains the default: measured GPU gains
depend on AA workload and transfer/presentation cost.

## Platform and numeric requirements

| Platform | Native runtime | Validation status |
| --- | --- | --- |
| macOS Apple Silicon, ARM64 JVM | LWJGL 3.4.2 + bundled ARM64 MoltenVK | Tested on Apple M3 Pro, macOS 26.6.2 |
| macOS Intel/AMD, x64 JVM | LWJGL 3.4.2 + bundled x64 MoltenVK | Packaging configured; hardware validation pending |
| Windows 10/11 | Native Vulkan through LWJGL (planned) | Runtime disabled; CPU remains available |
| Other OS/architectures | None | CPU only |

The macOS runtime requires Vulkan 1.1 and a hardware GPU with a compute queue.
It prefers discrete, then integrated GPUs; software Vulkan CPU devices are not
selected. The CPU backends remain usable if native initialization fails.

| Mode | GPU requirement | Current status / matching CPU path |
| --- | --- | --- |
| Palette recoloring (8.2) | FP32 for AA; 64-wide workgroup, four storage buffers, 32-byte push constants | Implemented and tested on M3 Pro; opt-in, parallel CPU fallback |
| Limited base-pass spike (8.3) | FP32 certificate + CPU recovery; two storage buffers, 16-byte push constants, 64-wide groups | Integrated and opt-in; Mandelbrot smooth coloring, 1–1000 iterations; precision-selecting CPU fallback |
| Direct-double equivalent arithmetic | Explicit `shaderFloat64` support, enabled at device creation | Direct-double CPU; feature support alone is not a conformance guarantee |
| Deep zoom | A separate precision-preserving implementation | CPU Mandelbrot perturbation; no GPU deep-zoom mode |

Reports include all enumerated devices and the selected device, Vulkan version,
vendor/device IDs, queue-family index, maximum workgroup invocations, and maximum
storage-buffer size. Only the **selected** device's capabilities gate execution.
`FLOAT32` denotes the Vulkan core arithmetic capability; `FLOAT64` is reported
only when the queried `shaderFloat64` feature is supported and is enabled on the
logical device. Exact kernel dimensions and precision must still be checked by
each future backend.

## Ownership and fallback

`FractalRenderService` owns `GpuRuntime`; neither the controller nor JavaFX uses
Vulkan handles. Native calls are confined to `MacosMoltenVkSession`,
`VulkanPaletteKernel`, `VulkanMandelbrotKernel` and `VulkanLibraryLease`. The loader is reference-counted so closing one runtime
cannot invalidate another. An externally initialized loader is borrowed.
Initialization rolls back partially created native resources. Normal shutdown
waits for the device, destroys the logical device and instance, then releases
the loader. A known lost device is destroyed without waiting for it to recover.

`runOrFallback(required, gpuWork, cpuWork)` serializes native work against
shutdown. An unavailable runtime or insufficient numeric capability selects the
CPU operation. A typed `GpuException` disables native work and cleans up before
retrying on CPU; a device-loss error latches `DEVICE_LOST`. There is no automatic
retry of a lost device. Cancellation and programming errors propagate.

The caller supplies the CPU operation matching its task: palette recoloring or
the existing `PrecisionSelectingRenderBackend` for calculation. GPU work must
complete all native accesses before returning/throwing, avoid publishing partial
results that make retry unsafe, and report native failures as `GpuException`.
It must not re-enter lifecycle/health/execution methods. Palette work is synchronous
on the recolor worker, with one submission in flight. Cancellation before submission
aborts immediately; cancellation after submission drains the fence before resources
can be reused or destroyed. The controller rejects stale epochs at publication.
Calculation uses this synchronous native boundary on a dedicated worker;
readback is asynchronous to the coordinator and overlaps CPU recovery of the
preceding batch. No submission outlives its runtime lease.

## Mandelbrot base-pass integration

```sh
JAVA_TOOL_OPTIONS=-Dfractal.gpu.mandelbrot.enabled=true mvn javafx:run
```

The default `FractalRenderService` wraps the existing precision-selecting CPU
backend with `GpuMandelbrotRenderBackend` only when this property is enabled.
Explicitly injected backends keep their policy. GPU palette recoloring remains
independently controlled by `fractal.gpu.palette.enabled`.

GPU calculation requires Mandelbrot without orbit traps, 1–1000 iterations,
adequate double-grid precision and `RenderJob.sampleAccuracy() == CERTIFIED_FP32`.
The controller permits this accuracy for opted-in smooth coloring; histogram
coloring requests `CPU_REFERENCE`. Legacy/default jobs and off-screen exports
retain CPU-reference accuracy. Frame cache and pan reuse require matching accuracy,
preventing tolerant GPU results from entering a CPU-reference request. The actual
inherited pan grid is used for certification and recovery.

The grid must pass the 1/16-pixel error and distinct-neighbor checks. Unsupported
jobs retain the existing CPU selection, including deep-zoom perturbation.
Accepted samples need the interval certificate in `GPU_FP32_NATIVE.md`; rejected
samples are recomputed with the original CPU-double coordinates. Raw uncertified
values are never marked ready or admitted into caches.

Regions are 192x192 in the shared priority/zoom-out order. Ready pan overlap is
skipped. Two reusable Java batches, one GPU worker with one queue slot, and at
most ten host workers bound asynchronous work. The next readback can run while
the host pool certifies and recovers the current region. Native work is serialized
with palette dispatch and shutdown by `ManagedGpuRuntime`. The kernel uses two
fixed mapped buffers (2.8125 MiB logical storage, 4 MiB allocation ceiling);
Java request arrays use about 5.91 MiB and reusable primitive staging adds about
0.91 MiB. Cancelled native and host work is drained before its batch can be
reused, and no partial region is published.

All missing samples in a region are staged before publication. Cancellation
checks guard preparation, submission/readback, CPU recovery and progress. The
render service independently rejects stale generation callbacks. Native failures
clean up the runtime and send the original frame's remaining pixels to CPU;
previously certified regions and reused overlap remain valid. No failed-batch
sample is published, and lost devices are not retried.

CPU coloring, AA sampling/cache, histogram mapping and export remain in their
existing services. Certification does not imply bit-identical colors to a full
CPU render for arbitrary palettes. The palette kernel was checked against CPU
recoloring of the same certified/recovered samples. Roadmap 8.5 records the
optimized repeat of the numeric/performance gate on M3 Pro. Retina base overview
improves from 592.52 to 238.67 ms on GPU, but remains behind its paired 71.93 ms
CPU baseline; Refined + AA is 1534.89 versus 1378.67 ms. CPU remains the default
and GPU residency expansion is still deferred. See
[GPU_RENDER_BENCHMARK_8_5_RESULTS.md](GPU_RENDER_BENCHMARK_8_5_RESULTS.md) for
raw runs, host/kernel profiles, memory accounting and display limitations; the
original 8.4 result remains archived separately.

`GpuMandelbrotRenderBackend.lastStats()` reports dispatched/certified/recovered
pixel counts and job-level CPU fallback. Region timings include host wait and
recovery; they are not GPU timestamp measurements.

For opt-in diagnostic runs, `fractal.gpu.mandelbrot.profile=true` enables bounded
Vulkan timestamp queries and `GpuMandelbrotRenderBackend.lastProfile()` component
sums, including separate certification, recovery and publication wall times.
The runtime owns and destroys the query pool with the kernel. Unsupported queue
timestamps return -1, not a zero-time kernel. Upload, dispatch/fence and readback
are host timings; the kernel query is device time. Host work overlaps native work,
so those sums are not additive. Normal rendering does not allocate a query pool.
`fractal.gpu.mandelbrot.hostWorkers` and `.regionSize` allow bounded diagnostic
tuning (defaults 10-or-processors-minus-2 and 192; region bounds are 32..192).
`mvn -Pgpu-render-benchmark javafx:run` runs paired production CPU/GPU pipelines
with full-frame numeric checks; see the report for options.

## Palette recoloring integration

`PaletteRecolorBackend` is owned through `FractalRenderService`, so the
controller and JavaFX surface still see neither Vulkan handles nor native
resources. The CPU baseline updates one thread-local `SmoothColorLookup`, then
runs the existing parallel base and AA loops. It does not copy a palette table or
allocate GPU requests. Non-smooth modes retain their current CPU coloring path.

Enable the experimental kernel in the application:

```sh
JAVA_TOOL_OPTIONS=-Dfractal.gpu.palette.enabled=true mvn javafx:run
```

The GPU path supports smooth `GradientPalette` coloring, including edited stops.
One immutable base generation, one immutable AA snapshot, and the offset-independent
palette remain resident. Phase pairs are packed into uint words; escape flags use
bitsets. Updating AA uploads only its changed snapshot; changing palette uploads
only the table; changing color scale or frame invalidates the base generation.
Stable offset frames upload zero buffer bytes and change only 32 bytes of push
constants. No 16-bit storage extension, FP64 or per-frame LUT rebuild is required.

`PaletteOffset` maps the triangular palette wave using integer phase arithmetic
and rounding thresholds computed with the CPU expression. Base colors and alpha
match exactly; FP32 linear-light AA accumulation is checked within one RGB channel
level (0–255). Unsupported coloring/offsets use CPU. Histogram/orbit-trap coloring
and fractal calculation are not moved to the GPU.

Four persistently mapped storage buffers use host-visible memory, preferring
coherent memory. The fallback heap path flushes host writes and invalidates host
reads. Dispatches use a base-to-AA write barrier and a compute-to-host read
barrier plus a fence, following the
[Khronos compute-to-host synchronization example](https://docs.vulkan.org/guide/latest/synchronization_examples.html).
On Apple Silicon, upload/readback are copies into/out of shared mapped memory,
not discrete PCIe transfer measurements. Residency is bounded by
`fractal.gpu.palette.maxBytes` (256 MiB by default), storage and dispatch limits.
Allocation, compilation and native-library failures disable this runtime's GPU
path and retry the whole operation on CPU; a new runtime is needed to retry GPU.

`PaletteRecolorTiming` separates preparation (including lookup/setup), buffer
upload, dispatch, readback, FX queue delay and publication. Dispatch is host wall
time for command encoding, submission and fence wait, not a GPU timestamp query.
Total recolor time includes all orchestration and fallback overhead. Publication
measures the shared `SurfaceBuffer.publish` array copy and PixelBuffer update;
it does not claim to measure display scanout. See `PALETTE_BENCHMARK_RESULTS.md`
for the reproducible JavaFX benchmark and its limitations.

## Packaging and diagnostics

Java binding JARs are common dependencies; native JARs are selected only by the
macOS Maven profile. The classifier follows the JVM architecture: `aarch64` and
`arm64` use `natives-macos-arm64`, while an x64 JVM uses `natives-macos` (including
under Rosetta). LWJGL core, Vulkan/MoltenVK and shaderc natives are included.
The GLSL resource is compiled to SPIR-V once per runtime on first palette use.
The JavaFX launcher resolves the native resource modules and
enables native access for LWJGL. Disabling `gpu-macos` leaves the CPU build usable.

On the project's Java 25+ baseline, the loader selects LWJGL's `ffm` memory
backend before `VK`/`MemoryUtil` initialization. This uses `java.lang.foreign`
instead of deprecated `sun.misc.Unsafe`, removing the JDK 24+ startup warning.
The setting is applied in code, so it also covers IDE/headless launches; an
explicit `org.lwjgl.system.memoryBackend` override is respected. See
[LWJGL FFM support](https://github.com/LWJGL/lwjgl3/blob/3.4.2/doc/FFM.md).
The test JVM runs with `--sun-misc-unsafe-memory-access=deny`, including the
native GPU gate, so an Unsafe regression fails instead of being hidden.

LWJGL supports a bundled MoltenVK implementation or an explicit
`-Dorg.lwjgl.vulkan.libname=/absolute/path/to/libvulkan.1.dylib` override for a
separately installed Vulkan SDK/validation-layer setup. See the
[LWJGL loader documentation](https://javadoc.lwjgl.org/org/lwjgl/vulkan/VK.html).
The runtime enables
[portability enumeration](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_portability_enumeration.html)
when advertised and enables the mandatory
[portability subset](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_portability_subset.html)
on the selected device. It uses Vulkan 1.1 to satisfy that extension's dependency.

Headless diagnostics through the application's modular launcher:

```sh
mvn javafx:run -Pgpu-diagnostics
```

The runtime can be disabled with the JVM property `-Dfractal.gpu.enabled=false`.
When using the JavaFX Maven launcher, JVM properties must reach its child JVM;
for example `JAVA_TOOL_OPTIONS=-Dfractal.gpu.enabled=false mvn javafx:run`.

## Validation

Run the portable suite (native initialization disabled; the hardware gate skips):

```sh
mvn clean test
```

Run the required hardware gate on a Mac with access to Metal:

```sh
mvn -Pgpu-smoke test
```

This gate fails, rather than skips, when no GPU is available. It verifies two
simultaneous runtimes, independent teardown, complete teardown/reopen, the
simulated device-loss path, and CPU rendering at ordinary and deep coordinates.
It also checks integrated Mandelbrot dispatch, CPU recovery, pan reuse, shared
palette ownership, simulated loss, reopen and default-service opt-in selection.
It runs actual palette dispatches across all presets and offset wrap
boundaries, checks exact base output / one-level AA tolerance, zero repeated
uploads, AA/scale/size/palette invalidation, cleanup, and reopening after dispatch.

Run failure-path checks in fresh JVMs:

```sh
mvn -Pgpu-smoke -Dfractal.gpu.expectUnavailable=true -Dorg.lwjgl.vulkan.libname=/private/tmp/fractalui-missing-moltenvk.dylib test
mvn '-P!gpu-macos,gpu-smoke' -Dfractal.gpu.expectUnavailable=true test
mvn -Pgpu-smoke -Dtest=PaletteRecolorNativeTest -Dfractal.gpu.expectPaletteUnavailable=true -Dorg.lwjgl.shaderc.libname=/private/tmp/fractalui-missing-shaderc.dylib test
```

The first two must report `UNAVAILABLE` and finish ordinary/direct and deep/perturbation
CPU rendering. The third initializes Vulkan, fails palette kernel loading, then
checks exact CPU palette fallback and resource cleanup. No installed library is modified.

Original 3.3.6 validation on 2026-09-02: Apple M3 Pro, macOS 26.6.2, ARM64 JVM; Vulkan 1.2.296,
compute queue family 3, FP32 available, FP64 absent, 1024 maximum workgroup
invocations, 4,294,967,295-byte maximum storage-buffer range. Hardware lifecycle,
modular launcher diagnostics, missing-MoltenVK and missing-native tests passed.
The sandbox hid Metal; the hardware gate passed outside it. Device loss was
injected at the runtime boundary, not induced on the physical GPU. Palette dispatch,
output conformance and the JavaFX benchmark were also validated on this Mac.
Intel/AMD Mac validation is tracked in roadmap 8.8 and Windows runtime work in
8.7. Non-coherent heaps and Vulkan validation-layer diagnostics also remain
unvalidated.

After the LWJGL 3.4.2/FFM update on the same date, the bundled MoltenVK reports
Vulkan 1.1.350 on this Mac. The portable suite, native lifecycle/palette gate
with Unsafe denied, and modular startup/shutdown diagnostics passed without
Unsafe warnings.

The palette benchmark was repeated on 2026-09-03 with LWJGL 3.4.2 / FFM: an
original-length 12/40 run and a 30/120 confirmation run both passed per-frame
conformance without GPU fallback or Unsafe warnings. GPU won only the large-AA
workload (3% and 16% lower time through the JavaFX buffer, respectively); CPU
remains the default. `PALETTE_BENCHMARK_RESULTS.md` reports the current samples
and retains the older data separately. The initial CPU-only FP32 study is in
`GPU_FP32_PRECISION.md`. Actual Mandelbrot shader validation and the per-pixel
interval acceptance contract are in `GPU_FP32_NATIVE.md`. The optional production
backend and standalone diagnostic now share the validated kernel and gate. The
production path uses the existing runtime owner.

After integration on 2026-09-03, `mvn clean test` passed (316 tests, four native
skips). A combined hardware run passed the runtime, palette, integrated backend
and full Retina precision gates with Unsafe denied. The integrated 384x256
overview certified 87,904 pixels and recovered 10,400, with exact escape/iteration
agreement and smooth error within 0.01. A fresh-JVM missing-shaderc run passed
exact CPU fallback. These are correctness checks, not performance claims.
