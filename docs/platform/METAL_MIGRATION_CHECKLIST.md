# Metal Prism pipeline migration checklist

Final acceptance for roadmap P1.7 Metal-first macOS startup. On 2026-10-29 owner
chose Spaces reliability over measured latency costs, including failed publication:
default activation candidate authorized without more tuning, remaining checks not
passed. Historical `fail`, `inconclusive` and rejected results are unchanged.

JavaFX 26 macOS defaults to `es2,mtl,sw`. ES2 may hide off-Space completed frames
after return; controlled `-Dprism.order=mtl` trials did not reproduce it.
[Timing results](../../benchmarks/PRISM_PIPELINE_RESULTS.md).

## 1. Design decisions before implementation

Shared plain-Java `FractalLauncher` handles Maven/native development/jpackage.
Before `Application.launch(FractalFxApplication.class, args)`, set
`prism.order=mtl,es2,sw` on macOS only if absent; preserve even empty overrides.
Other-platform defaults stay. No extra experimental app switch. Focus-repaint
decision awaits final-candidate section 4 trials.

- [x] **Single selection point that runs before toolkit startup.** `FractalLauncher`,
  the compatibility `FractalApplication` main class and the three graphical Maven
  benchmark main classes are plain Java. They route startup through
  `FractalLauncher.launch` before `Application.launch`. `FractalFxApplication` owns the
  production JavaFX lifecycle; benchmarks use dedicated lifecycle adapters. Tests and
  specialized probes starting JavaFX directly must still select the intended pipeline
  explicitly.
- [x] **macOS only, user override preserved.** Set the order only on macOS and
  only when `prism.order` is not already defined, so `-Dprism.order=es2` in
  JVM options or `JAVA_TOOL_OPTIONS` still wins. Windows and Linux keep the
  JavaFX default.
- [x] **Fallback chain `mtl,es2,sw`, with a precise guarantee.** Local isolated
  library-load failure checks selected ES2 and then SW; see the specialized
  acceptance record below. If Metal fails
  to initialize, JavaFX may use ES2; if ES2 also fails, SW. Do not require ES2
  specifically: in the recorded VirtualBuddy Sonoma guest ES2 did not
  initialize either (see [runtime packaging](../packaging/RUNTIME_PACKAGING.md)).
  The chain covers initialization failure only. It does not recover from a
  native crash or hang of an already running Metal pipeline; the VirtualBuddy
  Metal crash (`newArgumentEncoderWithLayout:` in `libprism_mtl.dylib`) is a
  separate known limitation and must be rechecked, not assumed fixed.
- [ ] **Minimum macOS of the whole delivery.** Determine it for JDK, JavaFX
  Metal pipeline, the AppKit bridge library and the launcher together. The
  source `Info.plist` has no `LSMinimumSystemVersion`; inspect the generated
  application bundle as well, and document the result. Packaging now sets the
  generated application plist to the observed 11.0 binary floor before signing;
  see the [metadata correction](../packaging/RUNTIME_PACKAGING.md#deployment-metadata-correction-2026-10-29).
  Minimum-OS execution and vendor support acceptance remain open. The bootstrap
  preparation's [binary/metadata inspection](../packaging/RUNTIME_PACKAGING.md#startup-and-macos-deployment-floor)
  found an 11.0 binary floor but a generated plist claiming 10.11. A supported
  minimum is still open; neither value alone establishes runtime support.
- [ ] **Fate of the focus-repaint fix** (`fix/stale-frame-after-space-switch`).
  Decide after section 4: keep it as protection for the ES2/SW fallback, or
  close it with a recorded reason.

## 2. Frame correctness

Two different equality criteria apply:

- [ ] **Computed data is exact.** Iteration data and ARGB buffers must match the
  ES2 run exactly; the Prism pipeline must not affect them. The paired
  campaign fingerprints already cover the measured fixtures.
- [ ] **Displayed image is checked visually.** Compare the same scenes on ES2
  and Metal on screen: overview, deep zoom, antialiasing, every palette and
  coloring mode. This covers filtering, scaling, gamma/color profile and the
  fractal actually displayed, which buffer fingerprints cannot.
- [ ] No seams, bands or mixed frames during progressive rendering and frame
  reuse: pan in every direction, zoom, resize, iteration-limit change, Reset
  View and fractal change.
- [ ] Loading screen disappears only after `RenderStatus.COMPLETE` and finishes
  correctly on `FAILED`.
- [x] **PNG export, including tiled high-resolution export:** compare decoded
  pixels and dimensions with an ES2 export of the same scene. Byte-identical
  files are not required; encoding and metadata may differ for the same image.

## 3. Windows and displays

- [x] Resize: slow, fast, down to the minimum size (owner-reported acceptance).
- [x] Fullscreen: enter, exit, switch between fullscreen apps (owner-reported
  acceptance; separate AA-policy and keyboard-exit observations remain).
- [ ] Move the window between Retina and non-Retina displays and between
  displays with different refresh rates.
- [ ] **Large frames.** ES2 is clamped to 4096 texture size in the pinned
  JavaFX version and Metal reports 16384, but `ImageView` can split a large
  image into several textures, so the texture limit does not by itself crop the
  frame. Criterion: a full frame with no seams or errors and no unacceptable
  memory use for a window above 4096 physical pixels (5K/6K or a full external
  display).
- [ ] Connect and disconnect an external display while the window is open.
- [x] Minimize to the Dock and restore (owner-reported window-checklist result).

## 4. Original defect and window lifecycle

Use the trial matrix and verdict rules of the
[Spaces presentation validation](../../benchmarks/SPACE_SWITCH_VALIDATION.md)
for the first two checks, on both ES2 and Metal.

Prototype result, not a tick for the final candidate: with `-Dprism.order`
on one Mac, ES2 reproduced the defect in every controlled trial with a
verdict and Metal showed the complete frame in the window, the Mission Control
thumbnail and a partial swipe
([result and limits](../../benchmarks/SPACE_SWITCH_VALIDATION.md#result-2026-10-28)).
Repeat both checks once startup pipeline selection exists.

- [ ] **Fixed reproduction order, without the focus-repaint fix:** pan, switch
  to another Space before the frame completes, let it complete there, return
  without any further input. The full completed frame must be visible.
- [ ] Separately: the Mission Control thumbnail and a partial Space swipe show
  the completed frame.
- [x] Sleep and wake with an open window and after a completed render
  (owner-reported acceptance).
- [x] Screen lock and unlock (owner-reported acceptance).
- [x] Light/dark appearance change with the window open (owner-reported acceptance).

## 5. Native AppKit integration

- [ ] Native status badge (`FractalStatusBadge.m`, an NSView above the content
  view) draws above the Metal layer without flicker or loss during resize and
  fullscreen.
- [ ] Native context menu: position relative to the window, cancellation on
  resize, window movement, focus loss and close.
- [ ] Native parameter sheet: focus, Enter, Escape, Cancel, Apply and
  validation errors.
- [ ] macOS application menu and the visible Dock tooltip `FractalLens`.
- [x] JavaFX fallbacks still work when the native bridge is unavailable
  (three independent desktop fallback tests passed on Metal).

## 6. Application features

- [x] Session restore, bookmarks, undo/redo and dialogs (Render Settings, Help):
  owner-reported acceptance on the activation candidate.
- [x] **Vulkan/MoltenVK together with JavaFX Metal.** Production GPU dispatch
  and completed presentation were checked locally; see the specialized record.
  `-Pgpu-smoke` alone does
  not prove that both Metal clients work in one process. Run the application
  manually with both
  `-Dfractal.gpu.enabled=true -Dfractal.gpu.mandelbrot.enabled=true` and
  confirm that GPU work actually ran (diagnostics or logs); a successful CPU
  fallback does not prove compatibility. Also run `./mvnw -Pgpu-smoke test`.
  This does not promote the GPU compute backend, which keeps its own roadmap
  gates.

## 7. Launch and packaging

- [x] `./mvnw compile javafx:run` starts on Metal: `-Dprism.verbose=true`
  reports `MTLPipeline`.
- [ ] Dock launcher: `python3 scripts/macos/test_macos_launcher.py`, then a
  manual launch with the Dock tooltip check.
- [ ] DMG from `python3 scripts/packaging/package_runtime.py`: the installed app
  actually runs on Metal.
- [x] Fallback: with Metal unavailable to initialization, the app starts on the
  next available pipeline (ES2 or SW) as defined in section 1.
- [x] The user override `-Dprism.order=es2` still selects ES2.
- [ ] Windows and Linux are not affected: no pipeline is selected there.
- [ ] Recheck the VirtualBuddy macOS guest and record the result separately; do
  not infer physical Mac behavior from it.

## 8. Automated checks

- [x] Full portable suite: `./mvnw clean test`.
- [ ] JavaFX integration tests with Metal selected explicitly, because tests
  start the toolkit themselves:

  ```shell
  ./mvnw -Dfractal.fx.tests=true \
    -Dprism.order=mtl -Dprism.verbose=true \
    "-Djavafx.cachedir=TASK_SPECIFIC_TEMP_DIRECTORY" \
    -DreuseForks=false test
  ```

  Use a fresh directory under the platform temporary directory. Confirm
  `MTLPipeline` in the log of every relevant fork. A skipped test is not a pass.
- [x] A separate targeted JavaFX run with `-Dprism.order=sw` for the declared
  fallback.
- [x] Unit tests for pipeline selection: macOS only, user value preserved,
  fallback chain set.
- [ ] Script tests for every changed `scripts/` directory: `scripts/packaging/`
  (`python3 scripts/packaging/test_package_runtime.py`), `scripts/macos/`
  (`python3 scripts/macos/test_macos_launcher.py`), `scripts/publication/`
  (`python3 -m unittest discover -s scripts/publication -p 'test_*.py'`).
- [ ] For new evidence: `check_public_data.py`, `check_secrets.py` and
  `check_secrets.py --tree`.
- [ ] Green CI (Linux, macOS, Windows) on the final version of the change.

## 9. Performance gate

[Prism protocol](../../benchmarks/PRISM_PIPELINE_BENCHMARK.md) sets 1.05 per-group
limit; owner decision supersedes activation gating, not verdicts. First campaign:
66/103 pass, none fail, 37 `inconclusive` (10 publication, 27 screen). Unperformed
measurements remain gaps; owner decision needs no further confirmation.

- [ ] Resolve all 37 inconclusive groups of both campaigns with a predeclared
  confirmation campaign in a new output directory. Selecting only groups with a
  median ratio above 1.00 is not sufficient: a favorable median can still have
  an interval that allows a substantial slowdown.
  The [confirmation policies and stopping rule](../../benchmarks/PRISM_PIPELINE_BENCHMARK.md#predeclared-confirmation-campaign-p17)
  retain all 103 groups. The publication confirmation campaign ended `fail`
  with one failing group (3024x1964 Refined first visible publish), which
  fails the performance gate (subsequently accepted as a trade-off by the
  owner); the screen-latency confirmation
  campaign was not executed. See
  [the confirmation results](../../benchmarks/PRISM_PIPELINE_RESULTS.md#confirmation-campaign-p17-2026-10-28).
  A post-change campaign on revision `2a19472`, after the frame-preparation
  fix, also ended `fail` with a different failing group (1512x982 Refined first
  visible publish); see
  [the post-change results](../../benchmarks/PRISM_PIPELINE_RESULTS.md#post-change-publication-campaign-p17-2026-10-28).
- [ ] Explain the occasional slow Metal first visible publish at 3024x1964 with
  the JFR pulse phases of that fixture.
- [ ] Compare memory (RSS and GPU memory) during a long session with large
  windows.
- [x] **Owner decision, 2026-10-29:** accept Metal for the Spaces reliability
  improvement despite the measured latency trade-off and the failing publication gate.
  The post-change failing group was 1512x982 Refined first visible publish: pooled
  medians 1.03 ms ES2 and 1.13 ms Metal; matched ratio 1.119, interval 1.064–1.243.
  Screen-latency confirmation and long-session memory comparison remain unperformed.
  This acceptance does not prove a uniform <=5% slowdown, waive other checklist sections
  or relabel any result as a pass.

## 10. Coverage matrix and documentation

Record the configuration of every check. Unlisted configurations are
unverified.

| Configuration | Status |
| --- | --- |
| Apple M3 Pro, macOS 27.2, built-in Retina display | historical timing; current local automated, owner manual and specialized checks below |
| External non-Retina or different refresh-rate display | not checked |
| 5K/6K or large external display | not checked |
| Other Apple Silicon generations | not checked |
| Intel Mac (integrated or AMD GPU) | not checked |
| VirtualBuddy macOS guest | historical Metal crash; current guest check deferred, local VM library empty |
| Windows, Linux | startup selection unit contracts only for this candidate; platform execution deferred |

Bootstrap preparation: macOS 27.2 ARM64, normal Maven and JDK 25 app image.
Maven confirmed `ES2Pipeline`/unchanged `es2,mtl,sw`. Tests cover entry structure/
Maven-jpackage agreement. Production smoke passed unset and separate explicit
`mtl`/`es2`/`sw` forks, with actual pipelines in verbose logs; focus, packaging
contracts/native launcher also passed. Preparation only: automatic selection,
displayed comparison, initialization fallback, Spaces, installed DMG, VM,
minimum OS/performance remain open.

- [x] Update `docs/platform/MACOS_RUN.md` and `docs/platform/MACOS_UI.md`: the
  selected pipeline, how it is selected and how to return to ES2.
- [ ] Update `docs/packaging/RUNTIME_PACKAGING.md` with the packaged pipeline
  and the minimum macOS.
- [ ] State the verified configurations from the matrix above; one Mac does not
  validate the switch for every Mac.

## Initial activation candidate developer checks (2026-10-29)

These checks predate the entry-point correction below. They do not validate
the changed lifecycle adapters or replace their graphical acceptance.

Based on revision `ba05a0ce5017c9de888fc1e5e1411fb0b6c95fa2`, on macOS 27.2
ARM64 with the JDK and JavaFX configuration from `pom.xml`:

- Targeted launcher/module unit tests: 6 tests, no failures or skips.
- `./mvnw clean test` in the active IDE desktop: 639 tests, 134 opt-in skips, no
  failures/errors. The earlier restricted-shell attempt had six
  `ExportFailureMessageTest` errors caused by `No toolkit found`; the unchanged full
  command passed in the desktop session. This is an environment limit, not a repaired
  product defect.
- Production-entry-point smoke with `prism.order` absent: 1 test, no skips;
  verbose output confirmed `MTLPipeline` and the smoke verified CPU completion.
- Full Metal JavaFX command from section 8: 657 tests, 13 conditional skips, no
  failures/errors. All 14 executed `*FxTest` classes reported `MTLPipeline`. The skips
  include three fullscreen checks, the separate no-native-bridge configuration, and
  other opt-in benchmark/GPU checks. Skipped scenarios are not accepted by this result.
- Explicit ES2 launcher smoke: 1 test; software launcher/startup tests: 2 tests;
  no skips or failures. Verbose output confirmed `ES2Pipeline` and `SWPipeline`
  respectively. This validates overrides, not failed-Metal initialization.
- Clean build with `-P!macos-native-menu`, `-Dfractal.fallback.tests=true`,
  `-Dprism.order=mtl`, `-DreuseForks=false` and
  `-Dtest=DialogRoutingFallbackFxTest`: 3 tests, no skips or failures; Metal
  confirmed in the log. JavaFX dialogs worked without the native bridge.
- `python3 scripts/macos/test_macos_launcher.py`: 3 tests passed;
  `python3 scripts/packaging/test_package_runtime.py`: 10 tests passed.
- `./mvnw -Pgpu-smoke test`: 5 tests passed without skips. This does not prove
  Vulkan and JavaFX Metal coexistence within one process.
- Normal `./mvnw compile javafx:run`, with only `prism.verbose` added through
  JVM options: logged `mtl es2 sw` and `MTLPipeline`. The restored deep-zoom
  frame and native status badge were visibly complete in the running window;
  application exit was successful. This limited inspection does not cover the
  controlled Spaces matrix, image comparisons, Dock tooltip or all UI scenarios.

Final-candidate Spaces/thumbnail trials, initialization failure fallback, fullscreen
acceptance, light/dark comparison, display changes, PNG comparison, long-session memory,
GPU coexistence, installed DMG, VM, supported minimum OS, other Macs, Windows/Linux
execution and final CI are unverified. The earlier bootstrap checks and historical
measurements above retain their own scope.

## Entry-point correction developer checks (2026-10-29)

Plain compatibility `FractalApplication` delegates `FractalLauncher`;
`FractalFxApplication` keeps production lifecycle. Graphical benchmark mains also
bootstrap dedicated adapters. Open `ui` only to `javafx.graphics` for construction;
export no implementation package. Cold startup uses new lifecycle; frozen-build
compatibility is in benchmark index.

Final-source developer checks on macOS 27.2 ARM64, JDK 26.0.1, and the JavaFX
version pinned in `pom.xml`:

- `./mvnw clean test` in the active IDE desktop: 640 tests, 135 opt-in skips,
  no errors or failures. Launcher/module contract tests cover all six plain
  main classes. The compatibility smoke has its own opt-in annotation;
  JUnit's class-level condition is not inherited from the shared test class.
- The full Metal JavaFX command in section 8: 658 tests, 13 conditional skips,
  no errors or failures. This includes both launcher smokes and production
  startup. Skipped fullscreen, separate fallback and benchmark/GPU scenarios
  remain outside that result.
- Separate `FractalLauncherFxTest,FractalCompatibilityLauncherFxTest` forks
  with `prism.order` absent: 2 tests passed without skips; both initialized
  `MTLPipeline`. The same pair with `prism.order=sw`: 2 tests passed without
  skips and initialized `SWPipeline`. Both forward application arguments and
  verify CPU completion and shutdown.
- Native launcher script: 3 tests passed. Packaging script: 10 tests passed.
- A restricted-shell full-suite attempt with `prism.order=sw` failed while extracting
  JavaFX natives: the default cache `.lock` was not writable, followed by six
  `ExportFailureMessageTest` initialization errors. The unchanged final suite passed in
  the desktop session. These errors do not prove a product defect and were not hidden by
  changing assertions.

Six real `compile javafx:run` smoke processes additionally passed. Every
process logged the expected initialized pipeline and exited successfully:

| Main class or profile | Pipeline request | Initialized pipeline |
| --- | --- | --- |
| `FractalApplication` main-class override | absent | Metal |
| `FractalApplication` main-class override | `es2` | ES2 |
| `palette-benchmark` | absent | Metal |
| `interaction-latency` | absent | Metal |
| `gpu-render-benchmark` | absent | Metal |
| `palette-benchmark` | `sw` | SW |

Compatibility runs used `javafx.args` with
`--package-smoke-test=${TEMP_DIR}/report.properties`, confirming CPU completion.
Palette/GPU: CPU-only 32x24, one warmup/sample, GPU scene `overview`. Interaction:
320x240 logical, one sample, zero warmup, `wheel`, `fractal.latency.noScreen=true`.
All isolated JavaFX caches/private outputs/verbose logs. CSV records requested,
logs actual pipeline. Functional startup only, no performance/GPU coexistence
acceptance or historical rerun.

The normal `./mvnw compile javafx:run` also initialized Metal with the absent
pipeline property. The completed restored deep-zoom frame and native status
badge were inspected in the running window; application exit succeeded. This
limited check does not close the remaining Spaces, Dock-tooltip, appearance,
export, display, initialization-fallback, packaged/VM or platform acceptance.
Raw logs, CSVs and smoke reports remain private.

### Cold-startup probe integrity correction (2026-10-29)

Probe identity includes nested lifecycle: new `probe_hash_scope=class-family-v1`
definitions record main/nested/anonymous `probe_files` and combined `probe_sha256`.
Before summary, compare entire map; reject byte changes/additions/deletions.
Index defines semantics; historical single-class identities remain.

101 benchmark script tests passed, seven new cold-startup regressions. Real
runner's unchanged probe drives 60 simulated launches, checking map/identity.
Mutated/added/deleted nested or deleted main classes reject without summaries;
separate tests cover cross-campaign changes/prelaunch missing main. Nested mutation
regression fails original runner (missing expected integrity error). No JavaFX or
measurement campaign ran; unchanged Java/application suites were not repeated.

## Local specialized acceptance (2026-10-29)

Owner requested available local checks; external display/minimum macOS/
Windows/Linux/additional Macs deferred to roadmap, P1.7 open. Apple M3 Pro,
macOS 27.2 ARM64, Microsoft OpenJDK 25 (update 4, vendor patch 1), `pom.xml` JavaFX.
Activation working tree base `ba05a0ce5017c9de888fc1e5e1411fb0b6c95fa2`;
previous CLEAN content `sha256:5678ea1e228e26cc88ea1666058430e10bd7ce0a87df4b7a1bb780da84e374e3`.
Only docs changed during recording; old identity neither identifies nor reviews
the expanded documentation.

### Functional launch and GPU coexistence

All commands ran in the active desktop session with isolated JavaFX caches and
`prism.verbose=true`. All three changed Maven profiles ran through
`./mvnw -P<profile> compile javafx:run`, with no `prism.order` override, confirmed
`MTLPipeline`, produced CSV output and exited successfully:

| Profile | Functional inputs and coverage |
| --- | --- |
| `palette-benchmark` | 64x48; one warmup and one sample; CPU/GPU comparison, actual GPU recoloring required by the existing harness |
| `gpu-render-benchmark` | 256x192; `overview,overview-aa`; one warmup and one sample; both GPU flags enabled and existing CPU conformance gates retained |
| `interaction-latency` | 320x240 logical canvas; `wheel`; zero warmup, one sample; `noScreen=true`, so physical display latency was not measured |

The GPU-render CSV contains six GPU rows with `fallback=false`; every row
certified 46,424 of 49,152 pixels, with the remaining pixels handled by the
existing exact CPU recovery path. No precision gates or tolerances were changed.
These are startup/functional smokes, not a performance campaign or promotion of
GPU compute to the default.

A temporary module-patched diagnostic launched the unchanged production
`FractalFxApplication` lifecycle through `FractalLauncher`, with both
`fractal.gpu.enabled=true` and `fractal.gpu.mandelbrot.enabled=true`. An isolated
home selected the default Mandelbrot overview. The diagnostic read the existing
backend's statistics without replacing the production render service:

- Completed frame: 2592x1674, 4,339,008 dispatched pixels, 4,128,844 certified
  pixels, 210,164 recovered pixels, `cpuFallback=false`.
- The initialized pipeline was Metal. The actual completed overview and native
  status badge were visually inspected in the running application.
- Application shutdown succeeded. `./mvnw -Pgpu-smoke test` additionally passed
  five tests without skips under this JDK.

Separate desktop run: `fractal.fx.tests=true`, `fractal.fx.fullscreen=true`,
`prism.order=mtl`, `reuseForks=false`;
`MacContextMenuFxTest#nativeMenuTracksOnAvailableDisplaysAndInFullScreen`,
`FractalResizeFxTest#reportedTrackpadZoomFullscreenCase+nativeFullscreenZoomMustFillEntireImage`.
Four cases pass without skips, Metal in both forks. Local display only; external
coverage deferred. Original suite skips remain historical; these are new executions.

Current `ColdStartupBenchmark` main/nested lifecycle plus compatible app/dependencies
copied to private snapshot. Real process initialized Metal, emitted exactly one
stage-shown and one later first-layout-pulse marker, exited successfully.
Single-build functionality, not paired timing/recomputed historical verdict.

### Initialization-failure controls

Private graphics copies omitted `libprism_mtl.dylib`, or both it and
`libprism_es2.dylib`. App classes/policy/original cache unchanged. Each used empty
cache, absent `prism.order`, production CPU-only flags and `--package-smoke-test`.

Both final runs exit successfully: JavaFX/AppKit/CPU fallback/complete CPU frame.
Expected load errors precede `ES2Pipeline`, then `SWPipeline` respectively.
Proves host library-load initialization fallback, not crash/hang recovery or all
device initialization failures.

Private early diagnostics: renamed native JARs caused LWJGL search warning;
adding native libraries exposed missing `fractal.gpu.enabled=false`, violating
CPU-only smoke precondition. Corrected final commands set CPU-only flags/explicit
LWJGL native directory; no product/assertion edits to pass.

### Exact PNG comparison

A temporary JavaFX diagnostic initialized each explicit pipeline in a separate
process, then invoked the production `AdaptivePngExportService` with identical
immutable scenes. JDK ImageIO decoded both outputs and compared every ARGB
pixel and both dimensions exactly. All seven pairs matched:

- Five fractal presets at 321x239, Fire palette, 80 iterations.
- Mandelbrot at 65x49, Ice palette, decimal center
  `(-0.743643887037151, 0.13182590420533)`, scale `1e-20`, 120 iterations.
- Mandelbrot overview at 4098x258, Ice palette, 80 iterations, using production
  tiled adaptive export.

Both processes exited successfully and logged their actual ES2/Metal pipeline.
The wide export is an offscreen file check, not a large external-display check.
This establishes equality of these exported pixels, not all palettes/modes,
raw sample-plane equality or displayed-image equivalence.

### Packaging, owner acceptance and deferred coverage

`python3 scripts/packaging/package_runtime.py --version 1.0.0` built and
smoke-tested a DMG using JDK 25. It was mounted read-only, its application copied
to a temporary installation directory, and all 132 copied regular-file hashes
matched the mounted app. The image was detached before execution.

Copied-app smoke passed without `JAVA_HOME`, system-only PATH: Metal/AppKit/
CPU fallback/complete frame. Normal launch showed restored deep frame/badge;
native context menu opened/Escape closed it, shutdown zero. Development-host
copied-DMG check only; Applications install, installed Dock tooltip, Gatekeeper/
notarization/clean-machine remain unverified.

All 35 loose Mach-O files (JDK/launcher/bridge included) and eight JavaFX graphics
natives specify `minos 11.0`; plist claims `LSMinimumSystemVersion=10.11`.
Unresolved discrepancy, not macOS 11 support. Microsoft
[OpenJDK policy](https://learn.microsoft.com/en-us/java/openjdk/support) covers
maintained OSs receiving security fixes; neither policy nor metadata proves a
tested product minimum.

Owner reported activation-candidate startup/shutdown/Dock, Spaces/Mission Control/
partial swipe, progressive rendering/scenes, resize/fullscreen/native UI, light/
dark, lock/sleep/wake, bookmarks/Undo/Redo/restore. No inferred trial counts;
owner declined another ES2 visual comparison. Enlargement AA/fullscreen keyboard
exit remain separate follow-ups.

VirtualBuddy had no listed VMs: guest not run. `ROADMAP.md` defers external
displays, other Macs, Windows/Linux/minimum OS. Guest Metal crash, standard/clean
install, fallback focus repaint, final image comparison/CI remain open. Raw logs,
diagnostic sources/CSVs/PNGs stay private, not selected benchmark evidence.
