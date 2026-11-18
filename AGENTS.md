# FractalLens project instructions

## Communication and repository language

- Speak Russian to the user, using the informal form of address.
- Use English for all repository content: production comments, Javadocs, tests,
  test names/comments, assertions, UI/error/log messages, documentation,
  benchmark notes, commits and new identifiers. Add Russian only for explicitly
  requested Russian localization.
- Report what changed, why, checks performed and remaining gaps. Do not claim full
  verification with missing/failed required tests or unchecked relevant visible
  behavior. Distinguish implementation completion from verification completion.
- Distinguish product defects from environment failures with concrete evidence.
- "Continue" means execute the next clear accepted-plan or `ROADMAP.md` item,
  not repeat the plan.

## Project overview and sources of truth

FractalLens is a JavaFX desktop fractal explorer: Mandelbrot, Julia, Burning
Ship, Tricorn and cubic Multibrot; arbitrary-precision Mandelbrot/Julia deep
zoom; progressive cancellable CPU rendering with frame reuse, antialiasing and
palettes; sessions, bookmarks, undo/redo and PNG export; optional LWJGL/Vulkan
GPU rendering (macOS MoltenVK, disabled by default); native macOS
AppKit/Objective-C integration and launcher. It ships macOS/Windows
distributions and keeps Linux code portable.

It is a single Maven module with Java module `com.shangin.fractal`
(`src/main/java/module-info.java`). The main class
`com.shangin.fractal.app.FractalLauncher` starts JavaFX through
`FractalFxApplication`; `FractalApplication` is a plain-Java compatibility entry
point using the same bootstrap. Java, JavaFX, JUnit and LWJGL versions come from
`pom.xml` properties.

Keep portable Java, JavaFX and CPU rendering compatible with macOS, Windows and
Linux. `ROADMAP.md` defines packaging/hardware acceptance targets. macOS-specific
AppKit integration must degrade safely elsewhere.

| Source | Owns |
| --- | --- |
| `ROADMAP.md` | Work order ("Remaining work by function"), requirements, acceptance criteria |
| `pom.xml` | Dependency/plugin versions, Maven profiles, launch/test settings |
| `docs/platform/MACOS_RUN.md` | macOS launch and application identity |
| `docs/platform/MACOS_UI.md` | Current UI behavior and native/JavaFX boundary |
| `docs/platform/GPU_RUNTIME.md` | GPU scope, runtime flags and CPU fallback |
| `docs/architecture/SESSION_FORMAT.md`, `PNG_EXPORT.md`, `SCENE_GALLERY.md` | Scene file format, history, bookmarks; export pipeline; Interesting Places catalog |
| `docs/development/CI.md`, `TASK_ORCHESTRATOR.md`, `PUBLICATION.md` | CI, task workflow orchestration, evidence publication |
| `docs/packaging/RUNTIME_PACKAGING.md` | jlink/jpackage DMG/EXE runtime artifacts |
| `benchmarks/README.md`, relevant `*_BENCHMARK.md`, `*_VALIDATION.md`, `*_RESULTS.md` | Measurement protocols and evidence |
| `scripts/README.md` | Build, CI, packaging, publication, quality, benchmark and orchestration tools |
| Existing tests | Executable behavioral contract |

Resolve source conflicts explicitly: identify stale documentation/tests or an
intentional task change, prefer the contract's owning source, and report conflicts
that affect implementation or verification. Do not duplicate `pom.xml` version
declarations without a specific reason.

A direct user task takes precedence over autonomous roadmap selection; use the
roadmap for that task's requirements, dependencies and acceptance. An unspecified
"continue" follows the execution order in `ROADMAP.md`, section "Remaining work
by function". Do not start lower-priority optimization with open dependencies or
decision gates. Task workflows may strengthen, never weaken, these project
constraints.

Keep historical benchmark results tied to their original implementation. Record
new experiments separately with environment, inputs, code state and decision.

## Repository map

Java packages live under `src/main/java/com/shangin/fractal/` (see
"Architecture constraints"). Other locations:

| Path | Contents |
| --- | --- |
| `src/main/macos/` | AppKit bridge (`FractalContextMenu.m`, `FractalApplicationMenu.m`, `FractalParameterSheet.m`, `FractalStatusBadge.m`, `FractalGalleryPanel.m`), Dock launcher `FractalLauncher.c`, `Info.plist` |
| `src/main/resources/com/shangin/fractal/` | App icons, GLSL compute shaders (`gpu/*.comp`), JavaFX CSS |
| `src/test/java/` | JUnit tests mirroring main packages; benchmark mains (`*Benchmark.java`) |
| `docs/` | `architecture/`, `development/`, `platform/`, `packaging/` documents |
| `benchmarks/` | Selected public benchmark protocols and evidence |
| `scripts/` | Reproducible tooling, indexed in `scripts/README.md` |
| `.agents/skills/`, `.claude/skills/` | Task workflow skills (see "Task workflow") |
| `.github/workflows/` | `ci`, `portable-ci`, `publication-checks`, `qodana_code_quality`, `runtime-artifacts` |
| `.qodana/`, `scripts/quality/` | Qodana configuration, baseline and acceptance |
| `packaging/`, `design/app-icon/` | Third-party license inventory for runtime artifacts; icon sources |

`target/`, `node_modules/`, `.idea/` and raw benchmark output are not source; do
not edit or commit them.

Main runtime flow: `FractalLauncher` -> `FractalFxApplication` -> `MainView`
(menus/indicators) -> `FractalView` (input/camera/debounce) ->
`FractalRenderController` -> `FractalRenderService` ->
`PrecisionSelectingRenderBackend`, which picks `DirectDoubleRenderBackend` when
doubles are safe and a deep-zoom backend otherwise. Batch progress uses the
caller executor (FX in the UI); stale generations never publish.
`FractalColorizer`/`coloring` colors frames; `FractalSurface`/`SurfaceBuffer`
displays them. `scene/FractalScene` is the reproducible state behind session
restore, bookmarks, shared presets, undo/redo and export.

## Build and run

```shell
./mvnw compile              # on macOS also builds the AppKit bridge
./mvnw compile javafx:run   # run the app; needs a graphical session
```

- On macOS the `macos-native-menu`, `macos-app-name`, `gpu-macos` and
  `macos-aarch64`/`macos-arm64` profiles activate automatically.
  `scripts/macos/build-macos-menu` compiles the Objective-C bridge during
  `process-classes` (requires Xcode Command Line Tools); `javafx:run` uses
  `scripts/macos/macos-java` so the Dock shows `FractalLens`.
- On Windows, `runtime-windows` adds LWJGL natives (no Vulkan GPU path yet).
- Manual GPU toggles (`docs/platform/GPU_RUNTIME.md`), for example
  `JAVA_TOOL_OPTIONS=-Dfractal.gpu.mandelbrot.enabled=true ./mvnw javafx:run`;
  also `-Dfractal.gpu.palette.enabled=true` and `-Dfractal.gpu.enabled=false`.
- Runtime installers: `python3 scripts/packaging/package_runtime.py --version 1.0.0`.

## Project rules

- Inspect existing flow, dependencies and object lifecycle before editing.
- Make the smallest correct change; do not refactor unrelated code.
- Keep public APIs stable unless the task explicitly requires a change. Reuse abstractions
  instead of adding parallel logic.
- Use the JDK/existing dependencies when reasonable. Justify any new dependency,
  check modular-build and all-platform compatibility, run available platform
  checks and identify platforms not actually verified.
- Prefer readable code; keep methods/classes focused.
- Leave no temporary diagnostics, experimental switches or benchmark code in the
  production path without an explicit purpose.
- Never hide failures, failing tests or unexpected warnings to pass validation.
  Suppress warnings only narrowly, with an understood, unavoidable and documented
  reason.
- Add/update tests for observable behavior changes; fix root causes.
- Update docs for changes to visible behavior, platform support, launch/test
  commands, benchmark protocols, roadmap acceptance, fallback paths or
  precision/performance decisions.
- Complete roadmap items only after acceptance passes. Proposals/expected
  speedups are not completed features.

### Privacy and published data

- Before committing new/generated evidence, run the Git policy's publication
  checks. Keep `scripts/publication/public_benchmark_files.json` and the benchmark
  index consistent when selecting new evidence.
- Never attach personal self-hosted runners or expose secrets to pull requests.
- Follow `benchmarks/README.md` and `docs/development/PUBLICATION.md`: privately
  archive raw logs, dumps and unselected evidence; publish only reviewed
  summaries and explicitly selected sanitized data. Preserve measurement
  identities, label redacted derivatives, and never imply private inputs are in
  the public checkout or historical checks were rerun.
- Never commit active credentials, tokens, private keys, recovery codes or other
  secrets. Inspect new benchmark results, logs, reports, generated files and
  diagnostics for secrets/personal machine data; GitHub masking is insufficient.
- Explicitly approved public identity:
  - Surname: `Shangin`, including `com.shangin` and `com.shangin.fractal` identifiers.
  - Contact email: `shangin.ro@gmail.com`.
  Do not rewrite history solely to remove these. This exception permits no other
  personal/machine data, including prohibited fields containing an allowlisted
  value. Expanding the allowlist requires explicit user approval.
- Apart from that email, never publish login names, home directories, email
  addresses, computer/host names, serial numbers, hardware UUIDs, MAC addresses,
  private/public IPs or machine-specific temporary/workspace paths in repository
  files, evidence, Actions logs or uploads. Use repository-relative paths or
  `${USER_HOME}`, `${PROJECT_DIR}`, `${TEMP_DIR}`.
- Benchmark provenance may contain only reproducibility-relevant details:
  OS/version, architecture, CPU/GPU model, core/memory counts, driver/runtime
  versions, power/thermal state. Filter system reports to these fields; never
  upload unfiltered hardware/software profiler output.
- For a live secret in any working tree/published location: revoke/rotate with its
  issuer first, remove current copies, then arrange cleanup of every affected
  published Git ref, Actions log/artifact, release, cache and retained copy.
  Later file deletion neither removes history nor invalidates credentials.
  Verify each destination; a current-tree scan does not prove cleanup.
- Before publishing historical/generated evidence, scan included files and review
  relevant published history and retained Actions logs/artifacts. Shared-history
  rewrites and published log/artifact deletion require explicit owner authorization.

## Architecture constraints

Preserve package boundaries; move responsibilities only for a clear reason.

| Area | Responsibility (key types) |
| --- | --- |
| `app` | Startup and application integration: launchers, last-session and bookmark stores, macOS app menu, app icon, runtime-artifact smoke entry |
| `scene` | Scene/rendering configuration: immutable `FractalScene` and settings records, `FractalSceneJson`, `SceneHistory` (undo/redo) |
| `formula` | `FractalFormula` implementations, presets, destinations, samples |
| `math` | Coordinates, viewports, numeric types: `PreciseComplex`, `BigDecimal`-based `Viewport` |
| `render` | CPU/deep-zoom rendering, caches, reuse, progress publication: `FractalRenderService` (async lifecycle, generations, progress batching), `RenderBackend` implementations (DirectDouble, MandelbrotPerturbation + BLA, JuliaDeepZoom, PrecisionSelecting), `FrameReusePlanner`, `FrameReprojection`, `PixelShift*`, `SpareFrameStorage`, `RenderGrid`/`RenderTarget` |
| `config` | Iteration-limit policies (adaptive limit) |
| `controller` | Rendering orchestration/lifecycle: `FractalRenderController` (camera bridge, iteration policy, reuse, recoloring, `RenderStatus`) |
| `ui` | JavaFX presentation, input, menus, dialogs, result display: `MainView`, `FractalView`, `FractalSurface`, native AppKit wrappers (`Mac*Sheet`, `MacContextMenu`), indicators, FX-launched benchmark mains |
| `coloring` | Palettes, gradient stops, smooth/histogram/orbit-trap coloring |
| `export` | Export workflows/rendering: `AdaptivePngExportService` (tiled high-res), `PngExporter`, interactive AA service |
| `gpu` | Optional Vulkan/LWJGL runtime/backends: MoltenVK session, Mandelbrot and palette kernels, precision gate, diagnostics |
| `src/main/macos` | Native macOS sources/metadata |
| `scripts` | Reproducible build, diagnostic, benchmark tools |

UI must not implement fractal math; formulas must not depend on JavaFX; CPU
execution must not require GPU code.

### Rendering and concurrency

Asynchronous, cancellable rendering requires accounting for old/new scene races
in rendering, orchestration, transitions, publication, cancellation and lifecycle.

- Never publish tiles, progress or frames from cancelled/superseded generations.
  Scene changes, resize, pan, zoom and reset must not receive stale pixels.
- Preserve atomic publication against seams, bands and mixed frames.
- Keep calculation, worker waits and heavy I/O off the JavaFX Application Thread;
  mutate its scene graph only there. Native callbacks from other threads dispatch
  application actions through `Platform.runLater`.
- Explicitly close `AutoCloseable` resources, executors and native handles;
  unregister listeners when their owner lifecycle ends.
- Verify idempotent close and cancellation during partial work. A cancelled
  coordinator `Future` is drained only after registered workers actually stop.
- Reuse changes must cover pan in every direction, no overlap, zero shift, resize,
  antialiasing and iteration-limit changes.
- Judge publication optimizations by gesture-to-visible latency, not throughput alone.

### macOS integration

Prefer native macOS behavior; retain a working JavaFX fallback.

- Do not imitate standard window buttons, title bars or system menus. Keep
  standard JavaFX decoration so AppKit provides the native frame/controls.
- Native bridges must preserve Windows/Linux and startup on library-load failure.
  Keep the JavaFX context-menu fallback when AppKit is unavailable.
- Use popup coordinates relative to the owning content view, in logical points;
  account for its flipped coordinates. Never multiply by Retina scale or convert
  via primary-display height.
- Cancel native menu sessions on close, replacement, resize, window movement,
  focus loss and scene detachment.
- JNI/Objective-C callbacks must not mutate JavaFX UI directly.
- Launcher changes must preserve JVM/application arguments, `@argfiles`,
  `JDK_JAVA_OPTIONS`, exit status and paths with spaces; never modify the installed JDK.
- Identity acceptance requires the visible Dock tooltip `FractalLens`; process
  name/`localizedName` is insufficient.
- Preserve `./mvnw compile javafx:run` development launch (`javafx-maven-plugin` run goal).

### GPU rules

GPU rendering is optional; CPU is the default and mandatory fallback.

- Local speedup alone cannot justify enabling GPU by default.
- Every GPU change needs a CPU reference and conformance check. Never weaken
  precision gates for performance or tolerate mismatches where exact output is required.
- Report unavailable devices/capabilities accurately; fall back to CPU.
- Do not generalize Apple Silicon results to Intel/AMD Mac or Windows.
- Opt-in native GPU tests do not replace the portable CPU suite.
- Promotion requires `ROADMAP.md` correctness, latency, throughput, memory and
  platform gates to pass.

## Numerical correctness and deep zoom

Numerical correctness takes priority over local performance gains.

- Preserve decimal viewport inputs; never prematurely convert to `double` or
  reconstruct exact coordinates from rounded values.
- Validate coordinates, scale, dimensions and iteration limits before expensive work.
- Preserve center, pixel spacing, dimension parity and mapping when changing grids.
- Mandelbrot assumptions need separate proof for Julia, Burning Ship, Tricorn or Multibrot.
- Julia uses pixel `z0` and fixed `c`; perturbation uses `delta z0`, not a copied
  Mandelbrot `delta c` formulation. Do not use Mandelbrot cardioid/interior shortcuts.
- Julia backend selection cannot rely on viewport spacing alone: orbit precision
  can fail near the critical point.
- Keep exact fallback correct and bounded; never silently publish approximate wrong frames.
- Formula/precision changes need sample and complete-frame checks at known coordinates.
- Small frames do not prove realistic-window usability. Measure realistic frames
  and time to first visible progress.

## Testing rules

Match validation to risk. Use the checked-in pinned Maven Wrapper: `./mvnw` on
macOS/Linux, `mvnw.cmd` or `.\mvnw.cmd` on Windows. Commands below use POSIX form;
system `mvn` is not recorded validation. Surefire sets
`fractal.gpu.enabled=false`, so tests are CPU-only by default.

### Mandatory sleep inhibition for long tests

All long-running tests (multi-minute suites, sustained checks and runs of unknown
or extended duration), especially performance/benchmark campaigns, MUST run with
temporary OS-level sleep inhibition on the machine actually executing them.
Keep both the system and display awake throughout the entire test/campaign,
including warmups, pauses between processes and analysis that belongs to the run.
Do not rely on a system-sleep-only assertion for graphical testing.

- **macOS:** wrap the complete command in `caffeinate -di <command> [arguments...]`.
  For a multi-command campaign, wrap the foreground controller script; it must
  wait for all test children. Bare `caffeinate -i` does not protect the display.
- **Windows:** use an owned, temporary power request or
  [SetThreadExecutionState](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate)
  with `ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED` on a thread
  that remains alive for the run. Check acquisition success; release the owned
  request or restore that thread's previous execution state in `finally`.
- **Linux:** use an available session/OS inhibitor, for example
  `systemd-inhibit --what=idle:sleep --mode=block <command> [arguments...]`.
  For graphical runs also acquire the desktop session's display/screensaver
  inhibitor: [logind inhibition](https://github.com/systemd/systemd/blob/main/docs/INHIBITOR_LOCKS.md)
  alone does not cover every desktop's screen blanking/locking policy.

Before starting, verify that the protection was acquired and, for graphical
runs, that the desktop is unlocked and active. Monitor these conditions during
long campaigns. Sleep inhibition does not prove that the session stays unlocked.
If protection is unavailable, acquisition fails, or the graphical session locks
or loses its active display, stop affected measurements and report an environment
blocker; preserve the evidence and do not promote it as valid timing acceptance.
Do not bypass a lock or weaken authentication/security policies.

The protection MUST end after success, failure, cancellation, timeout or runner
interruption. Use command-scoped lifetime and `finally`/signal cleanup as needed;
drain or terminate owned test children before releasing it. Verify afterwards
that this run's helper/assertions are gone and the prior power-management state
is restored. Never change persistent sleep/display settings, leave a detached
keep-awake process, or kill another application's/user's inhibitors. Record the
protection method and cleanup outcome in the validation evidence.

### Targeted tests

Run nearest tests first during development:

```shell
./mvnw -Dtest=RelevantTest test
```

Separate multiple classes with commas. Targeted passes are intermediate checks,
not substitutes for the pre-handoff suite.

### Portable test suite

Before handing off changes under `src/` (production, resources including CSS,
shaders/native sources, tests), or to `pom.xml`, `.mvn/`, `mvnw`, `mvnw.cmd`, run:

```shell
./mvnw test
```

After build/dependency/module changes or possible stale output, use:

```shell
./mvnw clean test
```

Targeted tests never replace this suite. Also run applicable opt-in JavaFX, GPU,
native and benchmark checks. For `scripts/` changes, run matching script tests.
Build/CI wrappers such as `scripts/macos/build-macos-menu` and
`scripts/ci/run-ci-tests` also require the full portable suite. Documentation-only
changes need no application suite. If environment failure or a user constraint
blocks it, report the limitation and exact checks run; do not imply full validation.

CI runs `./mvnw --batch-mode --no-transfer-progress -Dfractal.gpu.enabled=false clean test`
through `scripts/ci/run-ci-tests` on Ubuntu (`ci.yml`) and macOS/Windows
(`portable-ci.yml`); see `docs/development/CI.md`.

### Script tests

Run from the repository root for each changed directory:

| Changed directory | Command |
| --- | --- |
| `scripts/orchestration/` | `node --test scripts/orchestration/test_task_orchestrator.ts scripts/orchestration/test_validation_evidence.ts` (Node.js 24 or later) |
| `scripts/benchmarks/` | `python3 -m unittest discover -s scripts/benchmarks -p 'test_*.py'` |
| `scripts/publication/` | `python3 -m unittest discover -s scripts/publication -p 'test_*.py'` |
| `scripts/quality/` | `python3 -m unittest discover -s scripts/quality -p 'test_*.py'` |
| `scripts/packaging/` | `python3 scripts/packaging/test_package_runtime.py` |
| `scripts/ci/` | `bash scripts/ci/test-run-ci-tests`; for PowerShell changes also `scripts/ci/test-run-ci-tests.ps1` in a PowerShell session |
| `scripts/macos/` | `python3 scripts/macos/test_macos_launcher.py` (macOS with native developer tools) |

Analyzer tests do not run campaigns; PowerShell on macOS is not Windows acceptance.
Identify unrun platform-specific script tests.

### Reusing automated execution evidence

Orchestrated checks may reuse earlier successful execution only for the exact
current candidate (base revision, merge base, content), check definition and
applicable environment, with intact execution logs. Use
`scripts/orchestration/check.ts` and the private registry per
`docs/development/TASK_ORCHESTRATOR.md`; prose handoffs are insufficient.
Do not repeat valid PASS evidence for confidence alone. Reuse waives no portable,
subsystem, visible or independent-review requirements. Replace stale,
insufficient or suspicious evidence; a required skip is not PASS. Stage-specific
reuse duties are in the workflow skills.

### JavaFX and visible behavior

Model tests alone cannot validate visible UI changes.

- Add/update appropriate JavaFX tests. Inspect the running app for layout, system
  menus, window-manager, appearance, focus or animation behavior.
- Check light/dark appearance for color/native-material changes.
- Check dialog focus, Enter, Escape, Cancel, Apply and validation errors; preserve
  invalid drafts for correction.
- Fractal changes/Reset View must not expose stale or mixed pixels.
- Remove loading only after full `RenderStatus.COMPLETE`, or finish correctly on `FAILED`.
- Viewport-dependent menu tests must await initial rendering before actions.
- Explicitly list unchecked visual behavior.

JavaFX integration is opt-in. `*FxTest` classes (mostly in
`src/test/java/com/shangin/fractal/ui/`, plus `app/FractalApplicationFxTest` and
`app/FractalLauncherFxTest`) are gated by
`@EnabledIfSystemProperty(fractal.fx.tests)`:

```shell
./mvnw -Dfractal.fx.tests=true "-Djavafx.cachedir=TASK_SPECIFIC_TEMP_DIRECTORY" -DreuseForks=false -Dtest=RelevantFxTest test
```

- Portable JavaFX tests require an active graphical session; macOS-specific/native
  integration requires an active graphical macOS session.
- With a known display-less sandbox, run JavaFX tests/initializing benchmarks
  directly in the active desktop session. If an unexpected sandbox run reaches
  `Screen.getMainScreen` with no screens, rerun the unchanged test on desktop and
  record the sandbox failure as an environment limit.
- Replace `TASK_SPECIFIC_TEMP_DIRECTORY` with a fresh task-specific platform-temp
  directory: `$TMPDIR/fractallens-fx-<task>` on macOS, `/tmp/fractallens-fx-<task>`
  on Linux, `$env:TEMP\fractallens-fx-<task>` in PowerShell. Fresh caches prevent
  other runs' `.lock` interference. Never publish resolved machine-specific paths.
- Use `-DreuseForks=false` when combining suites that call `Platform.exit()`.
- `No toolkit found`, Prism, CVDisplayLink, `Screen.getMainScreen` and cache `.lock`
  failures may be environmental; classify with evidence before blaming the product.
- A skipped test is not a pass.

### GPU tests

Run native GPU tests (`*NativeTest` in `gpu/`) separately:

```shell
./mvnw -Pgpu-smoke test     # native GPU smoke
./mvnw -Pfp32-native test   # FP32 precision, MandelbrotPrecisionNativeTest
```

Results apply only to the tested device, driver and platform.

### macOS launcher tests

After launcher, bundle-metadata or launch-option changes, run:

```shell
python3 scripts/macos/test_macos_launcher.py
```

Launch current sources/rebuilt bridge, including `compile`:

```shell
./mvnw compile javafx:run
```

Manually verify Dock identity and affected system behavior.

### Benchmarks

Benchmarks are opt-in Maven profiles; never wire them into the default build.

- Headless (exec in `integration-test`, test classpath): `benchmark` (frame
  reuse), `baseline-benchmark`, `aa-benchmark`, `formula-benchmark`,
  `symmetry-benchmark`, `render-tuning-benchmark`, `julia-pipeline-benchmark`,
  `perturbation-benchmark`, `series-approximation-benchmark`,
  `distance-estimation-benchmark`, `fp32-precision`. Example:
  `./mvnw -Pperturbation-benchmark verify -DskipTests` (the `fp32-precision`
  docs use `-DskipTests integration-test`).
- JavaFX-launched (replace the `javafx:run` main class): `palette-benchmark`,
  `interaction-latency`, `gpu-render-benchmark`, `gpu-resident-benchmark`,
  `gpu-diagnostics`. Example: `./mvnw -Ppalette-benchmark javafx:run`.
- Protocol-specific properties are in the matching `benchmarks/*_BENCHMARK.md`;
  paired A/B campaigns, frozen builds and analyzers live in `scripts/benchmarks/`.

### Test design

- Regression tests should fail before the fix and pass after it.
- Test observable behavior unless an internal contract is itself under test.
- Coordinate concurrency deterministically; do not replace flaky checks with
  longer `sleep` when a concrete state can be awaited.
- Use decimal fixtures/math properties for precision regressions.
- Reuse/publication tests must inspect frames/pixels, not callbacks alone.
- Assert UI state after relevant JavaFX queue work.
- Keep native unit/contract tests alongside real-platform validation.

## Task workflow

Repository changes run through stages implement -> review -> verify -> deliver.
Each stage has one skill, `.agents/skills/<stage>-task/`, read by Codex.
`.claude/skills/<stage>-task` are symlinks to those folders for Claude Code; edit
only `.agents/skills/`. A new skill also needs its symlink (exactly
`../../.agents/skills/<name>`) and an entry in `SKILL_LINKS` in
`scripts/publication/check_public_data.py`, which rejects every other symlink. Claude skills on Windows need a checkout with symlinks enabled. `verify-task` and
`deliver-task` run only when invoked explicitly (`disable-model-invocation` for
Claude, `agents/openai.yaml` for Codex).
`scripts/orchestration/task-orchestrator.ts` runs the whole workflow with Codex
(default) or `--executor claude`.
When an agent starts a workflow stage in a separate session outside the
orchestrator (for example a Claude Code session started for `review-task`,
`verify-task` or `deliver-task`), it starts that session with the stage's model
and reasoning effort from the orchestrator: `CLAUDE_MODELS` for Claude Code,
`ROLES` for Codex, as listed in `docs/development/TASK_ORCHESTRATOR.md` ("Roles
and transitions"). Read the current values there rather than copying them. If the
session cannot be started with them, report the model and effort it actually uses.

This file holds all agent instructions. `CLAUDE.md` only imports it
(`@AGENTS.md`) so every Claude Code session loads it, including those that cannot
read `AGENTS.md` directly; add nothing else there.

## Git policy

- Check `git status` before editing. Inspect pre-existing staged/unstaged diffs
  in dirty trees to distinguish user changes.
- Start every repository-changing task on a new task branch before any edit,
  including docs/one-line changes. Never edit on `main`; read-only analysis,
  reviews and checks need no branch. Exception: reuse a non-`main` branch the
  user already selected/created for this task.
- Branch from `main` unless the user names another base; verify base and merge
  base. With pre-existing changes, branch in place (for example
  `git switch -c <branch>`) without stashing, resetting or disturbing them.
- Names: `feature/<description>` for functionality, `fix/<description>` for defects,
  `chore/<description>` for maintenance/tooling/CI/docs; use concise lowercase
  English with hyphens. Mixed tasks follow their primary purpose; honor user names.
- Never switch branches if that would overwrite, move or disturb user changes.
  Preserve unrelated user-owned changes; never use `git reset --hard` or similar
  destructive commands.
- Exclude IDE metadata, temporary/generated files and unrelated benchmark
  artifacts unless explicitly in scope.
- Before committing new/generated evidence (benchmarks, logs, reports, generated
  files, diagnostics), run:

  ```shell
  python3 scripts/publication/check_public_data.py
  python3 scripts/publication/check_secrets.py
  python3 scripts/publication/check_secrets.py --tree
  ```

  The first secret scan covers full Git history; `--tree` instead covers tracked
  plus non-ignored untracked files, including uncommitted changes. Ignored output
  and nested worktrees such as `.claude/worktrees/` are outside the candidate.
  One scan cannot cover both. Findings show path/rule/line without matched values.
  Gitleaks is required (CI: 8.30.1); put it on `PATH` or use `--gitleaks <path>`.
  `scripts/publication/install-gitleaks` is Linux x64 CI-only. Report secret scans
  as not run if Gitleaks is unavailable.
- Do not commit/push unless explicitly asked. A non-`--dry-run` request for
  `scripts/orchestration/task-orchestrator.ts` authorizes that run's standard
  delivery per `docs/development/TASK_ORCHESTRATOR.md`: commit intended changes,
  push the task branch and create/update its PR. It never authorizes force-push,
  merge, close or auto-merge, nor later tasks/runs.
- Never push directly to `main`; merge only through PRs. Do not create, merge or
  close PRs without explicit instruction; never bypass CI failures.

## Review severity definitions

- **Critical**: realistic data loss, security exposure, unrecoverable corruption,
  application-wide failure, fundamentally incorrect rendering or release-blocking
  failure without a reasonable workaround.
- **Major**: incorrect behavior, significant regression, broken platform/fallback,
  concurrency/resource-lifecycle defect with meaningful correctness, stability,
  leak or supported-workflow impact, or substantial performance/UX failure.
  A workaround does not justify acceptance without a fix.
- **Minor**: localized correctness, robustness, coverage, documentation or
  maintainability issue with limited impact that preserves the main workflow.
  Suggestions without concrete impact are not Minor.

No lower severity exists. Exclude nits, informational notes and optional
suggestions from findings; at most mention them as residual observations.
Ignore style preferences unless they harm correctness/maintainability.
