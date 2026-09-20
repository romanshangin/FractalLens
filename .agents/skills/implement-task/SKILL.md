---
name: implement-task
description: Implement a requested FractalUI change with repository-aware discovery, planning, scoped edits, tests, and documentation. Use for feature, fix, refactoring, performance, or roadmap implementation; not for review-only, verification-only, or pull-request delivery requests.
---

# Implement a FractalUI task

Follow all project-wide rules and constraints in `AGENTS.md`.

## Establish the task

1. Check the current branch and working tree before editing. Inspect existing
   tracked modifications, staged changes, and untracked files. Treat all
   pre-existing work as user-owned.
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

Treat an unmeasured performance change as an optimization candidate, not a
confirmed optimization.

Before implementation:

- identify the suspected bottleneck;
- select fixed workloads and a control implementation or baseline code state;
- define the correctness gate and retain/reject metrics;
- reuse an established benchmark protocol when one exists.

Initial bottleneck diagnosis may use lightweight exploratory measurements. Apply
the controlled protocol below before using results for a `retain` or `reject`
decision:

- compare identical scenes, dimensions, iteration limits, antialiasing settings,
  and precision modes;
- record backend, JDK, JavaFX, LWJGL, hardware, power state, inputs, and code
  state;
- use a fresh output directory and separate warm-up from measured repetitions;
- measure first-visible latency, completion time, and publication latency when
  they affect users;
- validate rendered output as well as timing;
- use at least three repetitions for basic reproducibility and enough
  alternating control/candidate pairs and JVM starts for a retain/reject
  decision;
- do not infer a memory leak from RSS, JFR, NMT, or one short run alone;
- do not compare runs collected under materially different thermal or power
  conditions.

Stop tuning once the predefined retain/reject threshold is clearly met or the
agreed experiment budget is exhausted. Do not repeatedly vary unrelated
parameters until a favorable result appears.

After the experiment, record an explicit `retain`, `reject`, or `inconclusive`
decision. Do not leave a rejected or inconclusive candidate active in the final
implementation. Keep the commands, parameters, summary, and necessary raw
evidence in the appropriate validation or results document.

## Finish the implementation phase

Review the resulting diff for scope, accidental formatting churn, debug logging,
temporary benchmark code, generated files, commented-out code, stale TODOs, and
unrelated dependency changes.

Implementation-time checks provide fast feedback and guide the change. Do not
defer basic regression-test development or immediately relevant checks until
verification. Then use the `verify-task` skill for final risk-appropriate
validation of the completed diff.

Do not commit, push, or create a pull request unless the user explicitly requests
delivery. Report:

- what behavior changed;
- files materially changed;
- tests, benchmarks, and validation executed, with their results;
- documentation or evidence updated;
- remaining risks and unverified areas.
