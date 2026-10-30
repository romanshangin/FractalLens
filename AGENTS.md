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

FractalLens is a modular desktop fractal explorer: Java/JavaFX versions from
`pom.xml`, Maven, JUnit Jupiter, module `com.shangin.fractal`, mandatory CPU and
optional LWJGL/Vulkan GPU rendering, arbitrary-precision Mandelbrot/Julia deep
zoom, native macOS AppKit/Objective-C integration and launcher, progressive
rendering, frame reuse, antialiasing and PNG export.

Keep portable Java, JavaFX and CPU rendering compatible with macOS, Windows and
Linux. `ROADMAP.md` defines packaging/hardware acceptance targets. macOS-specific
AppKit integration must degrade safely elsewhere.

| Source | Owns |
| --- | --- |
| `ROADMAP.md` | Work order, requirements, acceptance criteria |
| `pom.xml` | Dependency/plugin versions, Maven profiles, launch/test settings |
| `docs/platform/MACOS_RUN.md` | macOS launch and application identity |
| `docs/platform/MACOS_UI.md` | Current UI behavior and native/JavaFX boundary |
| Relevant `*_BENCHMARK.md`, `*_VALIDATION.md`, `*_RESULTS.md` | Measurement protocols and evidence |
| Existing tests | Executable behavioral contract |

Resolve source conflicts explicitly: identify stale documentation/tests or an
intentional task change, prefer the contract's owning source, and report conflicts
that affect implementation or verification. Do not duplicate `pom.xml` version
declarations without a specific reason.

A direct user task takes precedence over autonomous roadmap selection; use the
roadmap for that task's requirements, dependencies and acceptance. An unspecified
"continue" follows unfinished priorities at the start of `ROADMAP.md`. Do not
start lower-priority optimization with open dependencies or decision gates.
Task workflows may strengthen, never weaken, these project constraints.

Keep historical benchmark results tied to their original implementation. Record
new experiments separately with environment, inputs, code state and decision.

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

| Area | Responsibility |
| --- | --- |
| `app` | Startup and application integration |
| `scene` | Scene/rendering configuration |
| `formula` | Formulas and fractal presets |
| `math` | Coordinates, viewports, numeric types |
| `render` | CPU/deep-zoom rendering, caches, reuse, progress publication |
| `config` | Iteration-limit policies |
| `controller` | Rendering orchestration/lifecycle |
| `ui` | JavaFX presentation, input, menus, dialogs, result display |
| `coloring` | Palettes and coloring algorithms |
| `export` | Export workflows/rendering |
| `gpu` | Optional Vulkan/LWJGL runtime/backends |
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
system `mvn` is not recorded validation.

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
Do not repeat valid PASS evidence for confidence alone. Review may diagnose a
concrete suspected finding. Verification must independently assess coverage,
skips and remaining acceptance, then run missing checks. Reuse waives no portable,
subsystem, visible or independent-review requirements. Replace stale,
insufficient or suspicious evidence; a required skip is not PASS.

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

JavaFX integration is opt-in:

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

Run native GPU smoke tests separately:

```shell
./mvnw -Pgpu-smoke test
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

### Test design

- Regression tests should fail before the fix and pass after it.
- Test observable behavior unless an internal contract is itself under test.
- Coordinate concurrency deterministically; do not replace flaky checks with
  longer `sleep` when a concrete state can be awaited.
- Use decimal fixtures/math properties for precision regressions.
- Reuse/publication tests must inspect frames/pixels, not callbacks alone.
- Assert UI state after relevant JavaFX queue work.
- Keep native unit/contract tests alongside real-platform validation.

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
