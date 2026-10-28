# CLAUDE.md

Project rules, communication language, architecture constraints, testing policy,
privacy rules and Git policy live in `AGENTS.md`. They are mandatory and are
imported here so they are not duplicated:

@AGENTS.md

This file adds a working map of the repository: layout, commands and where to
look for things. When this file and `AGENTS.md`, `ROADMAP.md` or `pom.xml`
disagree, those files win; fix this file.

## What the project is

FractalLens is a JavaFX desktop fractal explorer (Mandelbrot, Julia, Burning
Ship, Tricorn, cubic Multibrot) with precision-preserving Mandelbrot/Julia deep
zoom, progressive cancellable CPU rendering, frame reuse, antialiasing, palettes,
session restore, bookmarks, undo/redo and PNG export. Supported distribution
targets are macOS and Windows; portable code must also work on Linux. An
optional LWJGL/Vulkan (MoltenVK on macOS) GPU path exists but is off by default.

- Single Maven module, Java module `com.shangin.fractal`
  (`src/main/java/module-info.java`), main class
  `com.shangin.fractal.app.FractalApplication`.
- Java release, JavaFX, JUnit and LWJGL versions: see `pom.xml` properties
  (currently Java 25, JavaFX 26.x, JUnit 6.x, LWJGL 3.4.x).
- Maven Wrapper (`./mvnw`, `mvnw.cmd`) pins the Maven distribution; use it
  instead of a system `mvn`, as `AGENTS.md` requires.

## Repository layout

```
src/main/java/com/shangin/fractal/
  app/         startup (FractalApplication), last-session and bookmark stores,
               macOS app menu, app icon, runtime-artifact smoke entry
  config/      iteration policies (adaptive iteration limit)
  scene/       immutable FractalScene + settings records, JSON format
               (FractalSceneJson), SceneHistory (undo/redo)
  formula/     FractalFormula implementations, presets, destinations, samples
  math/        PreciseComplex, Viewport (BigDecimal-based coordinates)
  render/      CPU pipeline: FractalRenderService (async lifecycle, generations,
               progress batching), RenderBackend implementations
               (DirectDouble, MandelbrotPerturbation + BLA, JuliaDeepZoom,
               PrecisionSelecting), frame reuse (FrameReusePlanner,
               FrameReprojection, PixelShift*), caches, RenderGrid/RenderTarget
  controller/  FractalRenderController: bridges UI camera and render service,
               iteration policy, frame reuse, recoloring, RenderStatus
  coloring/    palettes, gradient stops, smooth/histogram/orbit-trap coloring
  export/      AdaptivePngExportService (tiled high-res export), PngExporter,
               interactive AA service
  gpu/         optional Vulkan runtime, MoltenVK session, Mandelbrot and
               palette kernels, precision gate, diagnostics
  ui/          JavaFX views (MainView, FractalView, FractalSurface), menus,
               dialogs, native AppKit sheet wrappers (Mac*Sheet,
               MacContextMenu), indicators; also FX-launched benchmark mains
src/main/macos/        AppKit bridge (FractalContextMenu.m,
                       FractalParameterSheet.m), Dock launcher
                       (FractalLauncher.c), Info.plist
src/main/resources/    app icons, GLSL compute shaders (gpu/*.comp), JavaFX CSS
src/test/java/...      JUnit tests mirroring main packages, plus benchmark
                       mains (*Benchmark.java) run through Maven profiles
scripts/               build, CI, packaging, publication, quality, benchmark
                       and orchestration tools (see scripts/README.md)
docs/architecture/     session format, PNG export, fractal destinations
docs/development/      CI, publication, task orchestrator, Julia performance
docs/platform/         MACOS_RUN.md, MACOS_UI.md, GPU_RUNTIME.md
docs/packaging/        RUNTIME_PACKAGING.md (jlink/jpackage DMG/EXE)
benchmarks/            selected public benchmark protocols and evidence
                       (index: benchmarks/README.md)
.agents/skills/        shared workflow skills: implement/review/verify/deliver
.claude/skills/        Claude Code wrappers for the shared skills
.github/workflows/     ci, portable-ci, publication-checks, qodana, runtime-artifacts
packaging/             third-party license inventory for runtime artifacts
design/app-icon/       icon sources
```

`target/`, `node_modules/`, `.idea/` (mostly) and raw benchmark output are not
part of the source; do not edit or commit them.

### Main runtime flow

`FractalApplication` → `MainView` (menus, indicators) → `FractalView`
(input, camera, debounced requests) → `FractalRenderController` →
`FractalRenderService` → `PrecisionSelectingRenderBackend`, which picks
`DirectDoubleRenderBackend` when hardware doubles are safe and a deep-zoom
backend otherwise. Progress is published in batches on the caller's executor
(the FX thread for the UI) and stale generations must never publish. Colors are
applied by `FractalColorizer` / `coloring`, and the frame is shown through
`FractalSurface` / `SurfaceBuffer`.

The scene (`scene/FractalScene`) is the reproducible state used by session
restore, bookmarks, shared presets, undo/redo and export.

## Build and run

```sh
./mvnw compile                      # compile (on macOS also builds the AppKit bridge)
./mvnw compile javafx:run           # run the app (needs a graphical session)
./mvnw clean test                   # full portable suite, CPU default
```

Notes:

- On macOS the `macos-native-menu`, `macos-app-name`, `gpu-macos` and
  `macos-aarch64`/`macos-arm64` profiles activate automatically.
  `scripts/macos/build-macos-menu` compiles the Objective-C bridge during
  `process-classes` (requires Xcode Command Line Tools), and `javafx:run` uses
  `scripts/macos/macos-java` so the Dock shows `FractalLens`.
- On Windows, `runtime-windows` adds LWJGL natives (no Vulkan GPU path yet).
- Surefire sets `fractal.gpu.enabled=false`; tests are CPU-only by default.
- GPU runtime toggles for manual runs (see `docs/platform/GPU_RUNTIME.md`):
  `JAVA_TOOL_OPTIONS=-Dfractal.gpu.mandelbrot.enabled=true ./mvnw javafx:run`,
  `-Dfractal.gpu.palette.enabled=true`, `-Dfractal.gpu.enabled=false`.

## Tests

| Kind | Command | Notes |
| --- | --- | --- |
| Targeted | `./mvnw -Dtest=ClassA,ClassB test` | fast iteration only |
| Portable suite | `./mvnw clean test` | required before handoff of production changes |
| JavaFX integration | `./mvnw -Dfractal.fx.tests=true "-Djavafx.cachedir=$TMPDIR/fractallens-fx-<task>" -DreuseForks=false -Dtest=SomeFxTest test` | `*FxTest` classes, gated by `@EnabledIfSystemProperty(fractal.fx.tests)`; needs an active desktop session, not a sandbox |
| GPU native smoke | `./mvnw -Pgpu-smoke test` | real device only; results apply to that device |
| FP32 native precision | `./mvnw -Pfp32-native test` | `MandelbrotPrecisionNativeTest` |
| macOS launcher | `python3 scripts/macos/test_macos_launcher.py` | after launcher/bundle/launch-option changes |
| Script tests | per changed directory, see "Script tests" in `AGENTS.md` | Python `unittest`, `node --test` for orchestration, Bash/PowerShell CI wrappers |

FX test classes live mostly in `src/test/java/com/shangin/fractal/ui/` plus
`app/FractalApplicationFxTest`. Native GPU tests are `*NativeTest` under `gpu/`.
Without the opt-in property these tests are skipped, and a skip is not a pass.

CI runs `./mvnw --batch-mode --no-transfer-progress -Dfractal.gpu.enabled=false clean test`
through `scripts/ci/run-ci-tests` on Ubuntu (`ci.yml`) and macOS/Windows
(`portable-ci.yml`). Details: `docs/development/CI.md`.

## Benchmarks

Benchmarks are opt-in Maven profiles; never wire them into the default build.

- Headless (exec `integration-test` phase, test classpath): `benchmark`
  (frame reuse), `baseline-benchmark`, `aa-benchmark`, `formula-benchmark`,
  `symmetry-benchmark`, `render-tuning-benchmark`, `julia-pipeline-benchmark`,
  `perturbation-benchmark`, `series-approximation-benchmark`,
  `distance-estimation-benchmark`, `fp32-precision`.
  Example: `./mvnw -Pperturbation-benchmark verify -DskipTests` (the
  `fp32-precision` docs use `-DskipTests integration-test`). Protocol-specific
  properties are documented in the matching `benchmarks/*_BENCHMARK.md`.
- JavaFX-launched (replace the main class for `javafx:run`):
  `palette-benchmark`, `interaction-latency`, `gpu-render-benchmark`,
  `gpu-resident-benchmark`, `gpu-diagnostics`.
  Example: `./mvnw -Ppalette-benchmark javafx:run`.
- Paired A/B campaigns, frozen builds and analyzers live in
  `scripts/benchmarks/`; protocols and accepted evidence in `benchmarks/`.
  Record new experiments separately; never rewrite historical results.

## Packaging and quality

- Runtime installers: `python3 scripts/packaging/package_runtime.py --version 1.0.0`
  (see `docs/packaging/RUNTIME_PACKAGING.md`).
- Publication/privacy checks before committing evidence:
  `python3 scripts/publication/check_public_data.py`, then both
  `python3 scripts/publication/check_secrets.py` (Git history) and
  `python3 scripts/publication/check_secrets.py --tree` (tracked and
  non-ignored untracked files).
  Both need Gitleaks 8.30.1 on `PATH` or `--gitleaks <path>`;
  `scripts/publication/install-gitleaks` is the Linux x64 CI installer only.
- Qodana baseline and acceptance: `.qodana/`, `scripts/quality/`.

## Where to look first

- What to work on next and acceptance criteria: `ROADMAP.md`, section
  "0. Prioritized backlog" (currently open: P2.3 export acceptance, P2.4 native
  modal windows acceptance, P3, P4).
- UI behavior and the native/JavaFX boundary: `docs/platform/MACOS_UI.md`.
- Launch identity and launcher: `docs/platform/MACOS_RUN.md`.
- Scene file format, history, bookmarks: `docs/architecture/SESSION_FORMAT.md`.
- Export pipeline: `docs/architecture/PNG_EXPORT.md`.
- GPU scope, flags and fallback: `docs/platform/GPU_RUNTIME.md`.
- Task stage workflow (implement → review → verify → deliver):
  `.agents/skills/*/SKILL.md` and `docs/development/TASK_ORCHESTRATOR.md`.
  Claude Code loads them through thin wrappers in `.claude/skills/*/SKILL.md`
  that point to the shared files. `verify-task` and `deliver-task` run only when
  invoked explicitly (`/verify-task`, `/deliver-task`). Change workflow rules in
  `.agents/skills/`, and keep wrapper descriptions in sync.
  `scripts/orchestration/task-orchestrator.ts --executor claude` runs the whole
  workflow through Claude Code stages; the default executor is Codex.
