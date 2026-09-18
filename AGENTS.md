# FractalUI project instructions

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
- Do not call work complete when required tests were not run, failed, or when
  relevant visible behavior was not checked.
- Distinguish product defects from environment failures and provide concrete
  evidence for that distinction.
- Short requests such as "Continue" mean proceeding to the next clear item in
  the accepted plan or `ROADMAP.md`, not repeating the plan.

## Project overview

FractalUI is a modular desktop application for exploring fractals.

The current stack and major capabilities are:

- Java 25;
- JavaFX 26;
- Maven;
- JUnit Jupiter;
- the Java module `com.shangin.fractal`;
- a mandatory CPU rendering path;
- an optional LWJGL/Vulkan GPU path;
- arbitrary-precision Mandelbrot and Julia deep zoom;
- native macOS integration through AppKit, Objective-C, and a small launcher;
- progressive rendering, frame reuse, antialiasing, and PNG export.

Treat `pom.xml` as the source of truth for dependency and plugin versions. Do
not duplicate version declarations in new files without a specific reason.

## Sources of truth

Before changing code, inspect the sources relevant to the task:

- `ROADMAP.md` for work order, requirements, and acceptance criteria;
- `pom.xml` for versions, Maven profiles, launch settings, and test settings;
- `MACOS_RUN.md` for macOS launch and application-identity behavior;
- `MACOS_UI.md` for current UI behavior and the native/JavaFX boundary;
- relevant `*_BENCHMARK.md`, `*_VALIDATION.md`, and `*_RESULTS.md` files for
  established measurement protocols and evidence;
- existing tests for the executable behavioral contract.

A direct user request defines the current task and takes precedence over
autonomously selecting the next item from `ROADMAP.md`. Within the scope of that
request, use the roadmap as the source of requirements, dependencies, and
acceptance criteria. When the user asks to continue without naming a task,
follow the unfinished priorities at the beginning of `ROADMAP.md`. Do not
autonomously start a lower-priority optimization while its dependencies or
decision gates remain open.

Do not rewrite historical benchmark results as though they describe a new
implementation. Record new experiments separately and include the environment,
inputs, code state, and decision.

## Repository structure

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

## General development rules

- Inspect the existing flow, dependencies, and object lifecycle before editing.
- Prefer the smallest correct change.
- Do not refactor unrelated code.
- Keep public APIs stable unless the task explicitly requires a change.
- Reuse existing abstractions instead of implementing parallel logic.
- Do not add a dependency when the JDK or an existing dependency can reasonably
  solve the problem.
- Any new dependency must be justified, compatible with the modular build, and
  checked on supported platforms.
- Prefer readable code over clever code.
- Keep methods and classes focused.
- Do not leave temporary diagnostics, experimental switches, or benchmark code
  in the production path without an explicit purpose.
- Do not suppress failures, warnings, or failing tests.
- Add or update tests whenever observable behavior changes.
- Fix root causes rather than only masking symptoms.
- Preserve unrelated and pre-existing working-tree changes.

## Planning

Create and communicate a short implementation plan before making a non-trivial
change. A change is non-trivial when it spans multiple components, changes
observable behavior, affects architecture, concurrency, numerical precision,
performance, native integration, or has unclear acceptance criteria.

The plan must:

- state the intended outcome and the boundaries of the change;
- identify the relevant existing flow and files to inspect;
- call out important risks and invariants;
- define the test and validation strategy before implementation;
- break the work into independently verifiable steps when appropriate.

Keep the plan current when evidence changes the approach. Do not continue with a
known-invalid plan merely because implementation has started. Planning must not
become ceremony for a trivial, localized, low-risk edit such as a spelling or
documentation-only correction.

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

## Rendering and concurrency

Rendering is asynchronous and cancellable. Every change must account for races
between old and new scenes.

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
- Close `AutoCloseable` resources, executors, native handles, and listeners
  explicitly.
- Verify idempotent close and cancellation during partially completed work.
- A cancelled coordinator `Future` is not fully drained until its registered
  worker tasks have actually stopped.
- Frame-reuse changes must cover pan in every direction, no overlap, zero shift,
  resize, antialiasing, and iteration-limit changes.
- Evaluate publication-path optimizations by gesture-to-visible-update latency,
  not throughput alone.

## JavaFX and visible behavior

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

## macOS integration

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
  `FractalUI`; a process name or `localizedName` is insufficient.
- Preserve normal development launch through `mvn javafx:run`.

## GPU rules

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

## Performance work

Do not call a change an optimization without measurements.

Before implementation:

- identify the suspected bottleneck;
- select fixed workloads and a control implementation;
- define the correctness gate;
- define acceptance or rejection metrics;
- reuse an established benchmark protocol when one exists.

During measurement:

- compare identical scenes, dimensions, iteration limits, AA settings, and
  precision modes;
- record the CPU/GPU backend, JDK, JavaFX, LWJGL, hardware, and power state;
- use a fresh output directory;
- separate warm-up from measured repetitions;
- measure first-visible latency, completion time, and publication latency when
  they affect the user;
- validate rendered output, not only timing;
- use at least three repetitions for basic reproducibility;
- use enough alternating control/candidate pairs and multiple JVM starts for a
  retain/reject decision;
- do not infer a memory leak from RSS, JFR, NMT, or one short run alone;
- do not compare runs collected under materially different thermal or power
  conditions.

After an experiment:

- record an explicit `retain`, `reject`, or `inconclusive` decision;
- do not leave a rejected or inconclusive optimization active in production;
- update the relevant results or validation document;
- retain the commands, parameters, summary, and necessary raw evidence.

## Testing

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

### JavaFX tests

JavaFX integration tests are opt-in:

```shell
mvn \
  -Dfractal.fx.tests=true \
  -Djavafx.cachedir=/tmp/fractalui-javafx-<task> \
  -DreuseForks=false \
  -Dtest=RelevantFxTest \
  test
```

- Run JavaFX tests in an active graphical macOS session.
- Use a separate JavaFX cache directory for independent runs.
- Use `-DreuseForks=false` when suites that call `Platform.exit()` run together.
- `No toolkit found`, Prism failures, CVDisplayLink failures,
  `Screen.getMainScreen` failures, and cache `.lock` failures can be environment
  problems. Confirm this by rerunning in a valid desktop session.
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

### Final checks

Before handing off a change, run:

```shell
git diff --check
git status --short
```

Check the diff for accidental artifacts, generated output, IDE files, and
unrelated changes.

## Test design

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

## Documentation

Update documentation when a change affects:

- user-visible behavior;
- platform support;
- launch or test commands;
- a benchmark protocol;
- a roadmap acceptance criterion;
- a fallback path;
- a precision or performance decision.

Mark a roadmap item complete only after its acceptance criteria pass. Do not
present a proposal or expected speedup as a completed feature.

## Code review

When the user asks for a review, audit, or assessment, enter Review mode. Review
mode is read-only: inspect the code, diff, tests, and relevant documentation, but
do not modify files, apply fixes, create commits, or push changes unless the user
explicitly asks for implementation after or as part of the review.

You may and should recommend a concrete remediation for each finding.

Report findings first, ordered by severity, with precise file and line evidence.
Focus each finding on an actionable defect or risk and explain its impact. If no
findings are identified, say so explicitly and list residual risks or validation
gaps. Do not treat stylistic preferences as findings unless they materially harm
correctness or maintainability.

Use these severity levels:

- **Critical**: causes or can realistically cause data loss, security exposure,
  unrecoverable corruption, application-wide failure, fundamentally incorrect
  rendering, or a release-blocking failure with no reasonable workaround.
- **Major**: causes incorrect behavior, a significant regression, broken
  platform or fallback behavior, a concurrency or resource-lifecycle defect, or
  a substantial performance/UX failure in a supported workflow. A workaround
  may exist, but the change should not be accepted without addressing the issue.
- **Minor**: a localized correctness, robustness, test-coverage, documentation,
  or maintainability issue with limited impact that does not invalidate the main
  workflow. Suggestions with no concrete impact are not Minor findings.

During review, prioritize correctness and regressions:

1. Mathematical errors or loss of decimal precision.
2. Publication of stale frames after cancellation or a scene change.
3. Races and leaks of listeners, executors, or native resources.
4. Blocking the JavaFX Application Thread.
5. Broken CPU fallback or cross-platform startup.
6. GPU/CPU conformance failures.
7. Artifacts during resize, pan, zoom, Reset View, or fractal changes.
8. Tests that do not cover the changed behavior.
9. Performance changes without a valid control measurement.
10. Documentation or roadmap claims unsupported by completed acceptance checks.

Ignore purely stylistic preferences unless they harm correctness or
maintainability.

## Git and delivery

- Check `git status` before editing.
- If the working tree is already dirty, inspect the existing diff before editing
  so that pre-existing user changes can be distinguished from changes made for
  the current task.
- Treat existing changes as user-owned.
- Do not delete or overwrite unrelated changes.
- Do not use destructive commands such as `git reset --hard`.
- Do not include IDE metadata, temporary files, generated output, or unrelated
  benchmark artifacts unless they are explicitly part of the task.
- Do not create commits or push changes unless the user explicitly asks.
- All implementation changes must be pushed to a task branch using the
  `codex/` prefix.
- When the user explicitly asks to publish validated changes to `main`, commit,
  fast-forward merge, push, and verify local/remote `main` parity.
- In the final report, list changed files, executed checks and their results,
  and anything that remains unverified.
- Never push directly to `main` unless the user explicitly requests it.
- Merge into `main` only through a pull request.
- Do not create, merge, or close a pull request unless explicitly asked.
- Do not bypass CI failures.