# GPU runtime boundary

The shared Vulkan runtime (macOS MoltenVK and Windows native Vulkan) owns headless Vulkan instance, device discovery/numeric-
compute reporting, selected logical device/queue; ready → `AVAILABLE`. CPU calculation/
recoloring stay default. Opt-in Mandelbrot certifies FP32 and recovers rejects on
CPU; optional palette kernel recolors compact base/AA phases, returning ARGB via
JavaFX. GPU gains depend on AA and transfer/presentation costs.

## Platform and numeric requirements

| Platform | Native runtime | Validation status |
| --- | --- | --- |
| macOS Apple Silicon, ARM64 JVM | LWJGL 3.4.2 + bundled ARM64 MoltenVK | Tested on Apple M3 Pro, macOS 26.6.2 |
| macOS Intel/AMD, x64 JVM | LWJGL 3.4.2 + bundled x64 MoltenVK | Packaging configured; hardware validation pending |
| Windows 10/11, x64 JVM | LWJGL + system Vulkan loader | Runtime implemented; hardware acceptance pending |
| Other OS/architectures | None | CPU only |

The runtime requires Vulkan 1.1 and a hardware GPU with a compute queue.
It prefers discrete, then integrated GPUs; software Vulkan CPU devices are not
selected. The CPU backends remain usable if native initialization fails.

| Mode | GPU requirement | Current status / matching CPU path |
| --- | --- | --- |
| Palette recoloring (8.2) | FP32 for AA; 64-wide workgroup, four storage buffers, 32-byte push constants | Implemented and tested on M3 Pro; opt-in, parallel CPU fallback |
| Limited base-pass spike (8.3) | FP32 certificate + CPU recovery; two storage buffers, 16-byte push constants, 64-wide groups | Integrated and opt-in; Mandelbrot smooth coloring, 1–1000 iterations; precision-selecting CPU fallback |
| Direct-double equivalent arithmetic | Explicit `shaderFloat64` support, enabled at device creation | Direct-double CPU; feature support alone is not a conformance guarantee |
| Deep zoom | A separate precision-preserving implementation | CPU Mandelbrot perturbation; no GPU deep-zoom mode |

Report all/selected devices, Vulkan version, vendor/device IDs, queue family,
max workgroup invocations/storage buffer. Only **selected** capabilities gate work.
`FLOAT32` is core Vulkan; `FLOAT64` requires supported/enabled logical-device
`shaderFloat64`. Future backends must still check kernel dimensions/precision.

## Ownership and fallback

`FractalRenderService` owns `GpuRuntime`, hiding Vulkan from controller/JavaFX.
Native calls: `VulkanComputeSession`, `VulkanPaletteKernel`, `VulkanMandelbrotKernel`,
`VulkanLibraryLease`. Refcounted loader keeps runtimes independent; borrow external
loaders. Initialization rolls back partial resources. Shutdown waits for device,
destroys logical device/instance, releases loader; known-lost devices destroy
without recovery wait.

`runOrFallback(required, gpuWork, cpuWork)` serializes native work/shutdown.
Unavailable/insufficient capability → CPU. `GpuException` disables/cleans native
before CPU retry; device loss latches `DEVICE_LOST`, never auto-retries.
Cancellation/programming errors propagate.

Caller supplies matching CPU work (palette or `PrecisionSelectingRenderBackend`).
GPU work must finish native access before return/throw, avoid retry-unsafe partial
publication, use `GpuException` for native failures and never re-enter lifecycle/
health/execution. Palette runs synchronously on recolor worker, one submission;
pre-submit cancellation aborts, post-submit drains fence before reuse/destruction.
Controller rejects stale epochs. Calculation uses dedicated synchronous-native
worker, asynchronous coordinator readback overlapping preceding CPU recovery.
No submission outlives its runtime lease.

## Mandelbrot base-pass integration

```sh
JAVA_TOOL_OPTIONS=-Dfractal.gpu.mandelbrot.enabled=true mvn javafx:run
```

The default `FractalRenderService` wraps the existing precision-selecting CPU
backend with `GpuMandelbrotRenderBackend` only when this property is enabled.
Explicitly injected backends keep their policy. GPU palette recoloring remains
independently controlled by `fractal.gpu.palette.enabled`.

Requires Mandelbrot/no traps, 1–1000 iterations, safe double grid,
`RenderJob.sampleAccuracy() == CERTIFIED_FP32`. Controller permits opt-in smooth
coloring; histogram uses `CPU_REFERENCE`. Default/legacy/export jobs stay CPU-reference.
Cache/pan reuse must match accuracy, excluding tolerant results from CPU-reference
requests. Certify/recover on the inherited pan grid.

Grid requires 1/16-pixel accuracy/distinct neighbors. Unsupported jobs keep CPU
selection/deep perturbation. Accept only `benchmarks/GPU_FP32_NATIVE.md` interval
certificates; recover rejects at original double coordinates. Never mark/cache
uncertified raw values ready.

192x192 regions follow shared priority/zoom-out order, skipping ready pan overlap.
Bounds: two reusable Java batches, one GPU worker/queue slot, at most ten host
workers. Readback overlaps current certification/recovery; `ManagedGpuRuntime`
serializes native/palette/shutdown. Two fixed mapped buffers: 2.8125 MiB logical,
4 MiB allocation ceiling; Java arrays about 5.91 MiB, staging 0.91 MiB.
Drain cancelled native/host work before reuse; publish whole regions only.

Stage all missing regional samples before publication; check cancellation during
preparation, submit/readback, recovery/progress. Service independently rejects stale
generations. Native failure cleans runtime, sends original frame's remaining work
to CPU, retaining certified regions/reused overlap. Publish no failed-batch samples;
never retry lost devices.

CPU coloring, AA/cache, histogram/export stay in their services. Certificates
are not arbitrary-palette bit equality; palette conformance compares identical
certified/recovered samples. M3 Pro roadmap 8.5 repeat: GPU Retina overview
592.52 → 238.67 ms versus CPU 71.93 ms; Refined + AA 1534.89 versus 1378.67 ms.
CPU stays default/residency deferred. [8.5 report](../../benchmarks/GPU_RENDER_BENCHMARK_8_5_RESULTS.md):
raw runs, host/kernel/memory/display limits; 8.4 stays separately archived.

`GpuMandelbrotRenderBackend.lastStats()` reports dispatched/certified/recovered
pixel counts and job-level CPU fallback. Region timings include host wait and
recovery; they are not GPU timestamp measurements.

Opt-in `fractal.gpu.mandelbrot.profile=true` enables bounded device timestamp
queries and `GpuMandelbrotRenderBackend.lastProfile()` host certification/recovery/
publication sums. Runtime owns query pool with kernel; unsupported timestamps
return -1, never zero. Upload/dispatch-fence/readback are host wall time; kernel
query is device time. Overlap makes sums nonadditive; normal runs allocate no pool.
Diagnostic `fractal.gpu.mandelbrot.hostWorkers`/`.regionSize` defaults:
10-or-processors-minus-2/192, region bounds 32..192.
`mvn -Pgpu-render-benchmark javafx:run` pairs production CPU/GPU with full-frame
numeric checks; options in report.

Roadmap 8.6 first-residency diagnostic does not activate it.
`fractal.aa.profile=true` records AA wall/base-color time, summed detection/sampling/
cache-color worker time, busiest-worker detection and pixel/sample counts;
per-pixel timestamps exist only here. M3 Pro still loses even with free detection:
keep CPU AA. [Decision](../../benchmarks/GPU_RESIDENCY_8_6_DECISION.md).

The follow-up 8.6 whole-frame residency spike is also isolated from production
backend selection. It keeps a palette-independent four-word sample record per
pixel in Vulkan storage, reads back only rejected pixel indices, uploads exact
CPU corrections, performs smooth palette coloring on GPU, and finally reads
back ARGB. Run its paired off-screen gate with:

```sh
JAVA_TOOL_OPTIONS='-Xmx2g -Dfractal.gpu.enabled=true' \
  mvn -Pgpu-resident-benchmark javafx:run
```

Run settings: `fractal.residentBenchmark.sizes`, `.scenes`, `.warmup`, `.samples`,
`.output`; native bound `fractal.gpu.resident.maxBytes` defaults 384 MiB.
Diagnostic-only `fractal.residentBenchmark.allowMismatch=true` counts known-bad
outliers instead of stopping on first mismatch. M3 Pro Retina overview/exterior
win, but correction-heavy seahorse is 24.1% slower: fails 15% every-scene target,
remains opt-in. [Results](../../benchmarks/GPU_RESIDENT_SPIKE_RESULTS.md).

Bounded rejection counters: Retina seahorse 51.16% uncertain escape, 48.84%
smooth interval too wide, all others zero. Palette-phase/iteration tweaks cannot
help; next calculation experiment needs higher-precision reject certification.
GPU_RESIDENT_REJECTION_PROFILE.csv (private archive:
`benchmarks/GPU_RESIDENT_REJECTION_PROFILE.csv`).

Reject-only centered-error FP32 preserved conformance but certified zero extra
pixels at both sizes/all three smoke scenes. Retina seahorse calculation median:
27.06 → 46.65 ms. Second pass removed; higher-precision representation, not another
FP32 bound shape, is required. GPU_RESIDENT_SECOND_STAGE_PROFILE.csv
(private archive: `benchmarks/GPU_RESIDENT_SECOND_STAGE_PROFILE.csv`).

Optimistic double-single ceiling (two floats, about 48 significant bits) accepted
all rejects before the missing rigorous stability certificate. Even with zero CPU
recovery, Retina seahorse is 37.3% slower/overview only 4.3% faster. Conformance
also fails: 54 seahorse outliers, max channel error 189. Emulation removed.
GPU_RESIDENT_DOUBLE_SINGLE_RESULTS.csv (private archive:
`benchmarks/GPU_RESIDENT_DOUBLE_SINGLE_RESULTS.csv`).

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

Smooth `GradientPalette`, edited stops included: immutable base generation/AA
snapshot/offset-independent palette stay resident. Pack phase pairs into uints,
escape flags into bitsets. AA changes upload snapshot only, palette changes table
only; scale/frame invalidates base. Stable offsets upload zero bytes, changing
32-byte push constants only. No 16-bit storage, FP64 or per-frame LUT rebuild.

`PaletteOffset` uses integer triangular-wave phases and CPU-derived rounding
thresholds. Base/alpha exact; FP32 linear-light AA within one RGB level (0–255).
Unsupported coloring/offsets use CPU; histogram/traps/calculation stay off this
GPU palette path.

Four persistent mapped buffers prefer coherent host-visible memory; fallback
flushes writes/invalidates reads. Base-to-AA and compute-to-host barriers plus fence
follow [Khronos synchronization](https://docs.vulkan.org/guide/latest/synchronization_examples.html).
Apple Silicon upload/readback copies shared memory, not discrete PCIe transfers.
Residency obeys `fractal.gpu.palette.maxBytes` (256 MiB default), storage/dispatch
limits. Allocation/compile/native-load failures disable this runtime's GPU and
retry whole work on CPU; retry GPU requires a new runtime.

`PaletteRecolorTiming`: preparation/lookup/setup, upload, dispatch, readback,
FX queue/publication. Dispatch is host encoding/submit/fence wait, not device query.
Total includes orchestration/fallback. Publication covers `SurfaceBuffer.publish`
array copy/PixelBuffer update, not scanout.
`benchmarks/PALETTE_BENCHMARK_RESULTS.md` defines reproduction/limits.

## Packaging and diagnostics

Java bindings are shared dependencies; macOS profile alone selects natives by
JVM architecture: `aarch64`/`arm64` → `natives-macos-arm64`, x64/Rosetta →
`natives-macos`. Includes LWJGL core, Vulkan/MoltenVK/shaderc. First palette use
compiles GLSL to SPIR-V once per runtime. JavaFX resolves native modules/enables
LWJGL native access; disabling `gpu-macos` preserves CPU builds.

Java 25+ loader selects LWJGL `ffm` before `VK`/`MemoryUtil` in code, covering
IDE/headless; explicit `org.lwjgl.system.memoryBackend` wins. `java.lang.foreign`
replaces `sun.misc.Unsafe`, removing JDK 24+ warnings.
[LWJGL FFM](https://github.com/LWJGL/lwjgl3/blob/3.4.2/doc/FFM.md).
Tests/native gate use `--sun-misc-unsafe-memory-access=deny` so regressions fail.

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

No GPU fails this gate, never skips. Covers two runtimes/independent teardown,
full reopen, simulated loss, ordinary/deep CPU; integrated Mandelbrot dispatch,
recovery/pan reuse, shared palette, default-service opt-in/loss/reopen; actual
palette kernels across presets/wraps, exact base/one-level AA, zero repeat uploads,
AA/scale/size/palette invalidation and dispatch cleanup/reopen.

Run failure-path checks in fresh JVMs:

```sh
mvn -Pgpu-smoke -Dfractal.gpu.expectUnavailable=true -Dorg.lwjgl.vulkan.libname=/private/tmp/fractallens-missing-moltenvk.dylib test
mvn '-P!gpu-macos,gpu-smoke' -Dfractal.gpu.expectUnavailable=true test
mvn -Pgpu-smoke -Dtest=PaletteRecolorNativeTest -Dfractal.gpu.expectPaletteUnavailable=true -Dorg.lwjgl.shaderc.libname=/private/tmp/fractallens-missing-shaderc.dylib test
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
conformance without GPU fallback or Unsafe warnings. GPU won only the large-AA workload
(3% and 16% lower time through the JavaFX buffer, respectively); CPU stays the default.
`benchmarks/PALETTE_BENCHMARK_RESULTS.md` reports the current samples and retains the
older data separately. The initial CPU-only FP32 study is in
`benchmarks/GPU_FP32_PRECISION.md`. Actual Mandelbrot shader validation and the
per-pixel interval acceptance contract are in `benchmarks/GPU_FP32_NATIVE.md`. The
optional production backend and standalone diagnostic now share the validated kernel and
gate. The production path uses the existing runtime owner.

After integration on 2026-09-03, `mvn clean test` passed (316 tests, four native
skips). A combined hardware run passed the runtime, palette, integrated backend
and full Retina precision gates with Unsafe denied. The integrated 384x256
overview certified 87,904 pixels and recovered 10,400, with exact escape/iteration
agreement and smooth error within 0.01. A fresh-JVM missing-shaderc run passed
exact CPU fallback. These are correctness checks, not performance claims.

## Windows acceptance (roadmap 8.7)

The automatically activated `runtime-windows` Maven profile includes x64 LWJGL
and shaderc natives. Vulkan itself is supplied by the GPU driver (`vulkan-1.dll`),
not bundled from a Vulkan SDK. Only `amd64`/`x86_64` JVMs are supported by this
packaging; other architectures return `UNAVAILABLE` before native loading.
Missing natives, a missing loader, an incompatible driver, or no eligible compute
device leave CPU calculation and deep zoom available. Device selection and numeric
reporting use the same session as macOS; portability extensions remain conditional.
Runtime discovery does not enable GPU calculation or palette recoloring by default.

Run separately on Windows 10 and Windows 11 with the pinned wrapper:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd -Pgpu-smoke test
```

`GpuRuntimeNativeTest` checks two concurrent runtimes, close/reopen, injected device
loss, and direct/deep CPU fallback. Injected loss is not a real driver-reset test.
In a separate JVM with absent libraries, run the lifecycle gate with
`-Pgpu-smoke -Dtest=GpuRuntimeNativeTest
-Dfractal.gpu.expectUnavailable=true`; arrange missing loader/core natives in the
launch environment without removing installed driver files. The unavailable
assertion must fail if discovery unexpectedly succeeds.

Only after lifecycle acceptance, run the native calculation/palette gates and
existing JavaFX invalidation/AA tests in an active graphical desktop session.
Use `GpuRenderBenchmark` under the established 8.5 paired protocol, retaining
transfer, first publication, completion and next-pulse timings; record Windows
OS, GPU/driver, power state, runtime versions and output scale. Keep raw output
private until publication checks pass. Windows measurements must be new evidence,
not relabeled macOS results. Neither reported FP64 nor a passing smoke test proves
full precision or performance acceptance. Windows 10/11 hardware and visible
acceptance remain open; no default-enablement decision is made here.
### Historical pre-transfer Windows 11 developer checks (2026-11-03)

These checks ran in the original checkout before transfer, on
`feature/windows-vulkan-runtime`, based on
`1312e7f57b68b39d02d96daf5885378b82daf625` plus the uncommitted runtime changes.
They do not validate the transferred candidate, whose base revision and merge base
are `63b5c63ac860799a7ea17f383dd444c19f37ced0`. The intervening changes affect
`FractalView` and `FractalResizeFxTest`. All counts and timings in this historical
subsection belong to the pre-transfer code; they must not be reused as current
candidate evidence. In particular, the exploratory benchmark has not been rerun
on the transferred base.
Windows 11 build 26200, x64 Java 26.0.1, JavaFX 26.0.2, LWJGL 3.4.2,
8 logical processors, approximately 2 GiB JVM heap, default Prism pipeline,
125% output scale. NVIDIA GeForce GTX 1650 with Max-Q Design selected (Vulkan
1.4.325, compute family 2); Intel UHD Graphics also enumerated. FP64 is reported,
not calculation-conformance validated. Driver package, power and thermal state
were not collected; these observations are not a promotion campaign.

Native smoke: five tests passed (calculation, resident spike, lifecycle, palette
with AA/invalidation). Separate JVM lifecycle runs passed with each of
`-Dorg.lwjgl.vulkan.libname=fractallens-missing-vulkan-test.dll` and
`-Dorg.lwjgl.libname=fractallens-missing-lwjgl-test.dll`, together with
`-Pgpu-smoke -Dtest=GpuRuntimeNativeTest -Dfractal.gpu.expectUnavailable=true`.
They exercised direct/deep CPU fallback without modifying installed libraries.

Exploratory alternating pairs used `GpuRenderBenchmark`, 1500x980 pixels,
`overview,overview-aa`, 3 warmups, 3 measured pairs per case and one JVM start.
Uninstrumented total-frame ranges (milliseconds):

| Case | CPU | GPU |
| --- | --- | --- |
| Overview | 39.57–47.33 | 531.87–568.35 |
| Overview + AA | 172.12–187.15 | 660.87–762.03 |

A separate identical run with `fractal.gpu.mandelbrot.profile=true` recorded
GPU upload 7.61–8.92 ms, readback/wait 196.91–292.14 ms, kernel 217.98–220.77 ms,
and JavaFX color/publication 27.13–58.33 ms across both cases. These component
ranges are instrumented observations, not additive explanations of the
uninstrumented totals. All pairs passed the benchmark comparison; maximum smooth
error was 0.00016570704. Raw local CSVs were temporary ignored output in `target/`, removed by the subsequent
`clean test`; only this exploratory summary is retained, not replayable public evidence. The initial 1512x982 request was rejected by the
benchmark's integral-logical-dimension guard at 125%; no timings were collected
for that invalid configuration.

Decision: **inconclusive** for full Windows acceptance; exploratory performance
is unfavorable. CPU remains default. Missing: Windows 10 execution, other drivers,
real driver-reset/device-loss acceptance, complete visible gesture/AA acceptance,
and the full multi-start workload/performance campaign with controlled power and
thermal conditions. macOS hardware regression was not rerun for the shared-session
rename. No historical macOS measurements were changed.

JavaFX resize suite: 47 tests, 0 failures/errors, 3 skips (two opt-in fullscreen checks and one POSIX-permission check);
this does not establish complete Windows GPU visible acceptance.
Portable `mvnw.cmd clean test`: 660 tests, 0 failures/errors, 140 opt-in skips
outside the restricted filesystem environment. The first restricted run failed
with `AccessDeniedException` during temporary-file replacement in bookmark/export
tests; the unchanged candidate passed outside that environment. Targeted runtime
checks: 15 tests passed. Compiler deprecation notes and the JavaFX native-access
warning were observed, not suppressed. These checks are developer self-validation,
not independent review or full platform acceptance.

### Post-transfer developer revalidation (2026-11-03)

Branch: `feature/windows-vulkan-runtime`. Base revision and merge base:
`63b5c63ac860799a7ea17f383dd444c19f37ced0`, with the uncommitted Windows runtime
changes and the evidence-provenance correction. These fresh executions ran in the
transferred checkout. They replace the historical portable/resize results for
this base; historical native-smoke and timing observations above are not reused
as current-candidate evidence. This documentation correction changes the reviewed
content identity; the review's earlier content ID does not identify this revision.

| Check | Result |
| --- | --- |
| `mvnw.cmd test` after removing prior compiler/test output | 661 tests, 0 failures, 0 errors, 141 opt-in skips |
| `FractalResizeFxTest` with `fractal.fx.tests=true`, a fresh platform-temp JavaFX cache and `reuseForks=false` | 49 tests, 0 failures, 0 errors, 3 skips |
| `git diff --check` | Passed |

The resize suite ran in the active Windows desktop session. Its three skips are
two separately opt-in fullscreen checks and one POSIX-permission check. These
skips are not passes or complete visible GPU acceptance. Compiler deprecation
notes and the JavaFX native-access warning were observed and not suppressed.

Two attempts at `mvnw.cmd clean test` stopped before test execution because Maven
could not delete `target/runtime-package/images/FractalLens/FractalLens.exe`.
No successful full `clean` is claimed. To avoid stale compiled output, the
`classes`, `test-classes`, `maven-status`, `generated-sources`,
`generated-test-sources` and `surefire-reports` directories were removed where
present before running `mvnw.cmd test`; the packaged image was left in place.
Surefire reports from the fresh portable and resize executions are retained
privately in `.git/windows-gpu-review-fix/`, outside publication candidates.

Native smoke, missing-library fault injection, timing campaigns, Windows 10 and
manual visible acceptance were not rerun as part of this documentation fix.
The historical benchmark remains inconclusive and cannot support promotion of
the transferred candidate. CPU remains the default; section 8.7 acceptance stays
open. These are developer self-validation results, not independent verification.
### Separate deep Julia follow-up

Subsequent standalone verification reported a deep Julia JavaFX completion-deadline
failure on both this base and the Windows runtime candidate with GPU disabled.
The fresh developer resize pass above remains an observation, not proof of a
reliably passing deadline. Investigation is tracked separately in
[Windows deep Julia frame-completion follow-up](../development/WINDOWS_DEEP_JULIA_FX_FOLLOWUP.md).
This documentation update does not rerun or replace that independent verification.