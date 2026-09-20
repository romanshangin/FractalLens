---
name: implement-task
description: Implement a requested FractalUI change with repository-aware discovery, planning, scoped edits, tests, and documentation. Use for feature, fix, refactoring, performance, or roadmap implementation; not for review-only, verification-only, or pull-request delivery requests.
---

# Implement a FractalUI task

Follow all project-wide rules and constraints in `AGENTS.md`.

## Establish the task

1. Check the current branch and working tree before editing. Inspect any existing
   diff and treat pre-existing changes as user-owned.
2. Resolve the requested outcome and boundaries. A direct request takes priority
   over autonomous roadmap selection. For an unqualified "Continue", take the
   next clear unfinished priority from `ROADMAP.md`.
3. Inspect the relevant project sources of truth listed in `AGENTS.md`, the
   existing implementation flow, dependencies, object lifecycle, and executable
   tests before choosing an approach.

## Plan non-trivial work

Before a change that spans components, changes observable behavior, affects
architecture, concurrency, numerical precision, performance, native integration,
or has unclear acceptance criteria, give the user a short plan that states:

- the intended outcome and scope boundary;
- the existing flow and files to inspect or change;
- the important risks and invariants;
- the test and validation strategy;
- independently verifiable steps when they materially help.

Do not add planning ceremony to a trivial, localized, low-risk edit. Revise the
plan when evidence invalidates it instead of continuing with a known-bad
approach.

## Implement

- Make the smallest complete change that addresses the root cause.
- Preserve established abstractions, public APIs, module boundaries, fallbacks,
  and unrelated work.
- Add or update regression tests for changed observable behavior.
- Apply the numerical, concurrency, UI-thread, platform, GPU, and testing
  constraints from `AGENTS.md` wherever relevant.
- Update the required documentation and roadmap evidence without rewriting old
  measurements as results for the new implementation.

## Performance changes

Do not call a change an optimization without measurements.

Before implementation:

- identify the suspected bottleneck;
- select fixed workloads and a control implementation;
- define the correctness gate and retain/reject metrics;
- reuse an established benchmark protocol when one exists.

During measurement:

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

After the experiment, record an explicit `retain`, `reject`, or `inconclusive`
decision. Do not leave a rejected or inconclusive candidate active in production.
Keep the commands, parameters, summary, and necessary raw evidence in the
appropriate validation or results document.

## Finish the implementation phase

Review the resulting diff for scope and accidental artifacts. Then use the
`verify-task` skill to perform the risk-appropriate validation. Do not commit,
push, or create a pull request unless the user explicitly requests delivery.
Report the changed files, executed checks and results, and anything that remains
unverified.
