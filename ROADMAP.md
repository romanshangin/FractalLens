# FractalUI development roadmap

CPU/GPU optimization review (2026-09-05):
[analysis, experiment inventory and validation rules](CPU_GPU_OPTIMIZATION_ANALYSIS.md).
Keep CPU as the default after the failed 8.5 and resident 8.6 gates. The next
optimization work is section 9: establish comparable measurements, investigate
CPU AA synchronization, improve CPU/deep-zoom work, then test different GPU
algorithms. Completed and rejected experiments below remain historical evidence;
new unchecked items are proposals, not measured speedups.

Checkboxes represent acceptance state: a parent item remains open until every
required implementation and validation step beneath it is complete.

## 0. Prioritized backlog

This section is the execution order for all unfinished work below. The detailed
sections remain the source of requirements and acceptance criteria. A lower
priority does not cancel an item; it means that its dependencies, evidence or
delivery foundation should be completed first.

### P0. Infrastructure and delivery foundation — do first

- [ ] **P0.1. Make the build continuously reproducible.** Add CI for the
  portable CPU-default build and tests on macOS and Windows, with dependency
  caching, clean-checkout execution and uploaded test reports. Keep
  hardware-native GPU suites in explicit opt-in jobs because ordinary CI
  runners do not prove GPU conformance.
- [ ] **P0.2. Produce installable, smoke-tested runtime artifacts.** Define a
  versioned `jlink`/`jpackage`-style pipeline for the macOS app and Windows
  application, including JavaFX/LWJGL natives, the AppKit bridge where
  applicable, icons, launch options, licenses and CPU fallback. Record artifact
  provenance and a clean-machine launch checklist. Signing/notarization may
  remain a separate release step, but packaging must be automated and
  repeatable.
- [ ] **P0.3. Close the current measurement gate before starting another
  optimization.** Finish the open 9.1 paired-decision item and the already
  implemented 9.3 bounded-`ValidityMask` candidate with fixed power conditions,
  fresh output directories, at least 30 alternating pairs and multiple process
  starts. Retain or reject the candidate explicitly; do not leave production
  code in a permanently provisional state.
- [ ] **P0.4. Turn reproducibility into a product contract.** Implement section
  5 JSON scene serialization and last-session restore before undo/redo,
  shareable presets or new export variants. Version the schema and test
  migration, malformed input, precise decimal coordinates and atomic recovery
  after an interrupted write.
- [ ] **P0.5. Define platform validation lanes.** Treat the Windows runtime
  work in 8.7 and Intel/AMD Mac work in 8.8 as hardware-backed infrastructure
  tracks. They may run when hardware is available and do not block the
  CPU-default macOS artifact, but they must precede enabling GPU modes on those
  platforms.

- [ ] **P0 exit criterion:** a clean checkout is tested automatically, produces a
  traceable installable artifact, restores a versioned scene safely, and the
  current bounded-mask candidate has a recorded retain/reject decision.

### P1. Production reliability and measured latency

- [ ] **P1.1.** Replace user-visible stack traces with in-application errors,
  then add render progress/status and keyboard navigation from section 4.
- [ ] **P1.2.** Run the retained-snapshot preparation experiment in 9.2; accept
  it only if first-tile and full-frame behavior both pass.
- [ ] **P1.3.** Measure JavaFX dirty rectangles, callback coalescing, staging
  reuse and redundant copies from 9.3. This is the next end-to-end publication
  candidate after the validity-mask decision.
- [ ] **P1.4.** Profile the concrete allocation candidates at the start of 9.4,
  especially immutable reference conversions and precise AA coordinate
  construction.
- [ ] **P1.5.** Promote every retained change through the portable/native/FX
  and sustained gates in 9.9; this is part of the change, not a later cleanup
  phase.

### P2. Product foundation and user workflows

- [ ] **P2.1.** Complete iteration/Julia editing and the zoom/iteration presentation in
  section 4.
- [ ] **P2.2.** Build undo/redo and bookmarks on the versioned scene format, then add
  copyable/shareable viewport presets.
- [ ] **P2.3.** Consolidate the duplicate section 5/6 export requirements into one tiled,
  cancellable high-resolution/off-screen pipeline based on `FractalScene` and
  an independent `RenderTarget`.
- [ ] **P2.4.** Reduce dialog modality, then extend the AppKit bridge only where a native
  sheet materially improves behavior. Finish multi-display context-menu
  validation when suitable hardware is available.
- [ ] **P2.5.** Optimize Julia subnormal deltas in 7.7 only after profiling confirms that
  exact fallback is a material user-visible cost.

### P3. Conditional CPU research

- [ ] After P0/P1, take the remaining 9.2–9.5 experiments one bounded spike at a time:
  candidate sidecars and adaptive sampling; scalar interleaving/SIMD/scheduling;
  BLA/reference sharing and scaled-exponent work; then alternative numeric or
  spatial engines. Each spike needs a predeclared workload, correctness gate and
  retain/reject result. Do not batch speculative algorithms into one change.

### P4. Conditional GPU expansion and hardware alternatives

- [ ] The order is 9.6 diagnostic GPU perturbation/BLA, then 9.7 selection/scheduling,
  then the production gates in 9.9. Only a passing result may reopen the unchecked
  8.6 residency work. Section 9.8 API/hardware alternatives stay last and require
  a new measured bottleneck. The failed 8.4–8.6 gates mean that GPU AA storage,
  feature parity, resident caching and GPU export are not near-term tasks.

### Newly explicit infrastructure tasks

- [ ] Add portable macOS/Windows CI for the CPU-default build and test suite,
  plus opt-in native hardware lanes with retained reports. The workflows and
  runner contract are implemented in `.github/workflows` and `CI.md`; keep this
  item open until the hosted macOS/Windows jobs and configured self-hosted lane
  have produced retained reports from the repository. This implementation is
  limited to P0 item 1 and does not satisfy the P0 exit criterion: packaging,
  scene restore and the open measurement decision remain separate required
  work.
  - [x] Implement the portable and opt-in native workflow definitions, clean
    checkout checks, dependency caching, bounded diagnostics and retained test
    reports.
  - [x] Pass the hosted macOS and Windows CPU-default jobs and retain their
    reports in [Portable CI run 35303673925](https://github.com/romzesthefirst/fractal-ui/actions/runs/35303673925).
  - [ ] Run the configured self-hosted native hardware lanes and retain their
    reports.
    - [x] Pass the Apple Silicon macOS lane on an Apple M3 Pro with macOS 27.0:
      5 native GPU smoke tests passed and the reports were retained in
      [Native GPU Validation run 35373192709](https://github.com/romzesthefirst/fractal-ui/actions/runs/35373192709).
    - [ ] Pass the Windows x64 CPU-fallback lane on a matching self-hosted
      runner and retain its reports; no Windows self-hosted runner is currently
      registered for the repository.
- [ ] Add reproducible macOS and Windows runtime packaging and clean-machine
  smoke checks; document artifact provenance and the separate signing/release
  boundary.

## 1. Restore a stable green build

- [x] Fix compilation and run the complete test suite.
- [x] Remove duplication from the parallel rendering pipeline.
- [x] Add coverage for render timing statistics.
- [x] Verify cancellation during partially completed pan rendering.
- [x] Cover frame-reuse fast paths in every direction, including zero shift and no overlap.
- [x] Isolate temporary profiling from the normal rendering pipeline.
- [x] Split the current optimization work into focused commits.

- [x] **Exit criterion:** `mvn test` succeeds and temporary profiling code is
  isolated from the main rendering pipeline.

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
- [x] Investigate perturbation/reference-orbit rendering; the early double-only
  prototype was rejected. The subsequent high-precision production implementation
  and successful rebasing/BLA work are completed in 7.3–7.6.

## 4. Strengthen user interaction

Remaining items in this section follow the cross-section order in section 0.

- [x] Replace the crowded top control row with a global toolbar, left inspector, canvas, and bottom render status area.
- [x] Add Reset View.
- [x] Display and edit center coordinates where appropriate.
- [x] Support native macOS trackpad pinch zoom, continuous two-finger panning, and directional swipe panning.
- [ ] Display zoom and iteration count.
- [ ] Add iteration controls and editable Julia parameters.
- [ ] Add keyboard navigation and render progress/status.
- [ ] Show user-facing errors instead of printing stack traces.
- [ ] Add copyable/shareable viewport presets.
- [x] Replace the macOS canvas context menu with a native AppKit `NSMenu` for
  system-managed appearance and behavior. Add a small Objective-C/JNI bridge
  and package its native library, retaining the JavaFX menu on other platforms.
  Validate command callbacks, main-thread coordination, focus and dismissal,
  uninterrupted rendering, Retina/multiple-display positioning, and full screen.
  Implemented with a packaged universal JNI library and JavaFX fallback.
  Native callbacks, accessibility activation, cancellation/replacement, live
  rendering and full-screen entry/exit passed on the built-in Retina display;
  see `MACOS_UI.md` for validation details and reproducible commands.
- [ ] Complete native context-menu positioning validation across multiple displays,
  including mixed DPI, negative screen origins and display edges. Only one
  physical display was available for the initial implementation checks.
- [x] First unify JavaFX dialog appearance with macOS: system fonts, light/dark
  backgrounds, system accent colors, restrained input styling, and consistent
  spacing. Place the primary action on the right; support Enter to confirm,
  Escape to cancel, and predictable keyboard focus and validation.
  Shared appearance now covers coordinate/palette editors, Help and alerts;
  GUI regressions cover theme changes, exact input, draft validation and focus.
  See `MACOS_UI.md` for validation details.
- [ ] Reduce unnecessary modality: use a compact owner-associated sheet for
  Go to Coordinates, a nonmodal palette editor with live preview, and a nonmodal
  Help window. Show export completion unobtrusively inside the application;
  reserve modal alerts for errors or decisions requiring user action.
- [ ] Extend the planned AppKit bridge to native dialogs: use `NSAlert` sheets
  for actionable messages and custom sheets with native fields and buttons for
  forms such as Go to Coordinates. Retain JavaFX dialogs on other platforms.
  `WINDOW_MODAL` and CSS alone do not provide a native AppKit sheet. Validate
  ownership, focus restoration, cancellation, input validation, appearance
  changes, full screen, and callbacks without blocking rendering.

## 5. Add history and reproducibility

- [x] Introduce an immutable `FractalScene` containing formula, viewport, iteration settings, and coloring, and pair every completed frame with the exact scene snapshot it represents.
- [x] Keep transient output dimensions and scheduling priority in a separate immutable `RenderTarget` so window resizes do not alter scene history.
- [ ] Add undo/redo and bookmarks based on `FractalScene` snapshots.
- [ ] Add JSON serialization and last-session restore.
- [ ] Add arbitrary-resolution export by combining a scene snapshot with an independent render target.

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
- [x] Reuse exact samples and retained AA across compatible resize/pan grids;
  expansion calculates exposed borders and cropping retains valid overlap.
  Preserve unchanged pixel spacing, formula, sample accuracy and iteration
  limits; cover partial frames and resize-then-drag regressions. Measure its
  application-level benefit in 9.1 rather than repeating the completed work.
- [ ] Add a separate high-resolution/off-screen export pipeline.

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

### 7.7. Julia deep zoom

- [x] Define Julia semantics separately: the exact pixel is `z0`; the fixed `c`
  retains the binary-double parameter value used by the standard formula.
- [x] Add a tiled, cancellable BigDecimal backend using viewport precision,
  ready-pixel reuse, initial-radius testing, and the existing iteration-cap convention.
- [x] Enable precision-independent Julia navigation, Deep Zoom presentation,
  optional precise AA, orbit traps, and precise adaptive PNG export.
- [x] Cover sub-double and subnormal coordinate steps, higher-precision oracle
  conformance, cancellation, reuse, AA, export, and camera precision transitions.
- [x] Accelerate Julia using its own reference-orbit perturbation recurrence
  (`delta z0 = pixel - reference`, `delta c = 0`), two-component deltas, conservative error checks,
  bounded worker-local recovery references, and a BigDecimal fallback.
- [x] Add a regression that requires one shared exact orbit for thousands of
  coherent pixels, fixed-point conformance across several depths and traps,
  and a window-size JavaFX completion/loading-layer regression.
- [x] Include Julia's first-step contraction near the critical point in the
  shared backend/presentation/export precision gate. Preserve the reported
  decimal coordinates in `src/test/resources/julia/reported-pixelation.txt`.
- [x] Stop accumulating guard digits on every navigation operation; bound
  render arithmetic to pixel depth while preserving authoritative input values.
- [ ] Extend the perturbation fast path to deltas below the normal-double range;
  these currently retain the exact fallback.

Julia uses shared reference perturbation in base rendering, deep AA, and export.
It does not apply Mandelbrot cardioid tests, critical-point rebasing, or BLA.
Unreliable and subnormal deltas retain the slower arbitrary-precision fallback.
See `docs/JULIA_DEEP_ZOOM_PERFORMANCE.md` for the latency regression and measurements.

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

- [x] Parallelize certificate checks, sample conversion and rejected-sample CPU
  recovery with bounded workers and staging memory. Preserve cancellation,
  generation isolation and publication of fully validated regions only.
- [x] Eliminate repeated certificate and smooth-value calculations; reuse
  intermediate results without weakening the original-coordinate FP32 contract
  or changing the exact iteration/escape and <= 0.01 smooth-error requirements.
- [x] Re-profile the host stages and interval kernel after these changes. Evaluate
  symmetry and interior shortcuts only with a correctness argument and native
  conformance checks; tune batch sizes if submission overhead remains material.
- [x] Repeat the 8.4 native conformance and paired CPU/GPU performance gate,
  including fallback transitions, first publication, complete base/AA frames,
  memory and Retina JavaFX costs. Keep primary timings uninstrumented and save
  component profiling separately. The repeated M3 Pro gate passes conformance
  but fails performance: all ten cases remain slower on GPU. Retina overview
  improves from 592.52 to 238.67 ms but remains behind CPU at 71.93 ms; see
  `GPU_RENDER_BENCHMARK_8_5_RESULTS.md`. Keep CPU default and 8.6 deferred.

### 8.6. Expand GPU residency incrementally only when justified

Deferred again after the repeated 8.5 performance gate on M3 Pro: the optimized
hybrid backend is substantially faster than in 8.4 but still has no measured
end-to-end win over CPU. A whole-frame resident feasibility spike now shows that
residency can win when the FP32 certificate rejects few pixels, but it fails the
cross-scene gate and is not connected to production backend selection. The items
below remain conditional rather than the next implementation step.

- [x] Measure adaptive edge/distance candidate detection separately before
  moving it. The 8.6 M3 Pro profile shows a 17-19 ms candidate critical path at
  1512x982 and 72-74 ms at 3024x1964, smaller than the GPU base-frame deficit in
  every paired AA case. Even zero-cost GPU detection would remain 1.03-1.07x
  slower end to end; see `GPU_RESIDENCY_8_6_DECISION.md`.
- [x] Build an isolated whole-frame residency feasibility spike: keep canonical
  samples on GPU, read back only the FP32 rejection list, upload exact CPU
  corrections, color on GPU, and return ARGB. At that pre-JavaFX boundary on
  M3 Pro it is 39.9% faster for Retina overview and 83.9% faster for Retina
  exterior, but 24.1% slower for Retina seahorse because 68.5% of pixels require
  CPU recovery. These fixed-300-iteration, base/color-only measurements exclude
  AA and progressive presentation; their color comparison is not a full sample
  conformance gate. The strict 15% cross-scene gate therefore fails; see
  `GPU_RESIDENT_SPIKE_RESULTS.md` and the scope audit in
  `CPU_GPU_OPTIMIZATION_ANALYSIS.md`.
- [x] Classify resident certificate rejections before changing the kernel. In
  the Retina seahorse view, 51.16% of rejections come from an uncertain escape
  interval and 48.84% from a smooth-value interval wider than the contract;
  iteration, boundedness, validity, palette-phase and catch-all mismatches are
  all zero. Palette or dispatch tuning cannot remove this bottleneck. See
  `GPU_RESIDENT_REJECTION_PROFILE.csv`.
- [x] Prototype a tighter centered-error FP32 certificate only for first-stage
  rejections. Its outward-rounded error recurrence certifies zero additional
  pixels in overview, exterior, and seahorse, while raising the Retina seahorse
  calculation median from 27.06 to 46.65 ms. Remove the second pass; a future
  attempt needs a genuinely higher-precision representation, not another FP32
  interval shape. See `GPU_RESIDENT_SECOND_STAGE_PROFILE.csv`.
- [x] Measure the accuracy and performance ceiling of double-single GPU recovery
  for every FP32 rejection before investing in its certificate. It removes CPU
  recovery, but fails both gates: Retina seahorse is 37.3% slower than CPU and
  has 54 color outliers with a maximum channel error of 189; Retina overview is
  only 4.3% faster and has 46 outliers. Remove the emulation path and retain its
  diagnostic results in `GPU_RESIDENT_DOUBLE_SINGLE_RESULTS.csv`.
- [ ] Move adaptive edge/distance candidate detection onto the GPU only after a
  repeated gate shows an end-to-end win including dispatch, synchronization,
  storage and exact fallback costs.
- [ ] Move subpixel sampling and palette-independent AA sample storage onto the GPU.
- [ ] Add GPU feature parity for orbit traps and histogram reduction.
- [ ] Add GPU-resident frame caching, pan reuse, and exact CPU fallback transfers.
- [ ] Evaluate GPU off-screen export only after the interactive pipeline is stable.

The next algorithm and selection experiments are specified in 9.6 and 9.7.
Their diagnostic scope does not reopen production residency expansion or mark
this section's failed gates as passed.

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

## 9. Pursue CPU/GPU optimization from the measured bottlenecks

Use [the analysis](CPU_GPU_OPTIMIZATION_ANALYSIS.md) for the evidence, current
code entry points, alternative approaches and rejected experiments. Follow the
cross-section priority order in section 0: close the open 9.1/9.3 decision
first, then take selected 9.2–9.5 CPU follow-ups. Section 9.6 is the preferred
new GPU algorithm experiment; 9.7 and 9.8 are conditional alternatives.
Production GPU expansion remains gated by 8.6 and the final checks in 9.9.
Windows and Intel/AMD Mac work stays in 8.7/8.8.

### 9.1. Establish comparable baselines and close profiling gaps

- [x] Audit the saved CPU, palette, 8.4/8.5, candidate-detection, resident,
  rejection, centered-error and double-single results against current code;
  recompute the cited Retina GPU medians from saved samples. Record the analysis
  separately from new performance measurements.
- [x] Build a reproducible fixture matrix with exact viewport coordinates,
  actual adaptive/fixed iteration caps, formula/features, AA pattern, dimensions,
  sample accuracy and cache state. Include Mandelbrot/Julia, other formulas,
  direct/deep transitions, glitch-heavy and BLA-friendly scenes, scaled-exponent
  zoom, pan overlap, reverse navigation, resize-then-drag and cancellation.
  The versioned [matrix and headless runner](BASELINE_BENCHMARK.md) contain
  28 sequences / 37 steps at three render sizes, with a pinned exact manifest
  and behavioral regressions. See [validation](BASELINE_VALIDATION.md).
- [x] Measure formula/backend, returned-ARGB, JavaFX base/AA publication and
  input-to-visible-update scopes separately. Record first useful region,
  cold/warm full-frame time and cancellation tails; label physical scanout as
  unmeasured until a dedicated display experiment exists.
  Headless backend, returned-ARGB, first useful sample region, production AA
  and post-first-region cancellation scopes share the 9.1 matrix. The
  [JavaFX publication runner](BASELINE_FX_BENCHMARK.md) now uses the same inputs
  for Fast/Refined base/AA publication and request-bound post-layout observations,
  with exact sample/ARGB controls. See [validation](BASELINE_FX_VALIDATION.md).
  The [production input runner](BASELINE_INPUT_BENCHMARK.md) now connects scroll,
  pinch, trackpad, drag, resize and input-driven replacement to the same fixtures.
  Exact actual camera/controller jobs and generation-bound traces distinguish UI
  policy from canonical requests; sample/ARGB controls preserve verified reuse.
  See [input validation](BASELINE_INPUT_VALIDATION.md). The matrix also exposed
  and regression-tested scaled-exponent pan grid-snap overflow. Handler-entry
  timestamps and post-layout observations are not physical input/display latency.
- [x] Add a headless production-AA benchmark, an explicit CPU-only JavaFX
  comparison mode, and cache preparation/merge timing and batch counters.
  Record separate contention profiles and 30-sample comparisons across three
  fresh process pairs in [CPU AA results](CPU_AA_OPTIMIZATION_RESULTS.md).
- [x] Measure production scroll/pinch/drag handler latency with generation-aware
  callback/publication tracing, no-screen controls and a calibrated screen-marker
  capture proxy. Isolate the roughly 1.82 s fully-ready validity-mask scan at
  2400x1520; physical scanout remains unmeasured. See
  [interaction latency results](INTERACTION_LATENCY_RESULTS.md).
- [x] Add opt-in CPU base-task planning, queue and cancellation-drain diagnostics
  to the canonical matrix and real-input trace. Preserve request/input generation
  through worker exit, including workers outliving cancelled coordinator Futures.
  See [the contract](BASELINE_SCHEDULING_BENCHMARK.md) and
  [validation](BASELINE_SCHEDULING_VALIDATION.md). Per-worker intervals overlap;
  they are not additive frame costs or process CPU utilization.
- [x] Extend diagnostics to allocation and long-duration memory/thermal behavior.
  The long FX matrix exposed detached surfaces retained by Scene/Window scale
  listeners; unsubscribe-on-detach is now regression-tested. FX rows also record
  heap/GC observations; see [the ownership fix](BASELINE_FX_VALIDATION.md).
  The [allocation and sustained-memory driver](BASELINE_MEMORY_BENCHMARK.md)
  separates navigation from exact CPU controls and adds JFR allocation attribution,
  RSS, NMT, GC checkpoints and macOS thermal pressure. The selected eight-fixture
  validation covers 288 exact trials, including a 13.24-minute 1512x982 run with
  stable detached-heap checkpoints; see [results and limits](BASELINE_MEMORY_VALIDATION.md).
  Physical temperature, energy and all-platform/full-matrix soaks remain unmeasured.
- [ ] Use uninstrumented alternating pairs for decisions and separate JFR/native
  profiles for attribution. Confirm promising changes with at least 30 measured
  pairs and multiple process starts; record median/tails, hardware/runtime,
  memory/GC and sustained thermal behavior. Do not run competing timing suites
  simultaneously or overwrite historical CSVs.
  The [frozen-build paired runner](BASELINE_PAIRS_BENCHMARK.md) now enforces
  declared targets/controls, matching samples and alternating process starts.
  [A/A calibration](BASELINE_PAIRS_VALIDATION.md) validates the protocol on
  identical builds. This item stays open until a production candidate has a
  confirmed, scope-matched A/B result; calibration is not an optimization gain.
  The first [bounded-mask candidate](VALIDITY_MASK_OPTIMIZATION.md) now has
  six-process-pair A/B evidence: retained-frame returned pixels improve about
  145x at 3024x1964 and full FX publication about 22x at 1512x982. Exact controls
  pass, but the aggregate 5% timing-control budget is still inconclusive, so
  this decision item remains open.

- [ ] **Exit criterion:** a current, scope-matched baseline identifies the dominant costs
  and defines the correctness/performance gate before each implementation spike.

### 9.2. Reduce CPU antialiasing synchronization and repeated work

- [x] Profile `InteractiveAntialiasService.refineTile` and the synchronized
  access-order map in `AntialiasSampleCache`. Separate JFR runs recorded
  3,266,546 contended cache entries before the change and zero after it;
  accumulated worker waits are diagnostic evidence, not frame wall time.
- [x] Replace per-pixel shared-cache lookups with an immutable retained snapshot
  and an empty-cache fast path. Encode samples once into worker-owned tile
  batches, reuse sample scratch arrays and merge completed tiles after checking
  the frame/generation. Remove duplicate base coloring and repeated cache reads.
  Preserve phase quantization, payload limits, recoloring and pan/resize reuse;
  snapshot reads no longer update per-pixel LRU positions. Add regressions for
  cancelled-worker admission, phase ownership and pattern/scale invalidation.
  Three alternating JVM pairs, 30 measured frames per build/case: AA-only gains
  **2.05–6.46x**, with every compared pixel unchanged. Retina overview including
  JavaFX publication improves **1378.67 → 332.33 ms** (Fast + AA) and
  **1393.72 → 283.65 ms** (Refined + AA). Refined first publication improves;
  Fast base publication shifts by less than 1 ms before AA starts.
  Deep-AA follow-up shows no meaningful gain. See
  [CPU AA results](CPU_AA_OPTIMIZATION_RESULTS.md) for scopes, tails, memory
  observations and supplementary controls.
- [ ] Reduce the retained-snapshot color preparation barrier without restoring
  shared-cache contention. The 30-sample retained-Julia follow-up completes
  about 10x faster, but the first tile arrives about 5 ms later. Compare
  on-demand tile coloring with a compact immutable lookup; verify palette
  reuse, additional memory and first/full publication together.
- [ ] Compare an AA-enabled distance/candidate sidecar in the base pass with the
  current second derivative-orbit calculation for non-edge pixels. Include
  base-pass/first-publication cost and keep image-space edge detection for
  interior-centered boundaries; retain the current path for non-analytic maps.
- [ ] Separately evaluate nested/adaptive subpixel sampling only if sampling
  still dominates. Current direct 4x4 and deep 2x2/4x4 positions are not
  automatically reusable nested grids. Treat changed positions/sample counts
  as a quality-policy change with dense-reference image comparisons, stable
  jitter, palette-independent caches and exact export semantics.

- [ ] **Exit criterion:** unchanged-quality work passes sample/color and retention tests,
  with a confirmed full-AA improvement under the 9.1 gate and no material first
  publication regression. Sampling-quality experiments have a separate decision.

### 9.3. Improve CPU batching, scheduling and publication

- [ ] Bound reusable `ValidityMask.missingRowSpans` scans to their row/region,
  avoid repeated complete-frame scans, and check cancellation during task
  preparation. The [9.1 scheduling probe](BASELINE_SCHEDULING_VALIDATION.md)
  attributes roughly 5 seconds at 3024x1964 to planning with zero worker tasks,
  including a similar cancellation tail. Add exact-mask/reuse and deterministic
  cancellation regressions, then apply the 30-pair/multiple-process gate before
  claiming an interaction or throughput gain.
  Implemented in candidate `9778ac9`: bounded bitmap-word scans, an exact cached
  readiness count, complete-frame early exit and cancellation before task
  submission. Pixel-oracle, near-complete Retina and deterministic request-drain
  regressions pass. The [candidate report](VALIDITY_MASK_OPTIMIZATION.md)
  confirms the retained-frame target gain; final acceptance remains open on the
  unresolved overview, pan and short-latency timing controls.
  A separate 13.37-minute input/memory soak passes 108 exact trials and three
  whole rounds, with stable detached heap near 126.8 MB. A prior clock-shifted
  soak is explicitly rejected. Keep power source fixed for the next timing
  campaign; the last FX control process changed from battery to AC power.
- [ ] Benchmark interleaved independent scalar orbits and modest unrolling on
  Mandelbrot, Julia and AA samples. Inspect JIT/allocation profiles; preserve
  arithmetic order and bounded cancellation. Do not repeat rejected manual
  square caching or generic-to-specialized Julia rewrites without new evidence.
- [ ] Compare SIMD lane refill/compaction and workload-specific dispatch,
  including wider x86 FP64 species where available. Keep the current scalar
  fallback; the rejected M3 Pro two-lane masked loop is not the proposed change.
- [ ] Compare small first tiles followed by larger low-cost tiles and measured
  cost-based scheduling. Coordinate base, AA and recovery worker budgets to
  prevent oversubscription; retain UI headroom and the existing default until
  both throughput and latency pass. Do not assume more threads always help.
- [ ] Measure dirty-rectangle PixelBuffer updates, post-first-region callback
  coalescing, reusable output/staging and avoided redundant recoloring/copies.
  Preserve refined pixels, exact reused spans and resize behavior. Validate
  through JavaFX; a faster kernel or direct buffer alone is insufficient.

- [ ] **Exit criterion:** each retained change improves its declared workloads and keeps
  overview, deep scenes, interaction and cancellation within the control budget.

### 9.4. Extend the successful CPU perturbation/BLA path

- [ ] Profile BLA preparation/lookup, block objects, coordinate setup and sample
  publication once skipping dominates. Compare primitive block arrays and
  tile-local delta bounds while retaining the existing strict accuracy controls.
  The [9.1 allocation profile](BASELINE_MEMORY_VALIDATION.md) identifies repeated
  precise AA coordinate construction and `ReferenceOrbit.cRealAsDouble` /
  `cImaginaryAsDouble` conversions as concrete allocation candidates. First
  evaluate caching immutable reference conversions and reusing exact coordinate
  components; preserve BigDecimal operation order and the same sample controls.
- [ ] Evaluate reference and sampler/BLA sharing across base/AA and compatible
  navigation, plus bounded additional-reference reuse. Make ownership,
  precision, iteration capacity, coverage and eviction explicit; recompute
  generation-specific radii and never cache cancelled construction.
- [ ] Extend modified rebasing and BLA to the scaled-exponent path and, where
  profitable, additional-reference retries. Preserve the separate exponent,
  scalar escape tail, glitch detection and arbitrary-precision fallback.
- [ ] Evaluate higher-order series/polynomial blocks or tighter accumulated
  error bounds first as offline correctness/skip-coverage experiments. Include
  the shallow boundary where current BLA skips nothing; reject tolerance
  relaxation and do not repeat the failed cubic/loose-radius trials unchanged.

- [ ] **Exit criterion:** BigDecimal controls and full scalar comparisons retain exact
  escape/iteration behavior and existing deep smooth tolerance; gains include
  reference/table setup and AA, with no stale-bound or navigation regression.

### 9.5. Investigate conditional CPU numerical and spatial algorithms

- [ ] If reference construction or precise fallback dominates, compare optimized
  BigDecimal setup with batched binary fixed-point, double-double transition
  arithmetic or MPFR/GMP via FFM. Include call/conversion costs, rounding,
  precision growth, native packaging and a portable BigDecimal fallback.
- [ ] For increasing iteration caps at unchanged precise coordinates, evaluate
  explicit continuation state for unfinished pixels, including derivative/trap
  state where required. Account for memory and invalidation; existing capped
  samples are not completed higher-cap results. Keep current cache compatibility
  and zoom-budget rules until this separate contract is proven.
- [ ] Evaluate certified attracting-cycle/interior tests or interval-proven
  region skipping only on workloads that can amortize their cost. Exact
  checkpoint periodicity already lost. Matching corner/border colors,
  approximate bulb circles or a small derivative alone cannot authorize exact
  tile filling; quadtree scheduling can still be evaluated independently.
- [ ] Extend optimized sampling to Julia/Multibrot first where applicable;
  give Julia, Burning Ship and Tricorn perturbation separate numerical designs
  and controls. Do not reuse Mandelbrot-specific symmetry/interior predicates
  or analytic derivatives without formula-specific justification.
- [ ] Consider batched native CPU kernels or an alternate JVM only for a
  demonstrated hot loop. Include portability, build/runtime costs and full
  pipeline checks; a language or UI rewrite is not an established speedup.

- [ ] **Exit criterion:** a bounded spike demonstrates a worthwhile gain over the current
  optimized CPU path before adding a second production numerical engine.

### 9.6. Test GPU perturbation/BLA as a different algorithm

- [ ] Build a diagnostic Mandelbrot path using a high-precision CPU reference
  and GPU delta iteration/BLA, with compact rejected-sample recovery from
  original coordinates. Compare with the current CPU rebasing/BLA backend,
  not a naive arbitrary-precision-per-pixel baseline.
- [ ] Establish native error bounds for reference/coefficient conversion, delta
  recurrence, BLA truncation, escape and smooth values. Do not transplant CPU
  radius constants or assume FP32 rescaling supplies extra mantissa precision.
  Add exponent-preserving rescaling only with explicit range checks and tests.
- [ ] Measure BLA-friendly, glitch-heavy and scaled-exponent scenes at both
  target sizes, including CPU reference/table setup, uploads, divergence,
  failures/recovery, first region and complete frame. Reject the spike if the
  CPU's already short skipped workload leaves no end-to-end opportunity.
- [ ] Tune queried workgroup/subgroup behavior, register pressure, compact work
  lists and bounded iteration batches only after a viable numerical kernel
  exists. Preserve generation cancellation, device-loss fallback and memory
  ceilings; never substitute a sampled pilot for per-result validation.

- [ ] **Exit criterion:** pass native sample conformance and a predeclared paired
  performance gate, then production-service/JavaFX/AA validation in 9.9. This is
  unmeasured research and does not enable GPU calculation by default.

### 9.7. Evaluate selective residency and CPU/GPU scheduling

- [ ] In a separate diagnostic selector experiment, model total cost using
  dimensions, iteration distribution, reuse, AA, native warm state and rejection
  density. Compare cheap spatial pilot tiles and recent compatible-frame
  statistics; include pilot and wrong-prediction costs and use hysteresis.
- [ ] Select CPU early for predicted expensive recovery. Check abrupt scene,
  zoom, resize and palette changes; require CPU fallback for unknown/unsupported
  cases. Running the full FP32 frame before falling back cannot produce the
  missing seahorse win and must be accounted for as wasted work.
- [ ] Compare independent CPU/GPU tile assignment with separate recovery only
  when profiling supports it. Avoid duplicated work, excess host workers and
  shared-memory/power contention; keep publication ordering and exact caches.
- [ ] Evaluate warmed large-AA GPU palette animation as an independent narrow
  policy, including initialization, invalidation, small buffers and CPU fallback.

- [ ] **Exit criterion:** predeclare eligible classes and require at least 15% confirmed
  gain on selected cases, no more than 5% regression on controls and bounded first
  publication/cancellation. Passing this new selector experiment does not rewrite
  the failed six-case resident GPU gate or automatically authorize 8.6 expansion.

### 9.8. Keep hardware and API alternatives conditional

- [ ] Through 8.7, test native Vulkan FP64 on actual Windows hardware: query and
  enable support, measure FP64 throughput, validate rounding/FMA/escape/smooth
  semantics and compare direct vs perturbation workloads. Keep the M3 Pro CPU
  path; device capability alone does not establish correctness or performance.
- [ ] Through 8.8, validate Intel/AMD Mac behavior and discrete-memory transfer
  costs separately. Do not assume Metal/MoltenVK exposes native FP64 there.
- [ ] Consider native Metal or external-texture presentation only if profiling
  demonstrates dispatch/translation or readback/presentation dominance. Compare
  an equivalent kernel first and retain a Windows Vulkan path; JavaFX
  PixelBuffer has no public external GPU texture import API.
- [ ] Keep CUDA, OpenCL/SYCL, Java-to-GPU compilers, WebGPU/OpenGL and multiword
  GPU arithmetic as explicitly unmeasured alternatives in the analysis. Reopen
  one only with a supported hardware matrix and a materially different measured
  opportunity. The failed all-rejection double-single route stays closed;
  additional float limbs or API replacement are not presumed remedies.

- [ ] **Exit criterion:** retain an alternative only when its benefit exceeds added
  runtime/packaging complexity and passes the same numerical and application gates.

### 9.9. Promote only validated improvements; separate preview and export

- [ ] For retained optimizations, run portable regressions and applicable native
  and FX gates: sample accuracy, deep BigDecimal controls, palette/AA, feature
  fallback, cache/reuse, resize, cancellation and device loss. Strengthen the
  resident spike's color-only tests to sample-level checks before integration.
- [ ] Re-run paired production-service/JavaFX base and AA benchmarks, cold
  startup, sustained navigation, memory/GC and thermal behavior. Keep CPU
  default until the appropriate production decision gate passes on that device.
- [ ] Only then revisit 8.6 GPU AA sampling/storage, histogram/traps and resident
  reuse/cache. Measure candidate detection and subpixel work separately; moving
  detection alone has already failed as the next hybrid optimization.
- [ ] Evaluate low-resolution/low-iteration or uncertified GPU previews only as
  an explicit approximate display policy. Keep exact sample caches/export
  isolated and converge to the requested precision and iteration budget;
  never preserve a stale cap across a zoom-containing interaction batch.
- [ ] Give tiled high-resolution export, animation batches and optional
  multi-device/distributed rendering their own amortization and correctness
  gates after the stable interactive path. Include tile seams, storage/encoding,
  transfers and cancellation; do not report batch gains as interaction gains.

- [ ] **Exit criterion:** publish reproducible before/after results and an explicit
  retain/reject decision for each completed experiment; only demonstrated,
  compatible improvements enter production.

## Target milestone

- [ ] Deliver a stable interactive Mandelbrot/Julia explorer with smooth pan/zoom,
  measured frame reuse, PNG export, documentation, CI, and a packaged runtime.
