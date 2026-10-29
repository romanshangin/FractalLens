# Metal Prism pipeline migration checklist

This checklist tracks final acceptance of the Metal-first macOS Prism startup
candidate in roadmap item P1.7. On 2026-10-29 the owner selected Metal for its
Spaces presentation reliability and accepted the measured latency trade-off,
including the historical failing publication gate. This authorizes default
activation in the implementation candidate without further performance tuning;
it does not declare the remaining acceptance checks passed. Historical
`fail`, `inconclusive` and rejected experiment results remain unchanged.

Background: JavaFX 26 on macOS defaults to `es2,mtl,sw`. On ES2, a frame
completed while the window is on another Space can stay invisible after
returning. Controlled manual trials did not reproduce this with
`-Dprism.order=mtl`. The timing comparison is recorded in
[the Prism pipeline results](../../benchmarks/PRISM_PIPELINE_RESULTS.md).

## 1. Design decisions before implementation

`FractalLauncher` is the shared plain-Java entry point for Maven (including
the native macOS development launcher) and jpackage. Before calling
`Application.launch(FractalFxApplication.class, args)`, it sets
`prism.order=mtl,es2,sw` only on macOS and only when the property is absent.
Even an explicitly empty user value is preserved. Other platforms keep their
JavaFX defaults. No extra experimental application switch is added. The
focus-repaint decision remains pending final-candidate section 4 trials.

- [x] **Single selection point that runs before the toolkit starts.**
  `FractalLauncher`, the compatibility `FractalApplication` main class and the
  three graphical Maven benchmark main classes are plain Java. They route
  startup through `FractalLauncher.launch` before `Application.launch`.
  `FractalFxApplication` owns the production JavaFX lifecycle; benchmarks use
  dedicated lifecycle adapters. Tests and specialized probes starting JavaFX
  directly must still select the intended pipeline explicitly.
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
  application bundle as well, and document the result. The bootstrap
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

The [Prism pipeline protocol](../../benchmarks/PRISM_PIPELINE_BENCHMARK.md)
declares a 1.05 limit for every group. The owner decision below supersedes this
limit as an activation gate, while preserving all measured verdicts.
The first campaign passed 66 of 103
groups with no failure; 37 groups (10 publication, 27 screen latency) remain
`inconclusive`. Unperformed measurements below remain documented gaps; further
confirmation is no longer required for the owner decision.

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
  improvement despite the measured latency trade-off and the failing publication
  gate. The post-change failing group was 1512x982 Refined first visible publish:
  pooled medians 1.03 ms ES2 and 1.13 ms Metal; matched ratio 1.119, interval
  1.064–1.243. Screen-latency confirmation and long-session memory comparison
  remain unperformed. This acceptance does not establish a uniform <=5%
  slowdown, waive other checklist sections or relabel any result as a pass.

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

Bootstrap preparation was additionally exercised on macOS 27.2 ARM64 using the
normal Maven entry point and a JDK 25 app image. The normal Maven run reported
`ES2Pipeline` with the unchanged `es2,mtl,sw` default. Developer tests cover
module entry-point structure and Maven/jpackage entry-point agreement. The
production-entry-point JavaFX smoke passed with the property unset and in
separate explicit `mtl`, `es2` and `sw` forks; verbose logs confirmed the
requested pipeline in each explicit fork. The existing application startup
focus test, packaging script contracts and native launcher contracts also
passed. These checks validate bootstrap preparation only; they do not close
the automatic-selection, displayed-image comparison, fallback-initialization,
Spaces, installed-DMG, VM, minimum-OS or performance requirements above.

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
- `./mvnw clean test` in the active IDE desktop: 639 tests, 134 opt-in skips,
  no failures or errors. The earlier restricted-shell attempt had six
  `ExportFailureMessageTest` errors caused by `No toolkit found`; the unchanged
  full command passed in the desktop session. This is an environment limit,
  not a repaired product defect.
- Production-entry-point smoke with `prism.order` absent: 1 test, no skips;
  verbose output confirmed `MTLPipeline` and the smoke verified CPU completion.
- Full Metal JavaFX command from section 8: 657 tests, 13 conditional skips,
  no failures or errors. All 14 executed `*FxTest` classes reported
  `MTLPipeline`. The skips include three fullscreen checks, the separate
  no-native-bridge configuration, and other opt-in benchmark/GPU checks.
  Skipped scenarios are not accepted by this result.
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

Final-candidate Spaces/thumbnail trials, initialization failure fallback,
fullscreen acceptance, light/dark comparison, display changes, PNG comparison,
long-session memory, GPU coexistence, installed DMG, VM, supported minimum OS,
other Macs, Windows/Linux execution and final CI remain unverified. The earlier
bootstrap checks and historical measurements above retain their own scope.

## Entry-point correction developer checks (2026-10-29)

The original `FractalApplication` main-class name is now plain Java, delegating
to `FractalLauncher`; `FractalFxApplication` owns the unchanged production
lifecycle. The graphical Maven benchmark main classes are also plain Java and
launch dedicated lifecycle adapters through the same bootstrap. The UI package
is opened only to `javafx.graphics` for adapter construction; no implementation
package is exported. The cold-startup probe uses the new lifecycle class, with
its frozen-build compatibility limit documented in the benchmark index.

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
- A restricted-shell full-suite attempt with `prism.order=sw` failed while
  extracting JavaFX natives: the default cache `.lock` was not writable,
  followed by six `ExportFailureMessageTest` initialization errors. The
  unchanged final suite passed in the desktop session. These errors do not
  establish a product defect and were not hidden by changing assertions.

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

Compatibility launches supplied `--package-smoke-test=${TEMP_DIR}/report.properties`
through `javafx.args`; the reports confirmed CPU completion. Palette/GPU smoke
used CPU-only mode, 32x24 frames, one warmup and one sample; GPU-render selected
`overview`. Interaction smoke used a 320x240 logical canvas, one sample, zero
warmup, `wheel` only and `fractal.latency.noScreen=true`. Each process had an
isolated JavaFX cache, verbose Prism logging and fresh private output. CSV
metadata recorded the requested pipeline; verbose logs established the actual
pipeline. These are functional startup checks, not performance results or
GPU/JavaFX coexistence acceptance. No historical measurements were rerun.

The normal `./mvnw compile javafx:run` also initialized Metal with the absent
pipeline property. The completed restored deep-zoom frame and native status
badge were inspected in the running window; application exit succeeded. This
limited check does not close the remaining Spaces, Dock-tooltip, appearance,
export, display, initialization-fallback, packaged/VM or platform acceptance.
Raw logs, CSVs and smoke reports remain private.

### Cold-startup probe integrity correction (2026-10-29)

The startup probe's nested lifecycle class must participate in its identity.
New cold-startup definitions now use `probe_hash_scope=class-family-v1`,
recording the main class and every nested/anonymous class in `probe_files` and
a combined `probe_sha256`. The runner compares the complete file map again
before writing summary results, rejecting byte changes, additions and deletions.
The benchmark index documents the new hash semantics; historical definitions
retain their original single-class identity.

Developer validation: all 101 benchmark script tests passed, including seven
new cold-startup runner regressions. The unchanged-probe test drives the real
runner through all 60 simulated process launches and checks the saved file map
and combined identity. Mutation tests reject changed/added/deleted nested
classes and a deleted main class without writing a summary; separate tests
cover cross-campaign identity changes and a missing main class before launch.
The nested-class modification regression was also run against the original
runner: it failed because the expected integrity error was not raised.
No JavaFX process or measurement campaign was launched for these script tests.
Java sources are unchanged by this correction; the earlier application suites
were not repeated.

## Local specialized acceptance (2026-10-29)

The owner requested all available local checks and deferred unavailable external
display, minimum-macOS, Windows/Linux and additional-Mac execution to the
roadmap. P1.7 remains open. Environment: Apple M3 Pro, macOS 27.2 ARM64,
Microsoft OpenJDK 25 (update 4, vendor patch 1) and JavaFX from `pom.xml`.
Sources are the activation
working tree based on `ba05a0ce5017c9de888fc1e5e1411fb0b6c95fa2`; the source
candidate previously reviewed as CLEAN had content identity
`sha256:5678ea1e228e26cc88ea1666058430e10bd7ce0a87df4b7a1bb780da84e374e3`.
Only documentation changed while these results were recorded; the old identity
does not identify or review the expanded documentation candidate.

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

A separate desktop run enabled `fractal.fx.tests=true`,
`fractal.fx.fullscreen=true`, `prism.order=mtl` and `reuseForks=false`, selecting
`MacContextMenuFxTest#nativeMenuTracksOnAvailableDisplaysAndInFullScreen` and
`FractalResizeFxTest#reportedTrackpadZoomFullscreenCase+nativeFullscreenZoomMustFillEntireImage`.
All four cases passed without skips, with Metal initialized in both forks.
This covers the local display only; external-display coverage remains deferred.
The earlier full-suite fullscreen skips remain part of that original run;
these are new successful executions of those scenarios.

The current `ColdStartupBenchmark` main and nested lifecycle class were copied
with compatible application classes and dependencies to a private snapshot.
Its real JavaFX process initialized Metal, emitted exactly one stage-shown and
one later first-layout-pulse marker, and exited successfully. This single-build
functional check does not constitute a two-build cold-startup timing campaign;
no historical timing verdict was recomputed.

### Initialization-failure controls

Two private copies of the graphics dependency omitted only
`libprism_mtl.dylib`, or both `libprism_mtl.dylib` and `libprism_es2.dylib`.
The application's classes, launcher policy and original dependency cache were
unchanged. Each run used an empty cache, the absent `prism.order` property,
production CPU-only flags and the existing `--package-smoke-test` path.

Both final runs exited successfully and reported JavaFX, AppKit bridge,
CPU fallback and completed CPU render. Logs show the expected native-library
load failures, followed by `ES2Pipeline` for the first case and `SWPipeline` for
the second. This establishes the fallback chain for library-load initialization
failure on this host, not recovery from native crashes/hangs or all device
initialization failures.

Early diagnostic runs are retained privately: isolated renamed native JARs
initially produced an LWJGL search-path warning; supplying native libraries then
exposed a missing `fractal.gpu.enabled=false` flag in the diagnostic command,
which violated the CPU-only smoke precondition. Both corrected final commands
set the production CPU-only flags and the explicit LWJGL native directory.
No product code or assertions were changed to obtain the final results.

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

The copied app passed its smoke with `JAVA_HOME` absent and only system
directories on PATH: initialized Metal, available AppKit bridge, CPU fallback
and complete CPU frame. A normal launch of the same copied app displayed its
completed restored deep-zoom frame and native badge; the AppKit context menu
opened and Escape dismissed it. Shutdown returned zero. This is a copied-DMG
development-host check; standard Applications installation, installed-app Dock
tooltip, Gatekeeper/notarization and a clean machine remain unverified.

Binary inspection of this package found `minos 11.0` in all 35 loose Mach-O
files, including the JDK runtime, launcher and bridge. All eight JavaFX graphics
native libraries also specify 11.0. The generated plist still claims
`LSMinimumSystemVersion=10.11`. This is an unresolved metadata discrepancy,
not proof of macOS 11 support. Microsoft's
[OpenJDK support policy](https://learn.microsoft.com/en-us/java/openjdk/support)
covers actively maintained operating systems receiving security fixes; that
policy and Mach-O metadata do not establish a tested product minimum.

The owner reported successful startup/shutdown and Dock identity, Spaces return,
Mission Control/partial swipe, progressive render and scene changes, window
resize/fullscreen, native UI, both appearances, lock/sleep/wake, bookmarks,
Undo/Redo and session restore on the activation candidate. No controlled-trial
counts are inferred. The owner declined to repeat the earlier ES2 visual
comparison. AA display behavior on enlargement and keyboard fullscreen exit
remain separate product follow-ups.

VirtualBuddy was available, but its selected library showed no virtual machines;
the guest check was not run. External displays, other Macs, Windows/Linux and
minimum-OS execution are explicitly deferred in `ROADMAP.md`. The known guest
Metal crash, standard/clean installation acceptance, fallback focus-repaint
decision, final-candidate image-comparison gaps and final CI remain open.
Raw logs, diagnostic sources, CSVs and PNGs are private working evidence; none
is selected public benchmark data.
