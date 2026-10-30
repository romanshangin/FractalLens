# FractalLens development roadmap

CPU/GPU optimization review (2026-09-05):
[analysis, experiments and validation rules](benchmarks/CPU_GPU_OPTIMIZATION_ANALYSIS.md).
CPU stays default after failed 8.5/resident 8.6 gates. Section 9 orders the next
work: comparable measurements, CPU AA synchronization, CPU/deep-zoom improvements,
then different GPU algorithms. Completed/rejected experiments are historical;
unchecked proposals are not measured speedups.

Historical completion refers to original validation, not a migration rerun or
extended acceptance. The smaller public evidence set retains original SHAs and
archived-input/replay limits: [publication provenance](docs/development/PUBLICATION.md).
Public configuration removes original self-hosted lanes and automatic raw-report
uploads; hardware checks are manual with unchanged gates. See [CI](docs/development/CI.md).

Checkboxes represent acceptance state: a parent item remains open until every
required implementation and validation step beneath it is complete.

## 0. Prioritized backlog

Execution order for all unfinished work; detailed sections own requirements and
acceptance. Lower priority postpones an item until dependencies, evidence or
delivery foundations are ready; it does not cancel it.

### P0. Infrastructure and delivery foundation — do first

- [x] **P0.1. Make the build continuously reproducible.** CPU-default portable
  macOS/Windows CI: dependency caching, clean checkouts, uploaded test reports.
  Hardware-native GPU suites require explicit opt-in jobs; ordinary CI is not
  GPU conformance evidence.
- [x] **P0.2. Produce installable, smoke-tested runtime artifacts.** Automated,
  repeatable, versioned `jlink`/`jpackage` macOS/Windows pipeline: JavaFX/LWJGL
  natives, applicable AppKit bridge, icons, launch options, licenses, CPU fallback,
  provenance and clean-machine checklist. Signing/notarization may be separate.
  Pipeline/build-host smoke: `scripts/packaging/package_runtime.py`,
  `.github/workflows/runtime-artifacts.yml`. Both hosted jobs and Windows
  clean-machine checks passed. Owner accepted P0.2 with a VirtualBuddy/Sonoma
  exception: default Finder launch crashes in virtual Metal; software launch and
  manual functionality pass. Default macOS clean-machine launch did not pass;
  physical clean-machine behavior is unverified. Evidence/release boundary:
  `docs/packaging/RUNTIME_PACKAGING.md`.
- [x] **P0.3. Close the current measurement gate before starting another
  optimization.** Finish 9.1 paired decision and implemented 9.3 bounded-`ValidityMask`
  candidate with fixed power, fresh outputs, at least 30 alternating pairs and
  multiple process starts. Explicitly retain/reject; no permanently provisional
  production code. Two fresh 30-process-pair campaigns confirmed retained-frame
  gains but left aggregate controls inconclusive: candidate rejected/removed.
  See [final decision](benchmarks/VALIDITY_MASK_OPTIMIZATION.md).
- [x] **P0.4. Turn reproducibility into a product contract.** Section 5 versioned
  JSON/last-session restore precedes undo/redo, shared presets and new exports.
  Test migration, malformed input, exact decimal coordinates and interrupted-write
  atomic recovery. Version 2, version 1 migration, platform paths and recovery:
  `docs/architecture/SESSION_FORMAT.md`.
- [x] **P0.5. Define platform validation lanes.** Hardware-backed Windows runtime
  (8.7) and Intel/AMD Mac (8.8) tracks may run when hardware is available without
  blocking CPU-default macOS artifacts, but must precede GPU activation there.
  Manual native workflow has a physical Intel Mac x64 lane; Windows x64 stays a
  CPU-fallback contract until GPU implementation. Runner/evidence/promotion rules:
  `docs/development/CI.md`. Neither hardware acceptance is complete.

- [x] **P0 exit criterion:** a clean checkout is tested automatically, produces a
  traceable installable artifact, restores a versioned scene safely, and the
  current bounded-mask candidate has a recorded retain/reject decision.

### P1. Production reliability and measured latency

- [x] **P1.1.** Replace stack traces with application errors; add section 4
  progress/status and keyboard navigation. Render/recolor failures use the error
  dialog; the viewport badge reports exact base progress and completion/refinement.
  Arrow keys, Page Up/Down and Home navigate the focused canvas.
- [x] **P1.2.** Run 9.2 retained-snapshot preparation; require both first-tile and
  full-frame acceptance. On-demand tile coloring matched exact pixels but failed
  the joint timing gate: [rejected](benchmarks/RETAINED_SNAPSHOT_PREPARATION.md).
- [x] **P1.3.** Measure 9.3 dirty rectangles, callback coalescing, staging reuse and
  redundant copies. Visible JavaFX probe characterized update/copy/buffer costs.
  The 30-pair end-to-end queued-callback candidate failed timing and was removed;
  no production change retained. [Decision](benchmarks/FX_PUBLICATION_P1_3.md).
- [x] **P1.4.** Profile 9.4 allocation candidates, especially immutable reference
  conversion and precise AA coordinates. Current-source JFR confirms substantial
  deep-render allocation at both sites: [profile](benchmarks/ALLOCATION_PROFILE_9_4.md).
  Optimization/retain-reject gate remains open in 9.4.
- [x] **P1.5.** Retained changes must pass 9.9 portable/native/FX and sustained gates
  within the change. CPU AA passed current-source 652-second exact navigation/memory,
  portable/macOS FX and initial/final active-desktop native checks. Seven first-tile
  candidates used a frozen control: D passed headless/cold startup but its 120-pair
  FX confirmation left two fresh-AA first-tile controls inconclusive; E/F passed
  retained first-tile targets but left full AA inconclusive at 5%; G failed its
  target in 60 headless pairs. All were removed. Owner accepted the original
  retained-Julia first-tile cost (about 5 ms) solely for the existing cache change.
  Exact output and applicable portable/macOS FX/native/sustained checks passed.
  Current-source versus pre-optimization AA passed all eight headless and 16 FX AA
  groups. Three initially inconclusive base-publication controls prompted a
  predeclared 240-pair confirmation, passing four base-only and four AA controls.
  The 30-pair AC cold-startup gate passed. Promotion is complete under the exception;
  first-tile optimization remains open in 9.2.
  [Assessment](benchmarks/P1_5_PROMOTION_VALIDATION.md).

### P1.6. Triage the full-project IntelliJ inspection report

- [x] **P1.6. Triage the full-project IntelliJ inspection report.**
  2026-09-28 `Inspect Code`, `Project Default`, whole project including tests:
  IDEA 25,829; SARIF 25,810 (18 errors, 293 warnings, 25,499 notes). Later matched
  run: IDEA 25,936; SARIF 25,920; XML 25,942. Its 16-item IDEA/SARIF gap is 22
  omitted `unused` method/constructor warnings minus six extra exported spelling
  entries; the earlier 19-item gap is a different run. Most findings proofread
  benchmark output, retained snapshots and historical evidence. Tool severity
  requires runtime/product impact confirmation before fixing.
  All high-risk cases have source dispositions: three nullable shaderc byte results
  are guarded before Vulkan; remaining ownership/arithmetic/module/benchmark
  contracts stay intact. Inventory records all 22 XML-only entries and rule groups.
  Full-scope post-fix run: IDEA 25,983; SARIF 25,967, with all 16 omitted `unused`
  warnings accounted for and shaderc data-flow warnings gone. No confirmed Critical
  or Major remains. The 18 IDEA errors are 12 generic-CSS JavaFX-syntax findings
  and 6 schema findings in a well-formed retained SVG, not product errors.
  Remaining findings are noncritical, with higher-risk source dispositions.
  A narrower profile is optional hygiene, not a P1.6 gate. Work order:
  - [x] Audit correctness/lifecycle first: module visibility (62), JavaFX reflection
    (8), nullability/data flow (6), unmanaged `AutoCloseable` (33), exact
    `BigDecimal.divide` (2), loop waits (3), ignored returns (1), always-returning
    methods (2). Trace callers/lifecycles; fix defects with regressions. Record
    intentional contracts/false positives without weakening module, precision or
    resource guarantees.
  - [x] Resolve markup/framework findings: 12 CSS errors (`:focused`, `derive(...)`
    are JavaFX), 81 unknown properties, 28 unused selectors against actual styles;
    inspect 6 SVG XML errors, preserving evidence. Fix defects or adjust unsupported
    dialect/historical-artifact scope. Source and JavaFX checks found no CSS defect;
    retained SVG parses, so schema warnings do not prove runtime/artifact failure.
  - [x] Review production/benchmark maintainability: visibility leaks (62), parameters/
    dataflow (10 same-value, 6 field mutability, 4 field-to-local), exception printing
    (5), unused declarations/parameters (3), string conversions (3), `Optional`
    fields/parameters (4), other low-count style/API items. XML exposes 22 SARIF-omitted
    declarations, including entry points/API hooks; dispositions preserve public
    contracts and measured benchmark behavior.
  - [x] Classify noise as noncritical: later export has 25,283 spelling, 96 grammar,
    124 style, 77 Markdown-table and 11 HTTP-link notes, mostly generated output,
    snapshots/licenses. Preserve artifacts. A future profile may exclude proofreading
    and restrict CSS/XML to supported dialects; none was created/validated here.
  - [x] Rerun documented full-project inspection, export/reconcile IDEA/SARIF totals,
    record fixes and accepted noncritical findings. Every high-impact candidate is
    fixed or evidenced intentional; not every warning disappeared.

### P1.7. Evaluate Metal as the default macOS Prism pipeline

- [ ] Complete acceptance of the Metal-first macOS Prism startup candidate
  against the [Metal migration checklist](docs/platform/METAL_MIGRATION_CHECKLIST.md).
  The owner accepted the measured latency trade-off on 2026-10-29 and selected
  Metal for its Spaces presentation reliability. This waives the performance
  gate for activation, not the remaining correctness, lifecycle, packaging or
  platform checks; historical `fail` and `inconclusive` results stand.
  Motivation: on ES2 a frame completed while the
  window is on another Space can stay invisible after returning; a manual check
  did not reproduce this with `-Dprism.order=mtl`.
  - [x] Run the first ES2/Metal timing comparison with the
    [Prism pipeline protocol](benchmarks/PRISM_PIPELINE_BENCHMARK.md). Result:
    `inconclusive`, 66 of 103 groups pass and none fail; see
    [the results](benchmarks/PRISM_PIPELINE_RESULTS.md).
  - [x] Record the owner's 2026-10-29 decision to accept the measured latency
    trade-off for the reliability fix, including the later failing publication
    gate. This is a product decision, not a performance PASS. No further
    performance tuning or confirmation campaign is required for this decision.
    The [predeclared confirmation campaign](benchmarks/PRISM_PIPELINE_BENCHMARK.md#predeclared-confirmation-campaign-p17)
    retains all 103 groups with larger independent process-pair budgets.
    The publication campaign ended `fail` (19 of 28 pass, 8 inconclusive,
    3024x1964 Refined first visible publish fails), which rejects default
    activation under this gate; the screen-latency campaign was not executed.
    See [the confirmation results](benchmarks/PRISM_PIPELINE_RESULTS.md#confirmation-campaign-p17-2026-10-28).
    After the frame-preparation change in `2a19472`, a
    [predeclared post-change campaign](benchmarks/PRISM_PIPELINE_RESULTS.md#post-change-publication-campaign-p17-2026-10-28)
    ended `fail` again (25 of 28 pass, 2 inconclusive, 1512x982 Refined first
    visible publish fails by about 0.1 ms); the former failing group is now
    inconclusive. Opt-in per-request JFR boundaries and a
    [GC overlap analyzer](benchmarks/PRISM_PIPELINE_BENCHMARK.md#sample-to-gc-correlation-diagnostics-p17)
    were exercised in a [three-pair desktop diagnostic](benchmarks/PRISM_PIPELINE_RESULTS.md#desktop-sample-to-gc-correlation-diagnostic-2026-10-29).
    The historical failing group did not reproduce, and slow planning also
    occurred without a GC pause; its proposed GC cause remains inconclusive.
    A [fresh-frame decomposition diagnostic](benchmarks/PRISM_PIPELINE_RESULTS.md#fresh-frame-planning-decomposition-diagnostic-2026-10-29)
    then localized profiled CPU-heavy outliers to sample-array allocation in
    `FractalData`. A [bounded worker preparation experiment](benchmarks/PRISM_PIPELINE_RESULTS.md#bounded-worker-preparation-experiment-2026-10-29)
    was rejected: timing remained inconclusive and two process pairs exceeded
    its peak-RSS budget. It remains benchmark-only; no production optimization
    or new Prism pipeline timing verdict is recorded.
  - [x] Run the [Spaces presentation validation](benchmarks/SPACE_SWITCH_VALIDATION.md)
    on both pipelines. Result on one Mac with `-Dprism.order`: ES2 reproduced
    the stale mixed frame in all 8 controlled window trials with a verdict and
    in the Mission Control thumbnail and partial swipe; Metal showed the
    complete frame in all 9 window trials with a verdict and in both. The final
    startup-selection candidate must repeat it; see
    [the result and limits](benchmarks/SPACE_SWITCH_VALIDATION.md#result-2026-10-28).
  - [ ] Implement pipeline selection in a startup class that runs before the
    JavaFX toolkit, with the `mtl,es2,sw` chain, macOS only and the user
    override preserved; then pass the remaining checklist sections (visible,
    native, GPU coexistence, packaging, automated checks, documentation).
    The activation candidate uses the shared plain `FractalLauncher` entry
    point for Maven and jpackage and selects `mtl,es2,sw` before JavaFX startup
    when the property is absent. Unit tests cover selection and overrides;
    final-candidate acceptance remains open.
  - [ ] Record the verified configurations; one Apple M3 Pro does not validate
    the switch for other Macs.
  - [x] Record owner-reported manual acceptance of the activation candidate on
    2026-10-29: normal startup/shutdown and Dock identity; return from another
    Space, Mission Control thumbnail and partial swipe; progressive rendering
    and scene changes; resize/fullscreen; native UI; light/dark appearance;
    screen lock and sleep/wake; bookmarks, Undo/Redo and session restore.
    The supplied startup log confirms `MTLPipeline` with the default
    `mtl,es2,sw` order. These are manual checklist results, not a new controlled
    trial campaign or a final-candidate ES2/Metal image comparison. The owner
    declined to repeat the earlier ES2 comparison; its historical scope stands.
    Two observations remain separate from Metal acceptance: base pixels appear
    before AA when enlarging a window in Antialiased display, and keyboard exit
    from fullscreen needs assessment against platform conventions.
  - [ ] Track specialized acceptance and deferred environment coverage below.
    On 2026-10-29 the owner requested local checks and deferred unavailable
    external-display, minimum-OS, Windows/Linux and additional-Mac execution
    to roadmap follow-up. These gaps do not become passes or close P1.7.
    Local results and their limits are recorded in the
    [specialized acceptance record](docs/platform/METAL_MIGRATION_CHECKLIST.md#local-specialized-acceptance-2026-10-29).
    - [x] Exercise Metal initialization failure with the default fallback chain:
      isolated dependency copies missing only the Metal native library selected
      ES2; copies missing Metal and ES2 selected SW. Both final CPU smoke runs
      passed. This covers library-load initialization failure, not native
      crashes, hangs or every hardware-specific initialization failure.
    - [x] Verify Vulkan/MoltenVK and JavaFX Metal in one production application
      process: a completed 2592x1674 frame dispatched 4,339,008 pixels with
      `cpuFallback=false`; the completed frame and native badge were inspected.
      The graphical CPU/GPU comparison smokes and five GPU smoke tests passed.
      This is one-host compatibility evidence, not GPU default promotion.
    - [x] Run all three changed graphical benchmark Maven profiles on Metal,
      checking output and successful exit. Run the current cold-startup probe
      against a copied compatible snapshot, confirming both milestone markers
      and shutdown. These are functional checks, not a paired timing campaign.
    - [ ] Finish standard-location and clean-machine DMG installation acceptance,
      including the installed app's visible Dock tooltip. A newly built DMG was
      mounted read-only and its app copied to a temporary installation directory;
      copied file hashes matched, packaged Metal/AppKit/CPU smoke passed with
      no development JDK on PATH, and the normal restored frame, native context
      menu/Escape and shutdown were checked. This development-host check does
      not establish clean-machine, Gatekeeper or standard-location acceptance.
    - [ ] Recheck the VirtualBuddy macOS guest, recording the known Metal crash
      and explicit SW workaround separately from physical-Mac acceptance.
      Local VirtualBuddy opened with an empty VM library; no guest was available
      for this run. The historical default Metal crash remains unresolved.
    - [ ] Establish and document the supported minimum macOS for the JDK,
      JavaFX Metal, AppKit bridge, launcher and generated bundle together;
      verify execution on that OS rather than inferring support from metadata.
      Initial package inspection found `minos 11.0` in all 35 loose packaged
      Mach-O files and eight JavaFX graphics natives, while its generated plist
      claimed 10.11.
      - [x] Correct the generated application metadata to the observed 11.0
        binary floor before jpackage signing and DMG creation, and reject an
        unexpected packaged value. The override preserves the selected JDK's
        other template fields. This does not establish runtime support on 11.0;
        minimum-OS execution and vendor/platform acceptance remain open. See
        [the metadata correction](docs/packaging/RUNTIME_PACKAGING.md#deployment-metadata-correction-2026-10-29).
    - [ ] Check external-display connection/disconnection, mixed Retina scaling
      and refresh rates, and complete frames above 4096 physical pixels on
      suitable hardware, including seams and memory use.
    - [x] Compare decoded PNG pixels and dimensions for identical ES2/Metal
      scenes: all seven pairs matched exactly, covering five presets at 321x239,
      decimal Mandelbrot deep zoom at 65x49 and tiled export at 4098x258.
      This does not establish displayed-image equivalence or all palettes/modes.
    - [x] Execute the previously skipped fullscreen scenarios on the available
      desktop display with Metal: native menu tracking, reported trackpad case
      and both fullscreen-zoom parameter cases passed (four cases, no skips).
    - [ ] Record supported-platform execution for Windows/Linux and additional
      Mac configurations; local macOS arm64 results do not establish support
      on Intel/AMD Macs or other operating systems.
    - [ ] Resolve the focus-repaint decision for ES2/SW fallback using the Spaces
      evidence, and reconcile the detailed migration checklist with the final
      acceptance record without claiming unrecorded controlled-trial counts.
    - [ ] Complete final CI and record its exact committed candidate after
      review/delivery authorization. No commit, push or workflow dispatch is
      authorized by local acceptance checks alone.
    Screen-latency confirmation and long-session RSS/GPU-memory comparison
    remain unperformed measurements under the accepted performance trade-off;
    no new performance campaign is required by the owner's activation decision.

### P2. Product foundation and user workflows

- [x] **P2.1.** Complete section 4 iteration/Julia editing and zoom/iteration
  presentation. Current-source portable, active-desktop JavaFX/native and manual
  macOS checks passed. Menu-to-frame regressions verify applied iterations/exact Julia samples; restart
  checks preserve both editors' values.
  See [acceptance/platform limits](docs/platform/MACOS_UI.md#p21-acceptance).
- [x] **P2.2.** Build undo/redo, bookmarks and shared viewport presets on versioned
  scenes. Owner accepted 2026-10-14: Undo/Redo, bookmark persistence/replacement/
  deletion, valid/invalid clipboard presets. Native name sheet passed developer
  macOS UI checks; UI docs retain platform limits. Acceptance does not replace
  current-fix review/delivery.
  [Scene workflows](docs/architecture/SESSION_FORMAT.md#scene-history-bookmarks-and-shared-presets-p22).
- [x] **P2.3.** Accept unified tiled, cancellable high-resolution/off-screen export
  with `FractalScene`/independent `RenderTarget`; section 6 owns requirements.
  [Export contract](docs/architecture/PNG_EXPORT.md).
  - Status: owner accepted 2026-10-27 after macOS size sheet/save panel, progress,
    cancellation, overwrite/write errors, close confirmation, focus, light/dark,
    fullscreen and all-preset/deep Mandelbrot/Julia exports. Hidden staging-path
    error fixed in PR #33. No separate independent review/verification stages;
    Windows/Linux/Intel Mac have portable CI only.
    [Manual acceptance](docs/architecture/PNG_EXPORT.md#manual-acceptance-2026-10-27).
- [x] **P2.4.** Native AppKit macOS modal forms/alerts, less modality, JavaFX fallback
  for unavailable bridge/other platforms; finish multi-display context-menu checks
  with hardware. Implemented native coordinate/export forms/alerts and nonmodal
  palette/Help; live draft gradient reaches canvas only on Apply.
  [Implementation](docs/platform/MACOS_UI.md#native-windows-p24).
  - Status: owner accepted 2026-10-27: coordinates, palette/system color panel,
    Help, alerts, fullscreen, light/dark, Increase Contrast, Reduce Transparency,
    VoiceOver, native windows/context menus on mixed-DPI negative-origin second
    display. Two Minor defects fixed in PR #35: inexact new-stop position and long
    coordinate-hint zoom. Accepted AppKit limitation: menu near bottom corner of
    display-filling window adjacent to taller display can land off that display.
    No separate independent review/verification; Windows/Linux/Intel Mac portable
    CI only. [Manual acceptance](docs/platform/MACOS_UI.md#p24-acceptance).
- [x] **P2.5.** Optimize 7.7 Julia subnormal deltas only after profiling proves
  material visible exact-fallback cost.
  - Status: 640x400 at `1e-310`: exact 113 s, scaled about 1.5 s, normal deltas
    unchanged. Control/candidate PNGs byte-identical at 96x64 (`1e-310`, `1e-400`)
    and 640x400 (`1e-310`). Independent review clean; Apple Silicon macOS
    verification passed, including manual Deep Zoom/export/deep AA/navigation
    at `1e-310`. Windows/Linux portable CI only.
    [Subnormal deltas](docs/development/JULIA_DEEP_ZOOM_PERFORMANCE.md#subnormal-deltas-p25).
- [x] **P2.6.** Separate user-selected standard/deep AA modes in one application
  Settings window's Render Settings section, replacing Display Quality/Deep Zoom
  Antialiasing menu items and hosting future settings. Follow P2.4 native/fallback
  rules. Both drop-downs offer **Base only** (new, no AA), **Base, then antialiasing**
  (`FAST`), **Antialiased display** (`REFINED`, refined tiles only). Deep setting
  replaces the transient switch, is always editable and defaults to Base only.
  Extend `InteractiveRenderMode`/`AntialiasSettings`; persist both through scenes,
  restore, bookmarks/shared presets while preserving older-scene behavior.
  Export quality is unchanged.
  - Acceptance: base-only reaches `RenderStatus.COMPLETE` without refinement;
    badge/loading agree. Every mode reports whole-frame/AA `Rendering N%`, no
    separate `Refining…`. Switching during/after rendering preserves compatible
    samples without stale/mixed pixels. Portable/FX cover all standard/deep modes,
    persistence and compatibility. Completed-frame switches change presentation
    in place without recalculating base or recalculating/republishing finished AA.
    Reset defaults: standard Antialiased display, deep Base only. Manual macOS
    light/dark checks cover focus, Enter, Escape, Cancel, Apply and Reset to Defaults.
  - Status: owner accepted 2026-10-27 all modes/light/dark and Keyboard navigation
    on/off; sheet focus defects fixed in PR #31. Independent Apple Silicon macOS
    verification passed `main` at `b9af941`. Accepted limitation: Histogram Coloring/
    Orbit Trap loses finished AA after a Base-only pan. Windows/Linux/Intel Mac
    portable CI only. [Settings](docs/platform/MACOS_UI.md#render-settings-p26),
    [acceptance](docs/platform/MACOS_UI.md#p26-acceptance).

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

- [x] Add portable macOS/Windows CI for the CPU-default build and test suite,
  plus opt-in native hardware lanes with retained reports. The workflows and
  runner contract are implemented in `.github/workflows` and `docs/development/CI.md`; keep this
  item open until the hosted macOS/Windows jobs and configured self-hosted lane
  have produced retained reports from the repository. That implementation was
  limited to P0 item 1 and did not satisfy the P0 exit criterion at that stage:
  packaging, scene restore and the open measurement decision were separate
  required work.
  - [x] Implement the portable and opt-in native workflow definitions, clean
    checkout checks, dependency caching, bounded diagnostics and retained test
    reports.
  - [x] Pass the hosted macOS and Windows CPU-default jobs and retain their
    reports in Portable CI run 35392879380 (private archive).
  - [x] Run the configured self-hosted native hardware lanes and retain their
    reports.
    - [x] Pass the Apple Silicon macOS lane on an Apple M3 Pro with macOS 27.0:
      5 native GPU smoke tests passed and the reports were retained in
      Native GPU Validation run 35373192709 (private archive).
    - [x] Pass the Windows x64 CPU-fallback lane on Windows 11: the runtime
      remained unavailable as required, 5 native smoke tests completed with no
      failures or errors and one expected resident-GPU skip, the Maven cache
      was saved successfully, and the reports were retained in
      Native GPU Validation run 35393710276 (private archive).
- [x] Pass the macOS and Windows runtime-artifact workflow and record the clean-machine
  launch checklist, including the owner's explicit acceptance of the failed default
  launch on macOS Sonoma in VirtualBuddy. Windows passed; macOS passed only with a
  software-pipeline override, not the default Finder launch. Packaging, provenance, the
  exception, and the separate signing/release boundary are in
  `docs/packaging/RUNTIME_PACKAGING.md`.

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

- [x] Benchmark Mandelbrot/Julia throughput, ns/pixel, iteration distributions,
  1080p/HiDPI and multiple zooms.
- [x] Add Mandelbrot main-cardioid/period-2-bulb rejection.
- [x] Reject exact periodicity: current-case overhead exceeds benefit.
- [x] Evaluate other interiors; retain exact main/period-2 only. Higher periods
  lack cheap exact predicates; approximate circles risk false interiors.
- [x] Reject manual orbit-square caching: JIT removes repeats, Julia regresses.
- [x] Measure `FractalSample`; keep value API because escape analysis removes most
  allocation and iteration-heavy residual cost is negligible.
- [x] Reuse conjugate rows on empty real-axis-centered Mandelbrot grids; exact
  output, 1.72-1.88x parallel speedup.
- [x] Compare Vector API; keep scalar: 128-bit/two-double regresses overview,
  gains little deep.
- [x] Retune post-kernel tiles/workers: 32 pixels/`CPU - 1` balance throughput,
  first progress and UI headroom.
- [x] Specialized direct-to-data Julia differs ~1%; HotSpot removes abstraction,
  retain generic formula.
- [x] Reject early double-only perturbation; high-precision production/rebasing/BLA
  completed in 7.3–7.6.

## 4. Strengthen user interaction

Remaining items in this section follow the cross-section order in section 0.

- [x] Replace crowded controls with global toolbar, left inspector, canvas/bottom status.
- [x] Add Reset View.
- [x] Show/edit center where appropriate.
- [x] Native macOS pinch, continuous two-finger pan and directional swipes.
- [x] Badge tooltip zoom/effective iteration budget, compact scientific large values.
- [x] Iteration/Julia editors atomically apply paired drafts, retain invalid input,
  reset/cancel/session persist. [P2.1](docs/platform/MACOS_UI.md#p21-acceptance).
- [x] Keyboard navigation/render progress-status.
- [x] User errors instead of stack traces.
- [x] Shared/copyable viewport presets; P2.2 acceptance above.
- [x] macOS AppKit `NSMenu`, system appearance/behavior, universal packaged
  Objective-C/JNI/JavaFX fallback elsewhere. Validate callbacks/main-thread/focus/
  dismissal, uninterrupted rendering, Retina/multi-display/fullscreen. Built-in
  Retina passed native/accessibility actions, cancel/replace/live render/fullscreen;
  commands/limits: `docs/platform/MACOS_UI.md`.
- [x] Multi-display placement/mixed DPI/negative origins/edges; initial checks
  had one display. P2.4 accepted 2026-10-27 with bottom-corner limitation:
  [acceptance](docs/platform/MACOS_UI.md#p24-acceptance).
- [x] First unify FX dialogs: system fonts/light-dark/accent, restrained fields/
  spacing, primary right, Enter/Escape, predictable focus/validation. Coordinate/
  palette/Help/alerts share styling; GUI covers theme/exact drafts/focus.
  `docs/platform/MACOS_UI.md`.
- [x] Reduce modality: owner coordinate sheet, nonmodal live palette/Help, inline
  export completion; alerts only errors/actionable decisions. P2.4 accepted 2026-10-27.
- [x] Audit all modal coordinate/palette/Help/export/confirmation entries: remove
  excess modality or use native AppKit. Shared `NSPanel` for Iteration Settings,
  Julia Parameters, Add Bookmark, coordinates/export; `NSAlert` for errors/export
  close; nonmodal palette/Help. Draft gradient changes canvas only on Apply.
  `WINDOW_MODAL`/CSS are not native sheets. Retain bridge-failure/other-platform
  FX fallback. Validate ownership/restored focus/cancel/inputs/appearance/fullscreen/
  nonblocking callbacks. P2.4 accepted 2026-10-27:
  `docs/platform/MACOS_UI.md#p24-acceptance`.

## 5. Add history and reproducibility

- [x] Introduce an immutable `FractalScene` containing formula, viewport, iteration settings, and coloring, and pair every completed frame with the exact scene snapshot it represents.
- [x] Keep transient output dimensions and scheduling priority in a separate immutable `RenderTarget` so window resizes do not alter scene history.
- [x] Add undo/redo and bookmarks based on `FractalScene` snapshots. See P2.2 acceptance above.
- [x] Add JSON serialization and last-session restore. The current versioned schema and
  atomic recovery behavior are in `docs/architecture/SESSION_FORMAT.md`.
- Export requirements are consolidated in section 6 (P2.3), using the scene
  snapshot and independent render target defined here.

## 6. Expand the rendering engine

- [x] Reusable cubic Multibrot/Burning Ship/Tricorn presets.
- [x] Default smooth escape coloring against banding: slower seamless ping-pong/OKLab interpolation.
- [x] Precompute OKLab into 65,536-entry constant-time lookup.
- [x] Basic current-render PNG without AA.
- [x] Export adaptive 2x2–8x8, same dimensions/linear-light averaging.
- [x] Weak deterministic export dither reduces 8-bit banding without blur.
- [x] Cancellable interactive edge-adaptive 4x4 after visible base, progressive
  32x32 AA, pan overlap and partial visibility when zoom interrupts.
- [x] Reproducible optional jitter; later removed with Render → Antialiasing Pattern
  after dense-reference comparison found no visible/measurable regular-grid gain.
- [x] Benchmark Mandelbrot/Julia/cubic exterior distances: 1.4–2x base cost versus
  ~16x full 4x4, but misses interior-centered boundaries.
- [x] Combine distance/image-edge candidates before export/interactive supersampling;
  nonanalytic Burning Ship/Tricorn keep current detector.
- [x] Optional fast image-edge display: transformed old frame while base arrives,
  then progressive refined replacement.
- [x] Bounded/evicting palette-independent AA samples recolor base/refined without math.
- [x] Optional seamless period-two smooth offset animation without recalculation;
  pause on navigation/render, throttle/buffer recolor, apply through AA cache.
- [x] Separate two-pass histogram tonal mapping, not geometric AA replacement.
- [x] Orbit traps/editable stops.
- [x] Bounded LRU exact reverse reuse.
- [x] Zoom-out approximate scale-aware reprojection; expose base/AA outward from
  edges, atomically replace center with exact samples.
- [x] Exact samples/AA across compatible pan/resize: compute expansion borders,
  crop overlap. Preserve spacing/formula/accuracy/cap; test partial/resize-drag.
  Measure app gain in 9.1, do not repeat implementation.
- [x] P2.3 independent-resolution immutable-scene tiled CPU PNG: precise deep,
  histogram/traps, adaptive AA/dither, no stale cancel publication, preserve
  destinations on failure/cancel. Full-frame validated memory budget, inline
  progress/transient outcomes, size guidance/export-close confirmation.
  Accepted 2026-10-27 (P2.3). [Contract/validation](docs/architecture/PNG_EXPORT.md).

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
- [x] Extend the perturbation fast path to deltas below the normal-double range
  through scaled deltas with an explicit subnormal error bound (P2.5).

Julia uses shared reference perturbation in base rendering, deep AA, and export.
It does not apply Mandelbrot cardioid tests, critical-point rebasing, or BLA.
Subnormal deltas are carried in scaled units; unreliable orbits retain the slower
arbitrary-precision fallback.
See `docs/development/JULIA_DEEP_ZOOM_PERFORMANCE.md` for the latency regression and measurements.

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
  capabilities required by each backend mode (`docs/platform/GPU_RUNTIME.md`).
- [x] Isolate native dependencies and GPU resource ownership behind a dedicated runtime
  so the render controller and JavaFX surface are independent of a specific graphics
  API.
- [x] Implement the macOS runtime: architecture-specific LWJGL/MoltenVK natives,
  Vulkan instance and physical-device discovery, numeric/compute capability
  reporting, and a selected logical device with a compute queue.
- [x] Add synchronized runtime ownership, reference-counted loader lifetime,
  native cleanup, device-loss handling, and a guarded CPU fallback operation
  that preserves the caller's precision policy and cancellation.
- [x] Validate startup, simultaneous runtimes, shutdown/reopen, simulated device
  loss, missing-native failures, and direct/deep CPU fallback on Apple M3 Pro.
  See `docs/platform/GPU_RUNTIME.md` for commands and limits of this macOS-first validation.

### 8.2. Validate integration with palette recoloring

- [x] Add an optional GPU palette-recoloring backend that uploads compact base/AA
  smooth phases once and advances the palette offset in a shader; retain the
  CPU path as a portable fallback and benchmark both paths on Retina displays.
- [x] Present the recolored result through the existing JavaFX buffer boundary and
  measure upload, dispatch, readback, and presentation costs separately.
- [x] Validate the macOS/Apple Silicon prototype against the parallel CPU
  baseline, including palette/AA/frame invalidation and fallback. Run a paired
  Retina benchmark with warmup and saved samples (`benchmarks/PALETTE_BENCHMARK_RESULTS.md`).
  GPU remains opt-in: measured gains do not justify replacing CPU by default.
- [x] Repeat the paired Retina benchmark after the LWJGL 3.4.2 / FFM update,
  retain raw samples, and refresh the performance decision. Both 12/40 and
  30/120 runs favor GPU only for the large-AA workload; keep CPU as the default.

### 8.3. Build a limited Mandelbrot base-pass spike

- [x] Define initial FP32 numeric tolerances and screen coordinate rounding,
  orbit divergence and iteration limits against the direct-double CPU reference
  (`benchmarks/GPU_FP32_PRECISION.md`). Reject automatic selection by scale alone: even
  overview frames fail; whole-frame FP32 is not accepted.
- [x] Validate actual FP32 shader arithmetic in a verification-only MoltenVK
  Mandelbrot probe across full Retina frames, boundary sweeps, panned grids and
  iteration limits. An outward-rounded interval certificate accepts 89.65% of
  the overview Retina frame with zero false accepts in the recorded gate; raw
  FP32 has 1,352 wrong escaped classifications (`benchmarks/GPU_FP32_NATIVE.md`).
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
  uninstrumented and separately profiled results in `benchmarks/GPU_RENDER_BENCHMARK_RESULTS.md`.
  Physical scanout/vsync latency is not measured by this publication gate.
- [x] Make the continuation decision from end-to-end results: **do not expand
  GPU rendering yet**. All ten workload/size cases are slower on GPU; Retina
  overview base is 592.52 vs 52.56 ms, Refined + AA is 1708.19 vs 1175.21 ms.
  Keep CPU default and the limited GPU backend opt-in. Re-run this gate after
  targeted certificate/kernel improvements before reconsidering GPU residency.

### 8.5. Optimize certified GPU calculation and repeat the decision gate

Address the costs measured in 8.4 before expanding GPU residency. On the Retina
overview, host certification/recovery/publication takes about 545 ms and the interval
kernel about 139 ms; these costs overlap. CPU stays the default.

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
  `benchmarks/GPU_RENDER_BENCHMARK_8_5_RESULTS.md`. Keep CPU default and 8.6 deferred.

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
  slower end to end; see `benchmarks/GPU_RESIDENCY_8_6_DECISION.md`.
- [x] Build an isolated whole-frame residency feasibility spike: keep canonical
  samples on GPU, read back only the FP32 rejection list, upload exact CPU
  corrections, color on GPU, and return ARGB. At that pre-JavaFX boundary on
  M3 Pro it is 39.9% faster for Retina overview and 83.9% faster for Retina
  exterior, but 24.1% slower for Retina seahorse because 68.5% of pixels require
  CPU recovery. These fixed-300-iteration, base/color-only measurements exclude
  AA and progressive presentation; their color comparison is not a full sample
  conformance gate. The strict 15% cross-scene gate therefore fails; see
  `benchmarks/GPU_RESIDENT_SPIKE_RESULTS.md` and the scope audit in
  `benchmarks/CPU_GPU_OPTIMIZATION_ANALYSIS.md`.
- [x] Classify resident certificate rejections before changing the kernel. In
  the Retina seahorse view, 51.16% of rejections come from an uncertain escape
  interval and 48.84% from a smooth-value interval wider than the contract;
  iteration, boundedness, validity, palette-phase and catch-all mismatches are
  all zero. Palette or dispatch tuning cannot remove this bottleneck. See
  `benchmarks/GPU_RESIDENT_REJECTION_PROFILE.csv`.
- [x] Prototype a tighter centered-error FP32 certificate only for first-stage
  rejections. Its outward-rounded error recurrence certifies zero additional
  pixels in overview, exterior, and seahorse, while raising the Retina seahorse
  calculation median from 27.06 to 46.65 ms. Remove the second pass; a future
  attempt needs a genuinely higher-precision representation, not another FP32
  interval shape. See `benchmarks/GPU_RESIDENT_SECOND_STAGE_PROFILE.csv`.
- [x] Measure the accuracy and performance ceiling of double-single GPU recovery
  for every FP32 rejection before investing in its certificate. It removes CPU
  recovery, but fails both gates: Retina seahorse is 37.3% slower than CPU and
  has 54 color outliers with a maximum channel error of 189; Retina overview is
  only 4.3% faster and has 46 outliers. Remove the emulation path and retain its
  diagnostic results in `benchmarks/GPU_RESIDENT_DOUBLE_SINGLE_RESULTS.csv`.
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

Use [the analysis](benchmarks/CPU_GPU_OPTIMIZATION_ANALYSIS.md) for the evidence, current
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
  The versioned [matrix and headless runner](benchmarks/BASELINE_BENCHMARK.md) contain
  28 sequences / 37 steps at three render sizes (26 / 35 in `9.1-v2`, which
  drops the deterministic-jitter AA fixtures), with a pinned exact manifest
  and behavioral regressions. See [validation](benchmarks/BASELINE_VALIDATION.md).
- [x] Measure formula/backend, returned-ARGB, JavaFX base/AA publication and
  input-to-visible-update scopes separately. Record first useful region,
  cold/warm full-frame time and cancellation tails; label physical scanout as
  unmeasured until a dedicated display experiment exists.
  Headless backend, returned-ARGB, first useful sample region, production AA
  and post-first-region cancellation scopes share the 9.1 matrix. The
  [JavaFX publication runner](benchmarks/BASELINE_FX_BENCHMARK.md) now uses the same inputs
  for Fast/Refined base/AA publication and request-bound post-layout observations,
  with exact sample/ARGB controls. See [validation](benchmarks/BASELINE_FX_VALIDATION.md).
  The [production input runner](benchmarks/BASELINE_INPUT_BENCHMARK.md) now connects scroll,
  pinch, trackpad, drag, resize and input-driven replacement to the same fixtures.
  Exact actual camera/controller jobs and generation-bound traces distinguish UI
  policy from canonical requests; sample/ARGB controls preserve verified reuse.
  See [input validation](benchmarks/BASELINE_INPUT_VALIDATION.md). The matrix also exposed
  and regression-tested scaled-exponent pan grid-snap overflow. Handler-entry
  timestamps and post-layout observations are not physical input/display latency.
- [x] Add a headless production-AA benchmark, an explicit CPU-only JavaFX
  comparison mode, and cache preparation/merge timing and batch counters.
  Record separate contention profiles and 30-sample comparisons across three
  fresh process pairs in [CPU AA results](benchmarks/CPU_AA_OPTIMIZATION_RESULTS.md).
- [x] Measure production scroll/pinch/drag handler latency with generation-aware
  callback/publication tracing, no-screen controls and a calibrated screen-marker
  capture proxy. Isolate the roughly 1.82 s fully-ready validity-mask scan at
  2400x1520; physical scanout remains unmeasured. See
  [interaction latency results](benchmarks/INTERACTION_LATENCY_RESULTS.md).
- [x] Add opt-in CPU base-task planning, queue and cancellation-drain diagnostics
  to the canonical matrix and real-input trace. Preserve request/input generation
  through worker exit, including workers outliving cancelled coordinator Futures.
  See [the contract](benchmarks/BASELINE_SCHEDULING_BENCHMARK.md) and
  [validation](benchmarks/BASELINE_SCHEDULING_VALIDATION.md). Per-worker intervals overlap;
  they are not additive frame costs or process CPU utilization.
- [x] Extend diagnostics to allocation and long-duration memory/thermal behavior.
  The long FX matrix exposed detached surfaces retained by Scene/Window scale
  listeners; unsubscribe-on-detach is now regression-tested. FX rows also record
  heap/GC observations; see [the ownership fix](benchmarks/BASELINE_FX_VALIDATION.md).
  The [allocation and sustained-memory driver](benchmarks/BASELINE_MEMORY_BENCHMARK.md)
  separates navigation from exact CPU controls and adds JFR allocation attribution,
  RSS, NMT, GC checkpoints and macOS thermal pressure. The selected eight-fixture
  validation covers 288 exact trials, including a 13.24-minute 1512x982 run with
  stable detached-heap checkpoints; see [results and limits](benchmarks/BASELINE_MEMORY_VALIDATION.md).
  Physical temperature, energy and all-platform/full-matrix soaks remain unmeasured.
- [x] Use uninstrumented alternating pairs for decisions and separate JFR/native
  profiles for attribution. Confirm promising changes with at least 30 measured
  pairs and multiple process starts; record median/tails, hardware/runtime,
  memory/GC and sustained thermal behavior. Do not run competing timing suites
  simultaneously or overwrite historical CSVs.
  The [frozen-build paired runner](benchmarks/BASELINE_PAIRS_BENCHMARK.md) now enforces
  declared targets/controls, matching samples and alternating process starts.
  [A/A calibration](benchmarks/BASELINE_PAIRS_VALIDATION.md) validates the protocol on
  identical builds. The first [bounded-mask candidate](benchmarks/VALIDITY_MASK_OPTIMIZATION.md)
  received 30 alternating process pairs in both headless and JavaFX scopes on
  fixed AC power. Exact controls and the retained-frame target passed; the
  aggregate 5% timing-control budget stayed inconclusive. The explicit
  candidate decision is reject, with the candidate removed from production.
  This closes the current paired decision, not the open 9.3 optimization item.

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
  [CPU AA results](benchmarks/CPU_AA_OPTIMIZATION_RESULTS.md) for scopes, tails, memory
  observations and supplementary controls.
- [ ] Reduce the retained-snapshot color preparation barrier without restoring
  shared-cache contention. The 30-sample retained-Julia follow-up completes
  about 10x faster, but the first tile arrives about 5 ms later. Compare
  on-demand tile coloring with a compact immutable lookup; verify palette
  reuse, additional memory and first/full publication together. The on-demand
  tile-coloring candidate preserved exact pixels but missed the joint timing
  gate in 30 headless process pairs, so it was rejected and removed. The
  preparation barrier remains; see [the P1.2 experiment](benchmarks/RETAINED_SNAPSHOT_PREPARATION.md).
  P1.5 accepts the existing cache's first-tile cost by owner exception; this
  item remains open for any future improvement.
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

### 9.3. Improve CPU batching, scheduling and publication

- [ ] Bound reusable `ValidityMask.missingRowSpans` scans to their row/region,
  avoid repeated complete-frame scans, and check cancellation during task
  preparation. The [9.1 scheduling probe](benchmarks/BASELINE_SCHEDULING_VALIDATION.md)
  attributes roughly 5 seconds at 3024x1964 to planning with zero worker tasks,
  including a similar cancellation tail. Add exact-mask/reuse and deterministic
  cancellation regressions, then apply the 30-pair/multiple-process gate before
  claiming an interaction or throughput gain.
  Historical candidate `9778ac9` added bounded bitmap-word scans, an exact
  cached readiness count, complete-frame early exit and cancellation before
  task submission. Its exact-mask/reuse and deterministic request-drain tests
  passed, as did a separate 13.37-minute input/memory soak. The first six-pair
  FX campaign changed power source; the final 30-pair headless and FX campaigns
  held observed AC power, confirmed the retained-frame target gain, but left
  overview, pan and short-latency controls inconclusive. The
  [P0.3 decision](benchmarks/VALIDITY_MASK_OPTIMIZATION.md) rejected and removed this
  candidate. A new implementation and its tests must pass the full gate.
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

### 9.4. Extend the successful CPU perturbation/BLA path

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
