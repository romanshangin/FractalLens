# Metal Prism pipeline migration checklist

This checklist gates a switch of the default JavaFX Prism pipeline on macOS from
ES2 (OpenGL) to Metal. It is tracked by roadmap item P1.7. Nothing here is
complete until the change is implemented and the check passes on the final
candidate; an unchecked box is an open requirement, not a known failure.

Background: the application does not set `prism.order`, so JavaFX 26 on macOS
tries ES2 first. On ES2, a frame completed while the window is on another
Space can stay invisible after returning. The defect was not reproduced in a
manual check with `-Dprism.order=mtl`. The timing comparison is recorded in
[the Prism pipeline results](../../benchmarks/PRISM_PIPELINE_RESULTS.md).

## 1. Design decisions before implementation

Preparation is staged separately from default activation. `FractalLauncher`
is the shared plain-Java entry point for Maven (including the native macOS
development launcher) and jpackage. It forwards application arguments to
`Application.launch(FractalApplication.class, args)` without selecting Prism.
The pinned JavaFX default on macOS is `es2,mtl,sw`; user overrides and other
platform defaults remain unchanged. The first three requirements below remain
open until automatic selection and its regression tests are implemented and
the migration gates pass. No extra experimental application switch is added.

The agreed activation design is to set `prism.order=mtl,es2,sw` in that entry
point before `Application.launch`, only for macOS and only when the property
is absent. Even an explicitly empty user value must be preserved. Keep the
focus-repaint decision pending the ES2/Metal trials in section 4.

- [ ] **Single selection point that runs before the toolkit starts.**
  `FractalApplication` extends `Application`; JavaFX's `LauncherImpl` starts the
  toolkit before calling `main` of such a class, so setting `prism.order` there
  can be too late. Add a separate startup class that does not extend
  `Application`, selects the pipeline, then calls
  `Application.launch(FractalApplication.class, args)`. Point
  `fractal.mainClass` (Maven `javafx:run`), the macOS launcher and jpackage at
  it. Tests and benchmark mains that call `Application.launch` directly bypass
  this class and must select a pipeline explicitly.
- [ ] **macOS only, user override preserved.** Set the order only on macOS and
  only when `prism.order` is not already defined, so `-Dprism.order=es2` in
  JVM options or `JAVA_TOOL_OPTIONS` still wins. Windows and Linux keep the
  JavaFX default.
- [ ] **Fallback chain `mtl,es2,sw`, with a precise guarantee.** If Metal fails
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
- [ ] **PNG export, including tiled high-resolution export:** compare decoded
  pixels and dimensions with an ES2 export of the same scene. Byte-identical
  files are not required; encoding and metadata may differ for the same image.

## 3. Windows and displays

- [ ] Resize: slow, fast, down to the minimum size.
- [ ] Fullscreen: enter, exit, switch between fullscreen apps.
- [ ] Move the window between Retina and non-Retina displays and between
  displays with different refresh rates.
- [ ] **Large frames.** ES2 is clamped to 4096 texture size in the pinned
  JavaFX version and Metal reports 16384, but `ImageView` can split a large
  image into several textures, so the texture limit does not by itself crop the
  frame. Criterion: a full frame with no seams or errors and no unacceptable
  memory use for a window above 4096 physical pixels (5K/6K or a full external
  display).
- [ ] Connect and disconnect an external display while the window is open.
- [ ] Minimize to the Dock and restore.

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
- [ ] Sleep and wake with an open window and after a completed render.
- [ ] Screen lock and unlock.
- [ ] Light/dark appearance change with the window open.

## 5. Native AppKit integration

- [ ] Native status badge (`FractalStatusBadge.m`, an NSView above the content
  view) draws above the Metal layer without flicker or loss during resize and
  fullscreen.
- [ ] Native context menu: position relative to the window, cancellation on
  resize, window movement, focus loss and close.
- [ ] Native parameter sheet: focus, Enter, Escape, Cancel, Apply and
  validation errors.
- [ ] macOS application menu and the visible Dock tooltip `FractalLens`.
- [ ] JavaFX fallbacks still work when the native bridge is unavailable.

## 6. Application features

- [ ] Session restore, bookmarks, undo/redo and dialogs (Render Settings, Help).
- [ ] **Vulkan/MoltenVK together with JavaFX Metal.** `-Pgpu-smoke` alone does
  not prove that both Metal clients work in one process. Run the application
  manually with both
  `-Dfractal.gpu.enabled=true -Dfractal.gpu.mandelbrot.enabled=true` and
  confirm that GPU work actually ran (diagnostics or logs); a successful CPU
  fallback does not prove compatibility. Also run `./mvnw -Pgpu-smoke test`.
  This does not promote the GPU compute backend, which keeps its own roadmap
  gates.

## 7. Launch and packaging

- [ ] `./mvnw compile javafx:run` starts on Metal: `-Dprism.verbose=true`
  reports `MTLPipeline`.
- [ ] Dock launcher: `python3 scripts/macos/test_macos_launcher.py`, then a
  manual launch with the Dock tooltip check.
- [ ] DMG from `python3 scripts/packaging/package_runtime.py`: the installed app
  actually runs on Metal.
- [ ] Fallback: with Metal unavailable to initialization, the app starts on the
  next available pipeline (ES2 or SW) as defined in section 1.
- [ ] The user override `-Dprism.order=es2` still selects ES2.
- [ ] Windows and Linux are not affected: no pipeline is selected there.
- [ ] Recheck the VirtualBuddy macOS guest and record the result separately; do
  not infer physical Mac behavior from it.

## 8. Automated checks

- [ ] Full portable suite: `./mvnw clean test`.
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
- [ ] A separate targeted JavaFX run with `-Dprism.order=sw` for the declared
  fallback.
- [ ] Unit tests for pipeline selection: macOS only, user value preserved,
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
declares a 1.05 limit for every group. The first campaign passed 66 of 103
groups with no failure; 37 groups (10 publication, 27 screen latency) remain
`inconclusive`. This section is required.

- [ ] Resolve all 37 inconclusive groups of both campaigns with a predeclared
  confirmation campaign in a new output directory. Selecting only groups with a
  median ratio above 1.00 is not sufficient: a favorable median can still have
  an interval that allows a substantial slowdown.
  The [confirmation policies and stopping rule](../../benchmarks/PRISM_PIPELINE_BENCHMARK.md#predeclared-confirmation-campaign-p17)
  retain all 103 groups. The publication confirmation campaign ended `fail`
  with one failing group (3024x1964 Refined first visible publish), which
  rejects default activation under this gate; the screen-latency confirmation
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
- [ ] If the gate stays inconclusive, the owner may accept the switch for the
  reliability fix with an explicitly recorded latency trade-off. That is a
  separate decision; it must not relabel `inconclusive` as a pass.

## 10. Coverage matrix and documentation

Record the configuration of every check. Unlisted configurations are
unverified.

| Configuration | Status |
| --- | --- |
| Apple M3 Pro, macOS 27.2, built-in Retina display | timing campaign only |
| External non-Retina or different refresh-rate display | not checked |
| 5K/6K or large external display | not checked |
| Other Apple Silicon generations | not checked |
| Intel Mac (integrated or AMD GPU) | not checked |
| VirtualBuddy macOS guest | known Metal crash on the earlier default; recheck |
| Windows, Linux | not affected; CI only |

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

- [ ] Update `docs/platform/MACOS_RUN.md` and `docs/platform/MACOS_UI.md`: the
  selected pipeline, how it is selected and how to return to ES2.
- [ ] Update `docs/packaging/RUNTIME_PACKAGING.md` with the packaged pipeline
  and the minimum macOS.
- [ ] State the verified configurations from the matrix above; one Mac does not
  validate the switch for every Mac.
