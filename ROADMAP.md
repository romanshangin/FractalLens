# FractalUI development roadmap

## 1. Restore a stable green build

- [x] Fix compilation and run the complete test suite.
- [x] Remove duplication from the parallel rendering pipeline.
- [x] Add coverage for render timing statistics.
- [x] Verify cancellation during partially completed pan rendering.
- [x] Cover frame-reuse fast paths in every direction, including zero shift and no overlap.
- [x] Isolate temporary profiling from the normal rendering pipeline.
- [x] Split the current optimization work into focused commits.

Exit criterion: `mvn test` succeeds and temporary profiling code is isolated from the main rendering pipeline.

## 2. Prove the effectiveness of frame reuse

- [x] Measure time to first correct updated region and total pan-render time.
- [x] Track reused-pixel percentage, calculation/colorization time, tile count, and p50/p95 timings.
- [x] Benchmark 1080p and HiDPI scenarios with short, medium, and near-full-frame pans.
- [x] Move benchmarks into a dedicated Maven profile and compare tile sizes 16, 32, and 64.
- [x] Publish completed rows immediately after pan reuse so deep-zoom pixels do not wait for an entire 32x32 tile before appearing.
- [x] Calculate only missing horizontal spans in partially reused tiles, avoiding synchronized per-pixel validity checks after pan.

## 3. Optimize Mandelbrot and Julia calculation

- [x] Establish dedicated Mandelbrot and Julia calculation benchmarks covering throughput, nanoseconds per pixel, iteration distributions, 1080p/HiDPI, and multiple zoom levels.
- [x] Add Mandelbrot main-cardioid and period-2-bulb interior rejection.
- [x] Evaluate exact periodicity checking; reject it because benchmark overhead exceeds the benefit for current scenarios.
- [x] Evaluate other proven interior-point shortcuts; retain only the exact main-cardioid and period-2-bulb tests because higher-period components lack equally cheap exact membership predicates and approximate bulb circles risk false interiors.
- [x] Evaluate cached orbit squares; reject the manual optimization because the JIT already eliminates the repeated work and Julia regresses.
- [x] Measure `FractalSample` allocation cost; retain the value-returning API because escape analysis removes most allocations and the remaining cost is negligible in iteration-heavy scenes.
- [x] Reuse conjugate Mandelbrot rows for empty frames centered around the real axis; verify exact output equivalence and a 1.72-1.88x parallel-render speedup.
- [x] Compare the scalar kernel with the Java Vector API; retain scalar production code because the current 128-bit/two-double species regresses overview rendering and provides only a small deep-zoom gain.
- [x] Retune tile size and worker count after optimizing the calculation kernel; retain 32-pixel tiles and `CPU - 1` workers as the best throughput, progressive-latency, and UI-responsiveness balance.
- [x] Evaluate a specialized direct-to-data Julia pipeline; retain the generic formula pipeline because HotSpot removes its abstraction cost and the measured difference is about 1%.
- [x] Investigate perturbation/reference-orbit rendering; defer production integration until arbitrary-precision coordinates provide a high-precision reference orbit.

## 4. Strengthen user interaction

Remaining items in this section are deferred while rendering-engine work is prioritized.

- [x] Replace the crowded top control row with a global toolbar, left inspector, canvas, and bottom render status area.
- [x] Add Reset View.
- [x] Display and edit center coordinates where appropriate.
- [x] Support native macOS trackpad pinch zoom, continuous two-finger panning, and directional swipe panning.
- Display zoom and iteration count.
- Add iteration controls and editable Julia parameters.
- Add keyboard navigation and render progress/status.
- Show user-facing errors instead of printing stack traces.
- Add copyable/shareable viewport presets.
- [ ] Replace the macOS canvas context menu with a native AppKit `NSMenu` for
  system-managed appearance and behavior. Add a small Objective-C/JNI bridge
  and package its native library, retaining the JavaFX menu on other platforms.
  Validate command callbacks, main-thread coordination, focus and dismissal,
  uninterrupted rendering, Retina/multiple-display positioning, and full screen.

## 5. Add history and reproducibility

- [x] Introduce an immutable `FractalScene` containing formula, viewport, iteration settings, and coloring, and pair every completed frame with the exact scene snapshot it represents.
- [x] Keep transient output dimensions and scheduling priority in a separate immutable `RenderTarget` so window resizes do not alter scene history.
- Add undo/redo and bookmarks based on `FractalScene` snapshots.
- Add JSON serialization and last-session restore.
- Add arbitrary-resolution export by combining a scene snapshot with an independent render target.

## 6. Expand the rendering engine

- [x] Add cubic Multibrot, Burning Ship, and Tricorn presets on reusable formula implementations.
- [x] Preserve smooth escape-time coloring as the default treatment for palette banding, with a slower seamless ping-pong cycle and perceptually uniform OKLab palette interpolation.
- [x] Precompute each OKLab gradient into a 65,536-entry lookup table so per-pixel coloring remains a constant-time array lookup.
- [x] Add basic PNG export of the current render without antialiasing.
- [x] Add adaptive 2x2–8x8 subpixel sampling to off-screen export, preserving the current output dimensions and averaging colors in linear light.
- [x] Add weak deterministic export dithering to reduce visible 8-bit gradient quantization without blurring fractal detail.
- [x] Add cancellable edge-adaptive 4x4 subpixel refinement after the interactive base frame is visible, publishing refined 32x32 tiles progressively, preserving refined overlap across pans, and retaining visible partial results when zoom interrupts an unfinished render.
- [x] Add deterministic jitter as an optional, reproducible interactive sampling pattern.
- [x] Add and benchmark exterior distance estimates for Mandelbrot, Julia, and cubic Multibrot; measurements show a 1.4–2x base-pass cost versus roughly 16x for full 4x4 coverage, but exterior estimates alone miss interior-centered boundary pixels.
- [x] Combine distance candidates with the existing image-space edge detector before using distance estimation to schedule interactive or export supersampling; keep Burning Ship and Tricorn on the current detector because their maps are non-analytic.
- [x] Treat raw image-space edge filtering as an optional fast display mode; keep the transformed previous frame visible while base tiles arrive, then progressively replace them with refined tiles.
- [x] Cache palette-independent subpixel samples for AA candidates so palette changes can recolor both the base frame and refined tiles without recalculating the fractal; define memory limits and eviction behavior.
- [x] Add an optional color-cycling animation that continuously advances the smooth-coloring offset over its seamless period-two loop without recalculating the fractal; pause it during navigation/rendering, use a throttled buffered recolor path, and apply it to refined pixels through the palette-independent AA cache.
- [x] Add histogram coloring as a separate two-pass tonal-mapping feature; it does not replace geometric antialiasing.
- [x] Add orbit traps and editable palette stops.
- [x] Add a memory-bounded LRU render cache for exact reverse-navigation reuse.
- [x] During zoom out, keep the scale-aware previous-frame reprojection as approximate display coverage, grow newly exposed base and AA tiles outward from its edges, and atomically replace the preview center with exact samples.
- Resize reuse is deferred: exact reuse requires changing viewport/render-grid
  geometry, while the current stretched preview and debounced rerender make the
  practical benefit too small to justify that complexity now.
- Add a separate high-resolution/off-screen export pipeline.

## 7. Implement deep zoom

The subsections below are ordered by architectural dependency. Do not start a
later subsection until the preceding subsection has a stable contract and its
existing behavior is covered by tests.

### 7.1. Establish the backend boundary without changing output

- [x] Add a conservative Julia-specific double-precision guard so unstable orbit blocks are not presented as real detail before a deep-zoom backend exists.
- [x] Introduce backend-neutral `RenderJob`, `RenderBackend`, and `SamplePlane`
  contracts so calculation lifecycle, progressive regions, cancellation, and
  sample storage are not tied to `ParallelFractalCalculator` or CPU arrays.
- [x] Move formula identity and immutable formula parameters into the render job
  instead of using a concrete `FractalCalculator` as the backend contract.
- [x] Adapt the existing direct `double` renderer as the first CPU backend and
  verify that rendering, pan reuse, antialiasing, recoloring, caching, and
  export retain their current behavior.

### 7.2. Make coordinates and navigation precision-independent

- [x] Introduce an arbitrary-precision complex coordinate type with precision derived from the current scale.
- [x] Store the viewport center and scale without `double` precision loss, and update zoom, pan, resize, display, serialization, and preset handling accordingly.
- [x] Derive backend-specific render grids from the precise viewport without
  forcing deep coordinates through a lossy `double` conversion.
- [x] Add capability-based backend selection that keeps the direct `double` CPU
  backend for normal zoom levels and selects a separate deep-zoom backend only
  when hardware precision becomes insufficient.

### 7.3. [x] Implement the first correct Mandelbrot deep-zoom backend

- [x] Initially enable deep zoom only for Mandelbrot; retain the current Julia path until its coordinate and orbit semantics receive a separate design.
- [x] Build a high-precision Mandelbrot reference orbit for the current viewport while retaining hardware floating point for per-pixel deltas.
- [x] Add a specialized perturbation Mandelbrot calculator that shares the reference orbit across tiles and still produces the final orbit values required by smooth coloring.

### 7.4. [x] Add reliability, cancellation, and bounded reuse

- [x] Detect unreliable perturbation results and recover through rebasing, additional reference orbits, or direct high-precision fallback for affected pixels.
- [x] Define cancellation and memory limits for reference-orbit construction before
  retaining or sharing orbit data across render generations.
- [x] Cache reference orbits across compatible renders only after their ownership,
  precision, and invalidation rules are explicit.

### 7.5. [x] Pass the correctness and performance gate

- [x] Verify deep-zoom output against direct arbitrary-precision reference renders, including boundary points, long-running interior points, glitches, pan/zoom transitions, and cancellation.
- [x] Benchmark the backend-selection threshold, reference-orbit overhead, cache effectiveness, time to first visible tile, and total render time.
- [x] Treat a correct, verified, and measured Mandelbrot deep-zoom backend with a
  direct CPU fallback as the completion boundary before starting GPU work.

### 7.6. [x] Evaluate secondary deep-zoom optimizations

- [x] Add a scaled-exponent perturbation path when a pixel delta can no longer
  retain a safe fraction of one pixel step in `double`; reserve direct
  arbitrary-precision iteration for the remaining unreliable pixels.
- [x] Investigate cubic series approximation; retain the current perturbation
  recurrence because safe skips improve throughput by only 1-3%, while the
  tested longer skip is still below 7% and exceeds the smooth-color tolerance
  in the shallower benchmark case.
- [x] Add modified in-loop rebasing for the standard double perturbation path;
  reset the reference index near Mandelbrot's critical point while retaining
  glitch detection and multi-reference recovery as safety fallbacks. Keep the
  scaled-exponent path unchanged until rebasing can preserve its separate
  exponent without a lossy conversion through `double`.
- [x] Add conservative bivariate linear approximation (BLA) blocks to the
  standard double perturbation path and precise AA sampler. Use double-scale
  validity radii, generation-local delta bounds, scalar escape tails, and
  existing rebasing/fallbacks; verify against scalar and arbitrary-precision
  controls and measure both shallow and deeper CPU workloads.
- [x] Reduce interaction-to-progress latency: use a 30 ms wheel debounce,
  flush pending renders at pinch completion, and publish the first ready
  batch without the regular 16 ms batching delay. Retain batching for later
  progress and test cancellation, replacement, and completion ordering.

This phase should remain separate from the current pan-reuse optimization.

## 8. Add GPU rendering

The GPU work starts only after the deep-zoom correctness gate. Each subsection
must preserve the direct CPU backend as a portable fallback.

Implement and validate the first GPU runtime and rendering prototype on Apple
Silicon using MoltenVK. Windows work is tracked separately in 8.7 and Intel/AMD
Mac validation in 8.8; preserve portable contracts throughout the GPU work.

### 8.1. Establish the GPU runtime and platform boundary

- [x] Select the initial GPU stack: MoltenVK on macOS through the LWJGL 3 Vulkan
  bindings.
- [x] Define the supported OS/GPU matrix, including the numeric
  capabilities required by each backend mode (`GPU_RUNTIME.md`).
- [x] Isolate native dependencies and GPU resource ownership behind a dedicated
  runtime so the render controller and JavaFX surface do not depend on a
  specific graphics API.
- [x] Implement the macOS runtime: architecture-specific LWJGL/MoltenVK natives,
  Vulkan instance and physical-device discovery, numeric/compute capability
  reporting, and a selected logical device with a compute queue.
- [x] Add synchronized runtime ownership, reference-counted loader lifetime,
  native cleanup, device-loss handling, and a guarded CPU fallback operation
  that preserves the caller's precision policy and cancellation.
- [x] Validate startup, simultaneous runtimes, shutdown/reopen, simulated device
  loss, missing-native failures, and direct/deep CPU fallback on Apple M3 Pro.
  See `GPU_RUNTIME.md` for commands and limits of this macOS-first validation.

### 8.2. Validate integration with palette recoloring

- [x] Add an optional GPU palette-recoloring backend that uploads compact base/AA
  smooth phases once and advances the palette offset in a shader; retain the
  CPU path as a portable fallback and benchmark both paths on Retina displays.
- [x] Present the recolored result through the existing JavaFX buffer boundary and
  measure upload, dispatch, readback, and presentation costs separately.
- [x] Validate the macOS/Apple Silicon prototype against the parallel CPU
  baseline, including palette/AA/frame invalidation and fallback. Run a paired
  Retina benchmark with warmup and saved samples (`PALETTE_BENCHMARK_RESULTS.md`).
  GPU remains opt-in: measured gains do not justify replacing CPU by default.
- [x] Repeat the paired Retina benchmark after the LWJGL 3.4.2 / FFM update,
  retain raw samples, and refresh the performance decision. Both 12/40 and
  30/120 runs favor GPU only for the large-AA workload; keep CPU as the default.

### 8.3. Build a limited Mandelbrot base-pass spike

- [x] Define initial FP32 numeric tolerances and screen coordinate rounding,
  orbit divergence and iteration limits against the direct-double CPU reference
  (`GPU_FP32_PRECISION.md`). Reject automatic selection by scale alone: even
  overview frames fail; whole-frame FP32 is not accepted.
- [x] Validate actual FP32 shader arithmetic in a verification-only MoltenVK
  Mandelbrot probe across full Retina frames, boundary sweeps, panned grids and
  iteration limits. An outward-rounded interval certificate accepts 89.65% of
  the overview Retina frame with zero false accepts in the recorded gate; raw
  FP32 has 1,352 wrong escaped classifications (`GPU_FP32_NATIVE.md`).
- [x] Integrate interval-based rejection and precision-preserving CPU recovery
  into the production Mandelbrot GPU backend; retain whole-job CPU fallback for
  unsupported grids, features and native failures.
- [x] Implement an opt-in Mandelbrot-only GPU calculation backend using the
  existing contracts and runtime-owned device/queue. Require explicit certified
  sample accuracy; retain histogram/orbit traps/deep zoom on CPU.
- [x] Read samples back into the existing CPU flow and retain CPU coloring,
  adaptive antialiasing, caching, and export. Separate reuse/cache entries by
  required sample accuracy.
- [x] Add generation-based cancellation, bounded asynchronous readback, and
  progressive 128x128 regions. Overlap the next readback with current CPU recovery,
  drain cancelled submissions, and publish only completed regions.

### 8.4. Pass the conformance and performance decision gate

- [x] Verify CPU/GPU output within defined numeric tolerances across backend
  selection boundaries and fallback transitions on M3 Pro. Native checks cross
  the actual coordinate gate and 1000/1001-iteration boundary; all measured
  frames satisfy exact iterations/escape and smooth error <= 0.01.
- [x] Benchmark kernel time, transfer cost, first published region, full base/AA
  frame time, memory use and Retina JavaFX publication overhead. Save paired
  uninstrumented and separately profiled results in `GPU_RENDER_BENCHMARK_RESULTS.md`.
  Physical scanout/vsync latency is not measured by this publication gate.
- [x] Make the continuation decision from end-to-end results: **do not expand
  GPU rendering yet**. All ten workload/size cases are slower on GPU; Retina
  overview base is 592.52 vs 52.56 ms, Refined + AA is 1708.19 vs 1175.21 ms.
  Keep CPU default and the limited GPU backend opt-in. Re-run this gate after
  targeted certificate/kernel improvements before reconsidering GPU residency.

### 8.5. Optimize certified GPU calculation and repeat the decision gate

Address the costs measured in 8.4 before expanding GPU residency. On the Retina
overview, host certification/recovery/publication takes about 545 ms and the
interval kernel about 139 ms; these costs overlap. CPU remains the default.

- [ ] Parallelize certificate checks, sample conversion and rejected-sample CPU
  recovery with bounded workers and staging memory. Preserve cancellation,
  generation isolation and publication of fully validated regions only.
- [ ] Eliminate repeated certificate and smooth-value calculations; reuse
  intermediate results without weakening the original-coordinate FP32 contract
  or changing the exact iteration/escape and <= 0.01 smooth-error requirements.
- [ ] Re-profile the host stages and interval kernel after these changes. Evaluate
  symmetry and interior shortcuts only with a correctness argument and native
  conformance checks; tune batch sizes if submission overhead remains material.
- [ ] Repeat the 8.4 native conformance and paired CPU/GPU performance gate,
  including fallback transitions, first publication, complete base/AA frames,
  memory and Retina JavaFX costs. Keep primary timings uninstrumented and save
  component profiling separately. Proceed to 8.6 only if end-to-end gains justify it.

### 8.6. Expand GPU residency incrementally only when justified

Deferred after the 8.4 performance gate on M3 Pro: the current hybrid backend
has no measured end-to-end gain. The items below are conditional, not the next
implementation step until the optimizations in 8.5 pass the repeated gate.

- Move adaptive edge/distance candidate detection onto the GPU.
- Move subpixel sampling and palette-independent AA sample storage onto the GPU.
- Add GPU feature parity for orbit traps and histogram reduction.
- Add GPU-resident frame caching, pan reuse, and exact CPU fallback transfers.
- Evaluate GPU off-screen export only after the interactive pipeline is stable.

### 8.7. Implement and validate the Windows GPU runtime

- [x] Select LWJGL 3 with native Vulkan as the Windows GPU stack.
- [ ] Add Windows native packaging, loader/device/compute-queue initialization,
  and numeric capability reporting behind the existing runtime boundary.
- [ ] Validate startup, simultaneous runtimes, shutdown/reopen, device loss,
  missing natives, and direct/deep CPU fallback on Windows 10/11.
- [ ] Validate palette recoloring, AA/frame/palette invalidation, and fallback;
  record paired CPU/GPU timings including transfer and JavaFX publication costs.
- [ ] Run calculation conformance and performance gates for implemented GPU
  modes on Windows hardware before enabling them there.

### 8.8. Validate the Intel/AMD Mac GPU path

- [ ] Validate the packaged macOS x64 LWJGL/MoltenVK path on Intel/AMD Mac
  hardware, including device selection and numeric capability reporting.
- [ ] Validate startup, simultaneous runtimes, shutdown/reopen, device loss,
  missing natives, and direct/deep CPU fallback.
- [ ] Validate palette recoloring, AA/frame/palette invalidation, and fallback;
  record paired Retina CPU/GPU timings and discrete-memory transfer costs where
  applicable.
- [ ] Run calculation conformance and performance gates for implemented GPU
  modes on the selected device; do not infer FP64 correctness from capability
  reporting alone.

## Target milestone

Deliver a stable interactive Mandelbrot/Julia explorer with smooth pan/zoom, measured frame reuse, PNG export, documentation, CI, and a packaged runtime.
