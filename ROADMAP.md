# FractalLens development roadmap

Completed work is summarized first; all unchecked tasks are grouped by function
below. Checkboxes represent acceptance, not implementation alone. Historical
results retain their original code, platform and validation scope; this edit is
not a rerun. Evidence selection and archive limits:
[publication provenance](docs/development/PUBLICATION.md).

## Completed work

### Build, delivery and reliability (P0, P1.1–P1.6; sections 1–3)

- [x] **P0.1/P0.5:** portable macOS/Windows CPU CI and opt-in native lanes;
  Apple Silicon GPU and Windows CPU-fallback reports retained privately.
  Windows/Intel-Mac GPU acceptance remains open. [CI](docs/development/CI.md).
- [x] **P0.2:** versioned runtime artifacts and Windows clean-machine launch.
  Owner accepted the VirtualBuddy/Sonoma exception: default Metal launch crashes,
  explicit software launch works. Physical-Mac clean-machine acceptance remains
  open; signing/release is separate. [Packaging](docs/packaging/RUNTIME_PACKAGING.md).
- [x] **P0.3:** bounded-validity-mask candidate rejected/removed: exactness and
  retained-frame gains passed, aggregate controls remained inconclusive.
  [Decision](benchmarks/VALIDITY_MASK_OPTIMIZATION.md).
- [x] **P1.1:** application errors, keyboard navigation and whole-frame progress.
  **P1.2/P1.3:** retained-snapshot on-demand coloring and queued-callback
  coalescing rejected/removed at their timing gates.
  [Snapshot](benchmarks/RETAINED_SNAPSHOT_PREPARATION.md),
  [publication](benchmarks/FX_PUBLICATION_P1_3.md).
- [x] **P1.4/P1.5:** deep-allocation profile completed; CPU AA promotion passed
  exactness, timing, startup and applicable portable/macOS FX/native/sustained
  checks. Owner accepted the existing retained-Julia first-tile cost (~5 ms);
  attempted first-tile improvements were removed. Optimization remains open.
  [Allocation](benchmarks/ALLOCATION_PROFILE_9_4.md),
  [promotion](benchmarks/P1_5_PROMOTION_VALIDATION.md).
- [x] **P1.6:** full-project IDEA inspection triaged and export discrepancies
  reconciled; no confirmed Critical/Major remains. CSS/SVG errors were dialect/
  schema findings, not demonstrated product errors. Noncritical findings remain;
  no narrower inspection profile was validated.
- [x] Stable portable build, cancellation/reuse regressions and separate profiling;
  measured 1080p/HiDPI pan reuse, immediate ready-row publication and missing-span
  calculation. CPU retains exact cardioid/period-2 rejection, conjugate symmetry,
  32-pixel tiles and `CPU - 1` workers. Periodicity, manual square caching,
  two-lane Vector API and specialized Julia rewrites were rejected.
  [CPU results](benchmarks/FORMULA_BENCHMARK_RESULTS.md).

### User workflows and rendering (P0.4, P2.1–P2.6; sections 4–6)

- [x] **P0.4/P2.2:** immutable scene/independent render target, versioned JSON
  with atomic recovery, last-session parameters, Undo/Redo, bookmarks and shared
  presets. These do not persist calculated frames. Owner accepted workflows
  2026-10-14. [Scene format](docs/architecture/SESSION_FORMAT.md).
- [x] **P2.1:** atomic iteration/Julia editors, exact drafts, validation and session
  restore; portable/macOS desktop checks passed.
  [Acceptance](docs/platform/MACOS_UI.md#p21-acceptance).
- [x] **P2.3:** cancellable tiled CPU PNG export with independent resolution,
  precise deep coordinates, AA/histogram/traps, bounded memory and destination
  preservation on failure/cancel. Owner accepted 2026-10-27; no separate
  independent review/verification. [Contract](docs/architecture/PNG_EXPORT.md).
- [x] **P2.4:** native macOS menus/forms/alerts, nonmodal palette/Help, gestures,
  focus/validation and JavaFX fallback. Owner accepted 2026-10-27, including
  light/dark, accessibility and mixed-DPI negative-origin display checks.
  Accepted limitation: a bottom-corner context menu can leave its display beside
  a taller display. No separate independent review/verification.
  [Acceptance](docs/platform/MACOS_UI.md#p24-acceptance).
- [x] **P2.5:** Julia scaled subnormal deltas: 640x400 at `1e-310` improved from
  113 s exact to ~1.5 s; selected PNGs byte-identical. Independent review and
  Apple Silicon verification passed.
  [Results](docs/development/JULIA_DEEP_ZOOM_PERFORMANCE.md#subnormal-deltas-p25).
- [x] **P2.6:** separate standard/deep Base only, FAST and REFINED settings;
  compatible completed-frame mode switches reuse base/AA. Owner accepted
  2026-10-27; independent Apple Silicon verification passed. Accepted limitation:
  Histogram/Orbit Trap loses finished AA after a Base-only pan.
  [Settings/acceptance](docs/platform/MACOS_UI.md#p26-acceptance).
- [x] Five presets, smooth OKLab coloring, editable palettes/traps, histogram,
  palette animation and adaptive AA; optional jitter removed after comparison.
  Exact LRU reverse reuse, compatible pan/resize base/AA reuse and approximate
  zoom-out reprojection implemented. Analytic distance/image-edge detection is
  combined; Burning Ship/Tricorn retain their nonanalytic detector.
- Platform boundary: workflow acceptance above is primarily macOS; P2.3–P2.6
  Windows/Linux/Intel Mac have portable CI only, not equivalent visible acceptance.

### Deep zoom (7.1–7.7)

- [x] Backend-neutral jobs/storage, exact decimal navigation/grids and
  capability-based CPU selection; restoration/navigation no longer accumulates
  guard digits and render grids retain their required precision.
- [x] Mandelbrot reference perturbation, bounded orbit caching, multi-reference
  recovery and BigDecimal fallback; scaled-exponent deltas preserve deep range.
  Sample/frame correctness and performance gates completed before GPU work.

#### 7.6. [x] Evaluate secondary deep-zoom optimizations

- [x] Standard-double Mandelbrot rebasing/BLA in base and precise AA;
  scalar tails/fallbacks retained. Cubic-series skips rejected (small gain or
  smooth-tolerance failure); scaled rebasing/BLA remains research in 9.4.
  Wheel debounce 30 ms, pinch completion flush and immediate first batch reduce
  interaction delay. [Results](benchmarks/FORMULA_BENCHMARK_RESULTS.md).
- [x] Julia uses pixel `z0`, fixed binary-double `c`, its own shared-reference
  perturbation/scaled deltas and BigDecimal recovery in base/AA/export;
  critical-point contraction is covered. No Mandelbrot cardioid/rebasing/BLA
  assumptions apply. [Julia](docs/development/JULIA_DEEP_ZOOM_PERFORMANCE.md).

### GPU groundwork and decisions (8.1–8.7)

- [x] Optional LWJGL/Vulkan/MoltenVK runtime, bounded ownership/cancellation,
  device-loss handling and portable CPU fallback; initial Apple M3 Pro checks.
  Windows x64 runtime/packaging implemented; Windows 11 lifecycle/injected-loss/
  fallback checks and five GTX 1650 Max-Q smoke tests passed. This is not full
  Windows GPU acceptance. [Runtime/platform scope](docs/platform/GPU_RUNTIME.md).
- [x] GPU palette animation validated; repeated Retina pairs favor GPU only for
  large AA. CPU stays default. [Results](benchmarks/PALETTE_BENCHMARK_RESULTS.md).
- [x] Opt-in certified FP32 Mandelbrot with exact CPU recovery and progressive
  readback: exact iterations/escape, smooth error <= 0.01 in measured native gates.
  Whole-frame unchecked FP32 is rejected; histogram/traps/deep remain on CPU.
  [Precision](benchmarks/GPU_FP32_PRECISION.md), [native](benchmarks/GPU_FP32_NATIVE.md).
- [x] **8.4/8.5:** correctness passed, all ten workload/size cases slower on GPU
  after host/kernel optimization. **8.6:** resident spike failed the 15% cross-scene
  gate; base/color-only, pre-JavaFX gains do not establish AA/sample conformance.
  Candidate detection alone cannot close the deficit. Centered-error FP32 and
  double-single recovery rejected/removed; CPU default and residency gates stand.
  [8.4](benchmarks/GPU_RENDER_BENCHMARK_RESULTS.md),
  [8.5](benchmarks/GPU_RENDER_BENCHMARK_8_5_RESULTS.md),
  [residency](benchmarks/GPU_RESIDENCY_8_6_DECISION.md),
  [spike](benchmarks/GPU_RESIDENT_SPIKE_RESULTS.md).

### Measurement, CPU AA and wide preview (9.1–9.2, 9.9)

- [x] Exact fixture matrix, separate headless/ARGB/FX/input scopes, scheduling/
  cancellation tracing, paired runner and A/A calibration. Sustained allocation/
  memory diagnostics and detached-surface listener fix validated. Physical
  scanout, energy/temperature and all-platform/full-matrix soaks remain unmeasured.
  [Matrix](benchmarks/BASELINE_BENCHMARK.md),
  [FX](benchmarks/BASELINE_FX_VALIDATION.md),
  [input](benchmarks/BASELINE_INPUT_VALIDATION.md),
  [scheduling](benchmarks/BASELINE_SCHEDULING_VALIDATION.md),
  [memory](benchmarks/BASELINE_MEMORY_VALIDATION.md),
  [pairs](benchmarks/BASELINE_PAIRS_VALIDATION.md).
- [x] CPU AA immutable snapshots, worker-local batches and generation-checked
  admission removed measured map contention; exact compared pixels, 2.05–6.46x
  AA-only gains in recorded direct workloads. No meaningful deep-AA gain.
  [Results](benchmarks/CPU_AA_OPTIMIZATION_RESULTS.md).
- [x] AA cache accounting corrected; 512 MiB budget retains every measured
  3024x1964 AA frame. Primitive/distance-only AA experiments remain out of
  production after RSS rejection or inconclusive follow-up.
  [Memory evidence](benchmarks/AA_MEMORY_VALIDATION.md).
- [x] Off-FX spare frame preparation retained under timing gate v2 with fixed
  pair counts (without A/A calibration) and memory gate v2; this is not a new
  Prism-pipeline verdict. [Results](benchmarks/SPARE_FRAME_STORAGE_RESULTS.md).
- [x] CPU low-resolution wide preview enabled by default after TG2-4: all 56
  timing groups, memory v2 G1/G2, availability/thermal and exact controls passed;
  overall ratio 0.973 (0.946–0.997). Settings override persists; reserve uses the
  visible iteration limit. Earlier rejected/inconclusive/aborted campaigns stand.
  QB2 subsequently retained the 256 MiB/continued-work policy at default settings
  on the measured Apple Silicon matrix, with active-desktop and inhibitor-cleanup
  checks. Other preview settings/platforms/sessions remain open in 9.9.
  [QB2 decision](benchmarks/WIDE_PREVIEW_VALIDATION.md#byte-budget-comparison-qb2-with-sleep-inhibition-2026-11-0910).
  [Decision/history](benchmarks/WIDE_PREVIEW_VALIDATION.md#follow-up-campaigns-tg2-3-aborted-and-tg2-4-retain),
  [timing gate](benchmarks/TIMING_GATE_V2.md), [memory gate](benchmarks/MEMORY_GATE_V2.md).

### Metal acceptance already recorded (P1.7, partial)

- [x] Owner selected Metal for Spaces reliability on 2026-10-29 and accepted the
  latency trade-off; historical performance `fail`/`inconclusive` results stand.
  ES2 stale-frame reproduction versus Metal's successful trials is bounded
  one-host evidence, not proof. Owner manual activation checks passed on M3 Pro;
  no new controlled ES2 comparison. [Results](benchmarks/PRISM_PIPELINE_RESULTS.md),
  [Spaces scope](benchmarks/SPACE_SWITCH_VALIDATION.md).
- [x] Local fallback initialization (missing Metal -> ES2; missing both -> SW),
  Metal/Vulkan coexistence, graphical profiles/cold-startup functional smokes,
  seven exact exported-PNG pairs and four previously skipped fullscreen cases
  passed. Packaged temporary-location smoke passed without a development JDK;
  it is not clean-machine/standard-location acceptance. Generated deployment
  metadata corrected to observed binary floor 11.0; minimum-OS runtime support
  remains unverified. [Checklist/evidence](docs/platform/METAL_MIGRATION_CHECKLIST.md),
  [metadata](docs/packaging/RUNTIME_PACKAGING.md#deployment-metadata-correction-2026-10-29).

## Remaining work by function

Execution order: finish outstanding P1.7 acceptance, then P2.7 render retention;
CPU research (9.2–9.5) follows higher-priority gates, one bounded spike at a time.
GPU research follows 9.6 -> 9.7 -> 9.9; API/hardware alternatives in 9.8 stay last.
Hardware checks may run when equipment is available. Completed P0.3 does not
require repeating its historical campaign; each new spike needs a current,
scope-matched baseline (9.1). Direct user tasks take precedence.

### macOS desktop and Metal

#### P1.7. Evaluate Metal as the default macOS Prism pipeline

- [ ] Complete final acceptance against the
  [migration checklist](docs/platform/METAL_MIGRATION_CHECKLIST.md). Shared
  `FractalLauncher` startup selection is implemented: macOS-only `mtl,es2,sw`
  before JavaFX, preserving user overrides and Maven/jpackage entry points.
  Final-candidate correctness/lifecycle/packaging/platform acceptance remains
  open. Owner's performance waiver requires no new timing campaign and closes
  no other gate; screen-latency and long-session RSS/GPU comparisons remain
  unperformed. Bounded worker preparation was rejected and remains benchmark-only.
- [ ] Finish standard-location and clean-machine DMG installation, Gatekeeper
  and visible installed Dock-tooltip acceptance. Temporary-install smoke is
  insufficient. Recheck VirtualBuddy separately: historical Metal crash/SW
  workaround persists; no guest was available in the latest local run.
- [ ] Establish the supported minimum macOS for JDK/JavaFX/AppKit/launcher/bundle
  together and execute there. Binary floor/plist 11.0 is not runtime acceptance.
- [ ] Check external-display hotplug, mixed Retina scales/refresh rates and frames
  above 4096 physical pixels, including seams and memory. Record Windows/Linux
  and additional Mac configurations; M3 Pro results do not validate other Macs.
  Packaging/initial-render Windows follow-up fixes do not close physical-dialog,
  mixed-DPI or clean-machine gaps.
- [ ] Resolve ES2/SW fallback focus repaint using Spaces evidence; reconcile the
  detailed checklist with final acceptance without inventing controlled-trial
  counts. Assess keyboard exit from fullscreen against platform conventions.
  The owner also reported base-before-AA on window enlargement in Antialiased
  display; retain this as a separate behavior issue pending its own closure.
- [ ] Complete final CI and record the exact committed candidate after review/
  delivery authorization. Local acceptance alone authorizes no commit, push or
  workflow dispatch. Platform gaps stay open until executed or explicitly accepted.

### 4. Exploration and comparison workflows

- [ ] **Mandelbrot -> Julia at a selected point:** add an action that maps the
  selected Mandelbrot coordinate to Julia's fixed `c` and opens its initial view.
  Preserve the source scene for return/Undo. Keep selection coordinates exact
  until the Julia parameter boundary; define supported precision and validate
  any conversion explicitly. Verify center/edge clicks, pan/zoom/Retina mapping,
  Julia parameters, history and cancellation without stale pixels. A live Julia
  preview may follow as a separately bounded interaction feature.
- [ ] **Interesting-places gallery:** ship a curated set of named scenes with
  descriptions and previews, grouped by fractal. Open entries through the same
  scene/history pipeline as bookmarks; preserve exact coordinates and parameters.
  Validate fixtures against the current renderer, version the bundled catalog
  and keep user bookmarks independent of catalog updates. Cover missing/invalid
  entries, navigation, visible loading and supported render/export modes.
- [ ] **Comparison mode:** show two independently configured views side by side
  for formulas, Julia parameters, palettes or display quality. Offer explicit
  linking of compatible viewports; define coordinate/camera mapping and reject
  incompatible linking. Keep focus, history, cancellation and publication scoped
  to each view; share bounded worker/memory budgets. Verify independent/linked
  navigation, resizing, closing and switching without cross-view stale pixels;
  measure interaction latency with both views rendering.

### 5. History and render persistence

#### 5.1. Completed-render retention and persistence (P2.7)

Goal: returning to a retained view, including after application restart, reuses
calculated samples instead of repeating base and antialiasing work. A bounded
cache cannot guarantee that every previously calculated sample survives eviction
or remains compatible with a new scale, target size or iteration limit.

Planning baseline (2026-11-08): `RenderFrameCache` retains completed base frames
with a 192 MiB budget but is cleared when the formula changes. Last-session
storage saves scene parameters only. AA samples have a separate current-frame
lifecycle. Fractal selection resets the viewport to the preset's home view, so
retaining frames alone will not restore the user's previous exploration.

- [ ] **Stage A: retain results within one process.** Reuse the existing cache
  and compatibility planner across formula switches; keep one shared memory
  budget rather than allocating a full cache budget per fractal. **Remember each
  fractal's last view** (coordinates, scale and parameters) and restore it on
  return; keep Reset View explicit and
  define how Julia parameter edits, history and current application render
  settings interact with restored scenes. Retain compatible AA data alongside
  its base frame, with explicit ownership and eviction accounting.
- [ ] **Stage B: persist completed results across launches.** Store numerical
  base samples and the original render grid, including exact decimal values,
  offsets and symmetry metadata; a PNG alone cannot support palette changes or
  exact navigation reuse. Add AA persistence after base-frame persistence.
  Version the cache format and calculation semantics independently of scene
  JSON; key compatibility by formula/parameters, viewport/grid, render dimensions,
  iteration limit and sample accuracy. Include AA pattern/quality and encoding
  dependencies such as color scale where applicable. Palette changes may reuse
  only data whose representation supports them.
- [ ] Use background, bounded I/O with lazy loading and explicit disk eviction.
  Prefer writing after frame completion instead of starting a large save at exit.
  Publish complete files atomically, validate metadata, lengths and integrity
  before allocation, and reject incomplete, corrupt or incompatible entries.
  Cache misses, unavailable storage and write failures fall back to normal
  rendering without damaging scene persistence. Define bounded shutdown and
  cancellation; immutable snapshots must never mix render generations.
- [ ] Establish memory/disk budgets from actual retained data, including AA,
  temporary snapshots and loading buffers. At 3024x1964, direct CPU sample arrays
  alone are approximately 74 MiB without orbit-trap distances or 119 MiB with
  them, before masks, AA and other overhead. Compression gains and I/O latency
  require measurement; these sizes are arithmetic estimates, not RSS evidence.
- [ ] **Correctness acceptance:** compare stored/restored samples and complete
  displayed frames with their source, including deep Mandelbrot and Julia,
  other presets, palette changes, all display modes and parameter changes.
  Cover exact hits, eviction/misses, pan in all directions, zero/no overlap,
  resize/DPI changes, iteration changes, rapid switching, cancellation and close
  during writes. Prove fully compatible hits perform no base or AA resampling;
  partial AA retention computes only missing work. Test format invalidation,
  interrupted/corrupt writes and read-only/unavailable storage. Run portable
  and relevant JavaFX tests plus manual switch/restart checks; report platform
  coverage separately.
- [ ] **Performance acceptance:** predeclare limits and compare cold misses,
  warm memory hits and disk hits at realistic window sizes, including deep
  zoom. Measure switch/startup-to-visible latency, full completion, shutdown,
  peak RSS, retained memory and disk usage. Cache overhead must remain bounded;
  publish a retain/reject decision before marking either stage complete.

Preliminary engineering estimates, including tests and application checks:

| Increment | Estimated effort |
| --- | --- |
| Retain base frames across fractal switches | 1–2 days |
| Retain AA across switches | Additional 2–3 days |
| Persist completed base frames | Additional 3–5 days |
| Persist AA data | Additional 2–4 days |

These are planning ranges from code inspection, not measured commitments.
Include last-scene restoration in Stage A planning and refine the estimate after
its history/settings semantics and the snapshot/serialization design are fixed.

### 6. New fractal families

These are separate future feature stages. They do not reorder the existing
acceptance/research priorities or extend the current Mandelbrot/Julia milestone.
Define each family's formula, parameters and numerical/rendering contract before
implementation; do not assume existing escape-time, deep-zoom or cache rules apply.

#### 6.1. Additional 2D fractals

- [ ] **Burning Ship Julia:** fixed-parameter Julia variant of the Burning Ship
  map; specify the recurrence, pixel initial value, parameter controls and
  supported precision separately from Mandelbrot/Julia perturbation.
- [ ] **Newton:** root-basin rendering with a defined initial polynomial,
  convergence/singularity criteria, root identification and basin coloring.
- [ ] **Nova:** define the initial variant, recurrence, starting values and
  parameter controls; implement its convergence and coloring rules explicitly.
- [ ] **Magnet:** select and document the initial variant(s), parameters,
  convergence/escape rules and treatment of singularities.
- [ ] **Lyapunov:** parameter-plane rendering with configurable symbol sequence,
  transient/measurement iterations and stable numerical classification.
- [ ] **Barnsley Fern / IFS:** weighted affine transforms, reproducible seeded
  point generation and progressive density/point rendering with bounded storage.
- [ ] **L-system:** configurable axiom, production rules, generation limit,
  turn angle and 2D turtle geometry; bound expansion size and rendering work.
- [ ] **Acceptance:** expose family-specific controls and Reset View; version
  scene/bookmark/session serialization and cache identity for the new parameters.
  Verify known sample/image fixtures, deterministic output where applicable,
  invalid inputs, bounded cancellation/memory and isolation from old generations.
  Validate navigation, palette/AA/export behavior supported by each family and
  clearly expose unsupported modes. Keep existing presets unchanged; run portable,
  relevant JavaFX and manual checks at realistic window sizes. Deep zoom and GPU
  acceleration require separate family-specific correctness/performance gates.

#### 6.2. 3D fractals

- [ ] **Mandelbulb:** define the initial power/parameters, iteration and bailout
  policy, surface/distance evaluation and numerical limits.
- [ ] **Mandelbox:** define folding/scaling parameters, iteration and bailout
  policy, surface/distance evaluation and numerical limits.
- [ ] **3D foundation:** design a dedicated scene/camera and rendering contract
  for orbit/pan/dolly navigation, clipping, surface evaluation, lighting/materials
  and background. Integrate parameter editing, Reset View, history/bookmarks,
  session persistence and image export without overloading 2D complex viewports.
- [ ] **Acceptance:** choose/document the initial rendering method and supported
  CPU path before implementation; any optional GPU backend needs a CPU reference
  and fallback. Verify reference scenes, camera/parameter round trips, numerical
  failure handling, progressive cancellation and no stale scene publication.
  Measure first-visible/full-frame latency and memory at realistic window sizes;
  validate lighting/AA/export and platform coverage separately. This stage does
  not imply mesh export, arbitrary-precision 3D zoom or GPU-by-default promotion.

### Animation and video export

- [ ] **Journey video export:** author keyframes for view position/zoom and
  supported formula, Julia and palette parameters; configure duration, frame
  rate, output dimensions and quality. Define interpolation, exact deep-zoom
  coordinate handling and transitions between incompatible scene types.
- [ ] Save/version the journey and resumable render job. Render frames off the
  JavaFX thread with bounded memory, progress and cancellation; resume only
  compatible completed frames. Define the initial video container/codec and
  encoder distribution/dependency policy before implementation, including
  platform availability and failure recovery.
- [ ] **Acceptance:** deterministic keyframe/interpolation fixtures, correct
  frame count/order/duration and selected decoded-frame comparisons against
  standalone scene exports. Cover interruption/resume, invalid parameters,
  encoder/disk failures and destination preservation on cancellation/failure.
  Measure realistic sequence time/storage/memory; verify exported playback and
  applicable macOS/Windows/Linux paths. Apply the batch-rendering gates in 9.9;
  interactive preview timings do not establish offline export performance.

### 8. GPU residency and platform acceptance

CPU remains the mandatory default/fallback. Failed 8.4–8.6 performance gates
keep production residency expansion conditional; diagnostic 9.6/9.7 work does
not reopen it. Initial validation is Apple Silicon; Windows and Intel/AMD Mac
need separate hardware acceptance.

#### 8.6. Expand GPU residency incrementally only when justified

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

#### 8.7. Implement and validate the Windows GPU runtime

- [ ] Validate startup, simultaneous runtimes, shutdown/reopen, device loss,
  missing natives, and direct/deep CPU fallback on Windows 10/11.
  - [ ] Windows 10 execution and real driver-reset/device-loss acceptance.
- [ ] Validate palette recoloring, AA/frame/palette invalidation, and fallback;
  record paired CPU/GPU timings including transfer and JavaFX publication costs.
- [ ] Run calculation conformance and performance gates for implemented GPU
  modes on Windows hardware before enabling them there.
  - [ ] Full paired performance campaign and remaining platform/visible gates.
  Exploratory timings are historical pre-transfer observations, not promotion
  evidence. CPU stays default. See `docs/platform/GPU_RUNTIME.md` for provenance.
  The pre-existing deep Julia deadline has a separate UI-test correction and
  open latency follow-up in `docs/development/WINDOWS_DEEP_JULIA_FX_FOLLOWUP.md`.

#### 8.8. Validate the Intel/AMD Mac GPU path

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

### 9. Pursue CPU/GPU optimization from the measured bottlenecks

Use [the analysis](benchmarks/CPU_GPU_OPTIMIZATION_ANALYSIS.md) for the evidence, current
code entry points, alternative approaches and rejected experiments. Follow the
execution order above; establish the current 9.1 gate before each selected
9.2–9.5 CPU follow-up. Section 9.6 is the preferred
new GPU algorithm experiment; 9.7 and 9.8 are conditional alternatives.
Production GPU expansion remains gated by 8.6 and the final checks in 9.9.
Windows and Intel/AMD Mac work stays in 8.7/8.8.

#### 9.1. Establish comparable baselines and close profiling gaps

- [ ] Before each spike, refresh a scope-matched baseline and predeclare targets,
  controls and correctness/performance gates using the existing fixture matrix.
  Separate backend, returned ARGB, JavaFX publication and input scopes; handler/
  post-layout timestamps do not establish physical scanout latency. Include cold/
  warm behavior, cancellation, allocation, memory/GC and sustained thermal checks.
  Use uninstrumented alternating process pairs for decisions, separate JFR/native
  profiles for attribution, at least 30 measured pairs and multiple process starts
  unless an explicitly accepted protocol supersedes that budget. Do not run
  competing timing suites or overwrite historical CSVs.
  [Paired protocol](benchmarks/BASELINE_PAIRS_BENCHMARK.md).

#### 9.2. Reduce CPU antialiasing synchronization and repeated work

- [ ] Reduce the retained-snapshot color preparation barrier without restoring
  shared-cache contention.
  The existing retained-Julia path trades ~5 ms first-tile latency for faster
  completion, accepted only for the existing cache. On-demand coloring was
  rejected/removed; compare a compact immutable lookup or another new design
  against both first/full publication, palette reuse and memory.
  [Prior experiment](benchmarks/RETAINED_SNAPSHOT_PREPARATION.md).
- [ ] Compare an AA-enabled distance/candidate sidecar in the base pass with the
  current second derivative-orbit calculation for non-edge pixels. Include
  base-pass/first-publication cost and keep image-space edge detection for
  interior-centered boundaries; retain the current path for non-analytic maps.
- [ ] Separately evaluate nested/adaptive subpixel sampling only if sampling
  still dominates. Current direct 4x4 and deep 2x2/4x4 positions are not
  automatically reusable nested grids. Treat changed positions/sample counts
  as a quality-policy change with dense-reference image comparisons,
  palette-independent caches and exact export semantics.

- [ ] **Exit criterion:** unchanged-quality work passes sample/color and retention tests,
  with a confirmed full-AA improvement under the 9.1 gate and no material first
  publication regression. Sampling-quality experiments have a separate decision.

#### 9.3. Improve CPU batching, scheduling and publication

- [ ] Bound reusable `ValidityMask.missingRowSpans` scans to their row/region,
  avoid repeated complete-frame scans, and check cancellation during task
  preparation. The [9.1 scheduling probe](benchmarks/BASELINE_SCHEDULING_VALIDATION.md)
  attributes roughly 5 seconds at 3024x1964 to planning with zero worker tasks,
  including a similar cancellation tail. Add exact-mask/reuse and deterministic
  cancellation regressions, then apply the 30-pair/multiple-process gate before
  claiming an interaction or throughput gain.
  Prior bounded-mask candidate was rejected/removed despite exactness and
  target gains; a new implementation/tests must pass the full control gate.
  [Decision](benchmarks/VALIDITY_MASK_OPTIMIZATION.md).
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
  The [P1.3 publication experiment](benchmarks/FX_PUBLICATION_P1_3.md)
  characterized the local costs and rejected queued-callback coalescing at
  the 30-pair end-to-end gate. A retained improvement remains open.

- [ ] **Exit criterion:** each retained change improves its declared workloads and keeps
  overview, deep scenes, interaction and cancellation within the control budget.

#### 9.4. Extend the successful CPU perturbation/BLA path

- [ ] Profile BLA preparation/lookup, block objects, coordinate setup and sample
  publication once skipping dominates. Compare primitive block arrays and
  tile-local delta bounds while retaining the existing strict accuracy controls.
  The [9.1 allocation profile](benchmarks/BASELINE_MEMORY_VALIDATION.md) identifies repeated
  precise AA coordinate construction and `ReferenceOrbit.cRealAsDouble` /
  `cImaginaryAsDouble` conversions as concrete allocation candidates. The
  [P1.4 profile](benchmarks/ALLOCATION_PROFILE_9_4.md) confirms their current
  scope and fixture concentration. First evaluate caching immutable reference
  conversions and reusing exact coordinate components; preserve BigDecimal
  operation order and the same sample controls.
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

#### 9.5. Investigate conditional CPU numerical and spatial algorithms

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

#### 9.6. Test GPU perturbation/BLA as a different algorithm

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

#### 9.7. Evaluate selective residency and CPU/GPU scheduling

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

#### 9.8. Keep hardware and API alternatives conditional

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

#### 9.9. Promote only validated improvements; separate preview and export

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
  CPU low-resolution wide preview is already retained (see completed work).
  The default byte-budget policy passed QB2 on the measured Apple Silicon matrix
  (see completed work). Remaining scope: non-default preview extent/quality,
  sustained/deep-zoom behavior under the 256 MiB/continued-work policy,
  low-iteration/GPU preview policies and untested platforms/sessions. New policies
  need separate declared correctness, timing and memory decisions; prior campaign
  results do not transfer automatically.
- [ ] Give tiled high-resolution export, the journey-video animation batches
  above and optional multi-device/distributed rendering their own amortization and correctness
  gates after the stable interactive path. Include tile seams, storage/encoding,
  transfers and cancellation; do not report batch gains as interaction gains.

- [ ] **Exit criterion:** publish reproducible before/after results and an explicit
  retain/reject decision for each completed experiment; only demonstrated,
  compatible improvements enter production.

### Target milestone

- [ ] Deliver a stable interactive Mandelbrot/Julia explorer with smooth pan/zoom,
  measured frame reuse, PNG export, documentation, CI, and a packaged runtime.
