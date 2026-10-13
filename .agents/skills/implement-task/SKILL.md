---
name: implement-task
description: >
  Implement a requested FractalLens change with repository-aware discovery,
  planning, scoped edits, developer self-validation, tests, and documentation.
  Use only for implementation or implementation fixes. Do not perform or invoke
  independent review, independent verification, or pull-request delivery.
---

# Implement a FractalLens task

## Role boundary

This skill is the implementation stage of the task workflow.

Its responsibility ends when the requested change is implemented,
developer self-validation is complete, and the implementation handoff
has been reported.

Developer self-validation is part of implementation and is not independent
verification.

Do not invoke or perform:

- `review-task`;
- `verify-task`;
- `deliver-task`;
- independent code review;
- independent post-review verification;
- pull-request delivery.

Do not commit, push, create a pull request, or merge changes as part of this
skill. Delivery is handled separately by `deliver-task`.

After reporting the implementation handoff, stop and wait for the user or
orchestrator to start the next workflow stage.

Follow all project-wide rules and constraints in `AGENTS.md`.

## Establish the task

1. Before making any change, inspect the current branch and working tree as a
   read-only baseline. Inspect existing tracked modifications, staged changes,
   and untracked files. If the current branch is already the dedicated branch
   for the requested task, including one prepared by the user or orchestrator,
   continue using it. Otherwise, create the dedicated task branch according to
   `AGENTS.md` before editing files. Unless the user explicitly specifies
   another base or branch name, create it from `main` using the naming rule in
   `AGENTS.md`. Treat all pre-existing work as
   user-owned and preserve it; do not switch branches if doing so would disturb
   that work. Do not edit files until the task branch is established and its
   base and merge base have been verified. Never use `main` as the task branch.
2. Resolve the requested outcome and boundaries. Distinguish required behavior,
   explicitly requested cleanup, and incidental improvements. Do not expand the
   scope for unrelated cleanup, modernization, formatting, or architectural
   improvements unless they are required to complete the task safely. A direct
   request takes priority over autonomous roadmap selection. For an unqualified
   "Continue", reconcile the next plausible unfinished priorities in
   `ROADMAP.md` with the current implementation and relevant existing validation
   or benchmark evidence, then take the next clear unfinished priority. Do not
   assume roadmap status is current when repository evidence disagrees.
3. Inspect the relevant project sources of truth listed in `AGENTS.md`, the
   existing implementation flow, dependencies, object lifecycle, and executable
   tests before choosing an approach. When a change crosses layers, trace the
   relevant call and data flow from entry point to observable result. Identify
   state ownership, thread boundaries, cancellation, caching or reuse, and
   publication points where applicable.
4. When practical, establish whether the relevant tests pass before editing. If
   a relevant test already fails, record it as a baseline failure and do not
   attribute it to the new change unless evidence shows otherwise.
5. Treat benchmark drivers that initialize JavaFX, including those launched by
   scripts, as graphical runs under the `AGENTS.md` display rule. Before the
   first JavaFX test or benchmark run, use an active desktop session when the
   current sandbox is known to have no display; do not probe that sandbox by
   waiting for JavaFX to fail. If desktop access is unavailable, report the
   check as unverified.

## Plan non-trivial work

For non-trivial work, give the user a short plan before editing. Treat work as
non-trivial when it spans components, materially changes observable behavior,
affects architecture, concurrency, numerical precision, performance, native
integration, or has unclear acceptance criteria. State:

- the intended outcome and scope boundary;
- the existing flow and likely files to inspect or change;
- the important risks and invariants;
- the test and validation strategy;
- independently verifiable steps when they materially help.

Do not add planning ceremony to a trivial, localized, low-risk edit. Revise the
plan when evidence invalidates it instead of continuing with a known-bad
approach.

## Implement

- Make the smallest complete change that achieves the requested outcome and,
  for defects, addresses the root cause.
- Preserve established abstractions, public APIs, module boundaries, fallbacks,
  and unrelated work unless the task explicitly or necessarily requires changing
  them.
- Prefer extending an existing abstraction over introducing a new one. Add a new
  abstraction only when it resolves a real dependency or lifecycle problem, or
  when multiple concrete uses require it.
- Add or update regression tests for changed observable behavior.
- Do not replace required implementation or test coverage with TODOs, skipped or
  disabled tests, placeholder behavior, commented-out code, or temporary
  fallbacks unless the user explicitly accepts the deferred work.
- Apply the numerical, concurrency, UI-thread, platform, GPU, and testing
  constraints from `AGENTS.md` wherever relevant.
- Update documentation, roadmap status, benchmark evidence, or architectural
  notes when the change materially affects them or project rules require it.
  Do not rewrite old measurements as results for the new implementation.

## Handle unexpected findings

If repository evidence contradicts the requested assumption or planned
approach:

- preserve the requested outcome where possible;
- stop expanding the current approach and revise the plan based on the observed
  behavior;
- do not silently work around an unrelated defect;
- fix an adjacent defect only when it blocks the requested task and the fix is
  narrowly scoped;
- report any blocking pre-existing issue that remains unresolved.

## Performance changes

For performance work, read [the benchmark protocol](references/performance.md)
and follow the established benchmark and validation documents for the affected
subsystem.

Do not treat an unmeasured candidate as an optimization. Preserve correctness
gates and compare against a valid control. Record `retain`, `reject`, or
`inconclusive` before handoff.

## Finish the implementation phase

Review the resulting diff for scope, accidental formatting churn, debug logging,
temporary benchmark code, generated files, commented-out code, stale TODOs, and
unrelated dependency changes.

Implementation-time checks are developer self-validation. Run the tests and
checks required to establish that the implementation is ready for independent
review, including the portable suite required by `AGENTS.md` where applicable
and relevant subsystem checks. Do not defer basic regression-test development
or immediately relevant checks until independent verification.

Do not invoke `verify-task`. Independent verification is a separate workflow
stage started explicitly by the user or orchestrator after independent review.

If required developer self-validation fails, investigate and fix failures that
belong to the requested task before handoff. Do not report the implementation
as ready while known task-related failures remain. If a concrete blocker
prevents completion or required validation, report `BLOCKED` with the evidence.

## Report the implementation handoff

Start the report with exactly one implementation status:

- `READY_FOR_REVIEW` — the requested implementation is complete and required
  developer self-validation passed;
- `BLOCKED` — implementation or required developer self-validation cannot be
  completed because of a concrete repository, environment, dependency,
  requirement, or platform blocker.

Then report:

- behavior changed;
- files materially changed;
- tests and developer self-validation executed, with results;
- documentation or evidence updated;
- known risks and areas not validated by the developer;
- blocking issue, when status is `BLOCKED`.

Do not report `READY_FOR_REVIEW` while a known task-related required check is
failing, skipped, unrun, or could not be verified.
