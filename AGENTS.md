# FractalLens project instructions

## Communication and repository language

- Speak to the user in Russian and use the informal form of address.
- Keep all repository content in English. This includes production-code comments,
  Javadocs, tests, test names, test comments, assertion messages, UI text, error
  messages, log messages, documentation, benchmark notes, commit messages, and
  newly added identifiers.
- Do not add Russian text to repository files unless the task explicitly requires
  Russian localization.
- State conclusions concretely: explain what changed, why it changed, how it was
  verified, and what remains unverified.
- Do not call work fully verified when required tests were not run, failed, or
  relevant visible behavior was not checked. Implementation may be complete
  while verification remains incomplete; state that distinction explicitly.
- Distinguish product defects from environment failures and provide concrete
  evidence for that distinction.
- Short requests such as "Continue" mean proceeding to the next clear item in
  the accepted plan or `ROADMAP.md`, not repeating the plan.

## Project overview and sources of truth

FractalLens is a modular desktop application for exploring fractals. The current
stack and major capabilities are:

- Java and JavaFX versions defined by `pom.xml`;
- Maven;
- JUnit Jupiter;
- the Java module `com.shangin.fractal`;
- a mandatory CPU rendering path;
- an optional LWJGL/Vulkan GPU path;
- arbitrary-precision Mandelbrot and Julia deep zoom;
- native macOS integration through AppKit, Objective-C, and a small launcher;
- progressive rendering, frame reuse, antialiasing, and PNG export.

Portable Java, JavaFX, and CPU-rendering functionality must remain compatible
with macOS, Windows, and Linux. Packaging and hardware-backed acceptance
targets are defined by `ROADMAP.md`. Native AppKit integration is macOS-specific
and must degrade safely elsewhere.

The project sources of truth are:

- `ROADMAP.md` for work order, requirements, and acceptance criteria;
- `pom.xml` for versions, Maven profiles, launch settings, and test settings;
- `MACOS_RUN.md` for macOS launch and application-identity behavior;
- `MACOS_UI.md` for current UI behavior and the native/JavaFX boundary;
- relevant `*_BENCHMARK.md`, `*_VALIDATION.md`, and `*_RESULTS.md` files for
  established measurement protocols and evidence;
- existing tests for the executable behavioral contract.

If sources of truth disagree, do not silently choose one. Determine whether the
discrepancy is stale documentation, a stale test, or an intentional change in
the current task. Prefer the source that explicitly owns the disputed contract
and report the conflict when it affects the implementation or verification
result.

Treat `pom.xml` as the source of truth for dependency and plugin versions. Do
not duplicate version declarations in new files without a specific reason.

A direct user request defines the current task and takes precedence over
autonomously selecting the next item from `ROADMAP.md`. Within the scope of that
request, use the roadmap as the source of requirements, dependencies, and
acceptance criteria. When the user asks to continue without naming a task,
follow the unfinished priorities at the beginning of `ROADMAP.md`. Do not
autonomously start a lower-priority optimization while its dependencies or
decision gates remain open.

Task-specific workflows may add stricter steps, but they must not weaken or
override the project-wide constraints in this file.

Do not rewrite historical benchmark results as though they describe a new
implementation. Record new experiments separately and include the environment,
inputs, code state, and decision.

## Project rules

- Inspect the existing flow, dependencies, and object lifecycle before editing.
- Prefer the smallest correct change.
- Do not refactor unrelated code.
- Keep public APIs stable unless the task explicitly requires a change.
- Reuse existing abstractions instead of implementing parallel logic.
- Do not add a dependency when the JDK or an existing dependency can reasonably
  solve the problem.
- Any new dependency must be justified and compatible with the modular build.
  Assess its compatibility with all supported platforms, run available
  platform-specific checks, and explicitly report any platform that was not
  actually verified.
- Prefer readable code over clever code.
- Keep methods and classes focused.
- Do not leave temporary diagnostics, experimental switches, or benchmark code
  in the production path without an explicit purpose.
- Do not hide failures, failing tests, or unexpected warnings merely to make
  validation pass. Use targeted warning suppression only when the warning is
  understood, unavoidable, and the reason is documented.
- Add or update tests whenever observable behavior changes.
- Fix root causes rather than only masking symptoms.
- Preserve unrelated and pre-existing working-tree changes.
- Update documentation when a change affects user-visible behavior, platform
  support, launch or test commands, a benchmark protocol, a roadmap acceptance
  criterion, a fallback path, or a precision or performance decision.
- Mark a roadmap item complete only after its acceptance criteria pass. Do not
  present a proposal or expected speedup as a completed feature.

### Privacy and published data

- Before committing new or generated evidence, run `python3 scripts/check_public_data.py`
  and a full secret scan via `scripts/check_secrets.py`; include `--tree` for
  uncommitted files. Keep `scripts/public_benchmark_files.json` and the benchmark
  index consistent when explicitly selecting new evidence. Do not attach
  personal self-hosted runners or expose repository secrets to pull requests.

- The public benchmark selection is documented in `benchmarks/README.md` and
  `PUBLICATION.md`. Keep raw logs, diagnostic dumps and unselected working
  evidence in a private archive. Publish only reviewed summaries and explicitly
  selected sanitized data. Preserve original measurement identities and label
  redacted derivatives; do not imply that privately archived inputs are
  available in a public checkout or that historical checks were rerun.

- Never commit active credentials, tokens, private keys, recovery codes, or
  other secrets. Before adding benchmark results, logs, test reports, generated
  files, or diagnostic output, inspect them for secrets and personal machine
  data. Do not rely on GitHub masking to make a secret safe to print.
- A surname and contact email address explicitly selected by the user for
  public use may appear in author metadata and project content. The explicit
  public identity allowlist is:

  - Surname: `Shangin`, including its use in package and module identifiers
    such as `com.shangin` and `com.shangin.fractal`.
  - Contact email address: `shangin.ro@gmail.com`.

  Do not rewrite history solely to remove these approved public details.
  This exception does not authorize publishing other personal or
  machine-specific data, even when a prohibited field contains an allowlisted
  value. Any expansion of this allowlist requires explicit user approval.
- Apart from the explicitly approved contact email address above, do not
  publish a user's login name, home directory, email address, computer
  or host name, machine serial number, hardware UUID, MAC address, private or
  public IP address, or machine-specific temporary/workspace path in repository
  files, benchmark evidence, Actions logs, or uploaded artifacts. Use
  repository-relative paths or stable placeholders such as `${USER_HOME}`,
  `${PROJECT_DIR}`, and `${TEMP_DIR}`.
- Benchmark provenance may include only machine details needed to reproduce or
  interpret a result, such as OS/version, architecture, CPU/GPU model, core and
  memory counts, driver/runtime versions, and power or thermal state. Filter
  system-report commands to those fields; never upload unfiltered hardware or
  software profiler output.
- If a live secret is found in a working tree or any published location, revoke
  or rotate it with its issuer first. Then remove it from the current files and
  arrange cleanup of every affected published Git ref, Actions log/artifact,
  release, cache, and other retained copy. Deleting a file in a later commit
  does not remove it from history or invalidate a credential. Verify the
  cleanup at each affected destination; do not claim it is complete from a
  current-tree scan alone.
- Before publishing a change that includes historical/generated evidence,
  scan all included files and review the relevant published Git history and
  retained Actions logs/artifacts. Do not rewrite shared Git history or delete
  published logs/artifacts without explicit owner authorization.

## Architecture constraints

Preserve the existing package boundaries:

- `app` owns application startup and application-level integration;
- `scene` owns scene and rendering configuration;
- `formula` owns mathematical formulas and fractal presets;
- `math` owns coordinates, viewports, and numeric value types;
- `render` owns CPU rendering, deep zoom, caches, frame reuse, and progress
  publication;
- `controller` owns rendering orchestration and lifecycle;
- `ui` owns JavaFX presentation, input, menus, dialogs, and result display;
- `coloring` owns palettes and coloring algorithms;
- `export` owns export workflows and export rendering;
- `gpu` owns the optional Vulkan/LWJGL runtime and GPU backends;
- `src/main/macos` contains native macOS sources and metadata;
- `scripts` contains reproducible build, diagnostic, and benchmark tools.

Do not move responsibilities between layers without a clear reason. UI code
must not implement fractal mathematics, formulas must not depend on JavaFX, and
GPU code must not become a prerequisite for CPU execution.

### Rendering and concurrency

Rendering is asynchronous and cancellable. Changes that affect rendering,
render orchestration, scene transitions, publication, cancellation, or
lifecycle must account for races between old and new scenes.

- Never publish a tile, progress event, or completed frame after its render
  generation has been cancelled or superseded.
- Scene changes, resize, pan, zoom, and reset must not receive pixels from stale
  work.
- Preserve atomic publication where partial publication would produce seams,
  bands, or a mixture of different frames.
- Do not block the JavaFX Application Thread with calculation, worker waits, or
  heavy I/O.
- Modify the JavaFX scene graph only on the JavaFX Application Thread.
- Native callbacks arriving outside the JavaFX Application Thread must dispatch
  application actions through `Platform.runLater`.
- Close `AutoCloseable` resources, executors, and native handles explicitly.
  Remove or unregister listeners when their owning lifecycle ends.
- Verify idempotent close and cancellation during partially completed work.
- A cancelled coordinator `Future` is not fully drained until its registered
  worker tasks have actually stopped.
- Frame-reuse changes must cover pan in every direction, no overlap, zero shift,
  resize, antialiasing, and iteration-limit changes.
- Evaluate publication-path optimizations by gesture-to-visible-update latency,
  not throughput alone.

### macOS integration

Prefer native system behavior on macOS while retaining a working JavaFX
fallback.

- Do not draw imitations of standard window buttons, title bars, or system
  menus.
- Keep the standard JavaFX window decoration so AppKit provides the native
  frame and controls.
- Native bridges must not break Windows, Linux, or startup when a native library
  cannot be loaded.
- Retain the JavaFX context-menu fallback when the AppKit bridge is unavailable.
- Calculate popup coordinates relative to the owning content view in logical
  points.
- Do not manually multiply coordinates by the Retina scale or convert through
  the primary display height.
- Account for the AppKit view's flipped coordinate system.
- A native menu session must cancel correctly on close, replacement, resize,
  window movement, focus loss, and scene detachment.
- JNI and Objective-C callbacks must not mutate JavaFX UI directly.
- Launcher changes must preserve JVM arguments, application arguments,
  `@argfiles`, `JDK_JAVA_OPTIONS`, exit status, and paths containing spaces.
- Do not modify the installed JDK to implement the launcher.
- The acceptance check for application identity is the visible Dock tooltip
  `FractalLens`; a process name or `localizedName` is insufficient.
- Preserve normal development launch through `mvn javafx:run`.

### GPU rules

The GPU renderer is optional. CPU rendering remains the default and mandatory
fallback.

- Do not enable a GPU path by default based only on a local speedup.
- Every GPU change requires a CPU reference and a conformance check.
- Do not weaken precision gates to obtain a better benchmark result.
- Do not hide mismatches behind a tolerance when the contract requires exact
  output.
- If the required device or capability is unavailable, report that condition
  correctly and fall back to CPU.
- Do not generalize Apple Silicon results to Intel Mac, AMD Mac, or Windows.
- Native GPU tests are opt-in and do not replace the portable CPU suite.
- Promote a GPU path only after the correctness, latency, throughput, memory,
  and platform gates in `ROADMAP.md` pass.

## Numerical correctness and deep zoom

Numerical correctness takes precedence over a local performance improvement.

- Preserve supplied decimal viewport coordinates. Do not convert them to
  `double` prematurely.
- Never reconstruct exact coordinates from values that have already been
  rounded.
- Validate coordinates, scale, render dimensions, and iteration limits before
  starting expensive work.
- Preserve center, pixel spacing, dimension parity, and coordinate mapping when
  changing the render grid.
- Do not apply Mandelbrot-specific assumptions to Julia, Burning Ship, Tricorn,
  or Multibrot without separate proof.
- For Julia, remember that `z0` is the pixel and `c` is fixed.
- Julia perturbation uses `delta z0`; do not mechanically copy Mandelbrot's
  `delta c` formulation.
- Do not use Mandelbrot cardioid or interior shortcuts for Julia.
- Do not select the Julia deep-zoom backend from viewport spacing alone. Loss of
  precision can occur along an orbit near the critical point.
- Exact fallback must remain correct and bounded. An optimization must never
  silently publish an approximately incorrect frame.
- When changing formulas or precision selection, validate individual samples
  and complete frames at known coordinates.
- A small test frame does not prove that deep zoom is usable at a realistic
  window size. Measure realistic frames and time to first visible progress.

## Testing rules

Choose validation proportional to the risk of the change.

### Targeted tests

Run the nearest tests first during development to get fast feedback while
iterating:

```shell
mvn -Dtest=RelevantTest test
```

List multiple related test classes with a comma when necessary. Passing targeted
tests is an intermediate development check; it does not replace the required
pre-handoff suite.

### Portable test suite

Before handing off a production-code change, run the full portable suite:

```shell
mvn test
```

After build, dependency, or module-setting changes, or when stale output is
possible, use:

```shell
mvn clean test
```

Do not substitute targeted tests for the full portable suite before handoff.
Also run any relevant opt-in JavaFX, GPU, native, or benchmark validation called
for by the changed subsystem. Documentation-only changes do not require the
application test suite. If the full suite cannot run because of an environment
failure or an explicit user constraint, report that limitation and the exact
checks that did run; do not imply full validation.

### JavaFX and visible behavior

Automated model tests are not sufficient evidence for visible UI changes.

- Add or update an appropriate JavaFX test for changed UI behavior.
- Also inspect the running application when behavior depends on layout, system
  menus, the window manager, appearance, focus, or animation.
- Check light and dark appearance when colors or native materials change.
- For dialogs, check focus, Enter, Escape, Cancel, Apply, and validation errors.
- Preserve invalid drafts when the user must be able to correct them.
- Fractal changes and Reset View must not expose stale or partially mismatched
  pixels.
- Remove the loading screen only after full `RenderStatus.COMPLETE`, or finish
  it correctly on `FAILED`.
- Tests for menus that depend on the current viewport must wait for the initial
  render before firing menu actions.
- Explicitly list any visual behavior that could not be checked.

JavaFX integration tests are opt-in:

```shell
mvn -Dfractal.fx.tests=true "-Djavafx.cachedir=TASK_SPECIFIC_TEMP_DIRECTORY" -DreuseForks=false -Dtest=RelevantFxTest test
```

- Run portable JavaFX tests in an active graphical session.
- Run macOS-specific JavaFX or native-integration tests in an active graphical
  macOS session.
- If the current sandbox is known to have no display, do not attempt JavaFX
  integration tests there; run them directly in the active desktop session.
- Replace `TASK_SPECIFIC_TEMP_DIRECTORY` with a task-specific directory under
  the platform's temporary directory.
- Use `-DreuseForks=false` when suites that call `Platform.exit()` run together.
- `No toolkit found`, Prism failures, CVDisplayLink failures,
  `Screen.getMainScreen` failures, and cache `.lock` failures can be environment
  problems. If an unexpected sandbox run reaches `Screen.getMainScreen` with an
  empty screen list, rerun the unchanged test in a desktop session and record
  the sandbox failure as an environment limit.
- Do not report a skipped test as passed.

### GPU tests

Run native GPU smoke tests separately:

```shell
mvn -Pgpu-smoke test
```

The result applies only to the device, driver, and platform actually used.

### macOS launcher tests

After launcher, bundle-metadata, or launch-option changes, run:

```shell
python3 scripts/test_macos_launcher.py
```

Also launch the application:

```shell
mvn javafx:run
```

Manually verify Dock identity and any affected system behavior.

### Test design

- A regression test should fail for the original defect and pass after the fix.
- Test observable behavior rather than implementation details unless the
  internal contract is itself the subject of the test.
- Use deterministic coordination points for concurrency tests instead of
  unreliable delays.
- Do not fix a flaky test by increasing `sleep` when a concrete state can be
  awaited.
- Use decimal fixtures and mathematical properties for precision regressions.
- For frame reuse and publication, validate frame or pixel contents rather than
  callbacks alone.
- For UI tests, assert state after the relevant JavaFX event queue work has run.
- Keep unit or contract tests for native integration in addition to real
  platform validation.

## Git policy

- Check `git status` before editing.
- If the working tree is already dirty, inspect the existing diff before editing
  so that pre-existing user changes can be distinguished from changes made for
  the current task.
- When the task requires creating a new branch, create it from `main` unless the
  user explicitly specifies another base. Verify the selected base and the new
  branch's merge base.
- When the agent creates a task branch, use the `codex/` prefix.
- Do not switch branches when doing so would overwrite, move, or otherwise
  disturb pre-existing user changes.
- When the user has already selected or created the task branch, preserve and
  use that branch unless it is `main`.
- Treat existing changes as user-owned.
- Do not delete or overwrite unrelated changes.
- Do not use destructive commands such as `git reset --hard`.
- Do not include IDE metadata, temporary files, generated output, or unrelated
  benchmark artifacts unless they are explicitly part of the task.
- Do not create commits or push changes unless the user explicitly asks.
- Never push directly to `main`.
- Merge into `main` only through a pull request.
- Do not create, merge, or close a pull request unless explicitly asked.
- Do not bypass CI failures.

## Review severity definitions

- **Critical**: causes or can realistically cause data loss, security exposure,
  unrecoverable corruption, application-wide failure, fundamentally incorrect
  rendering, or a release-blocking failure with no reasonable workaround.
- **Major**: causes incorrect behavior, a significant regression, broken
  platform or fallback behavior, a concurrency or resource-lifecycle defect
  with meaningful correctness, stability, leak, or supported-workflow impact,
  or a substantial performance/UX failure. A workaround may exist, but the
  change should not be accepted without addressing the issue.
- **Minor**: a localized correctness, robustness, test-coverage, documentation,
  or maintainability issue with limited impact that does not invalidate the main
  workflow. Suggestions with no concrete impact are not Minor findings.

Ignore purely stylistic preferences unless they harm correctness or
maintainability.
