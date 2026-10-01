---
name: review-task
description: Perform a read-only FractalLens code, diff, or change assessment and report actionable findings with evidence and remediation. Use for review, audit, assessment, or re-review requests; not for implementing fixes unless the user explicitly includes implementation.
---

# Review a FractalLens task

Follow all project-wide rules, constraints, testing requirements, Git policy,
and severity definitions in `AGENTS.md`.

## Keep review read-only

Inspect the code, comparison diff, tests, and relevant documentation without
modifying files, applying fixes, creating commits, pushing, or changing pull
requests. Read-only diagnostic commands and tests are allowed when they are
relevant and proportionate.

Prefer the narrowest tests that can validate or falsify a suspected finding.
Run broader suites when the change crosses subsystem boundaries or targeted
tests cannot establish sufficient confidence.

If the user explicitly requests both review and implementation, report the
review findings first, then use the `implement-task` workflow for the authorized
fixes.

## Review method

1. Determine the comparison base from the user's request, repository state, and
   Git metadata. Prefer an explicitly named base. Otherwise use the repository's
   normal integration branch only when it can be established reliably. If the
   base cannot be established, review the observable changes relevant to the
   request and state the comparison limitation instead of guessing. Inspect the
   complete relevant diff. Account for existing working-tree changes when they
   overlap with or affect the reviewed behavior, but do not attribute unrelated
   user-owned changes to the review target.
2. Read the affected implementation flow, tests, and project sources of truth.
3. Constrain the review to the changed behavior and the implementation paths
   necessary to validate it.
4. Look for concrete regressions and material executable-coverage gaps.
   Prioritize:
   1. mathematical errors or loss of decimal precision;
   2. stale-frame publication after cancellation or a scene change;
   3. races and leaks of listeners, executors, or native resources;
   4. blocking the JavaFX Application Thread;
   5. broken CPU fallback or cross-platform startup;
   6. GPU/CPU conformance failures;
   7. resize, pan, zoom, Reset View, or fractal-change artifacts;
   8. changed behavior that is regression-prone or contractually important but
      lacks an executable check, creating a concrete validation gap;
   9. performance regressions, or performance claims lacking a valid control
      measurement when the claim is part of the change's acceptance evidence;
   10. documentation or roadmap claims unsupported by acceptance evidence.
5. Validate suspected findings against the actual behavior or contract before
   reporting them. Identify the evidence that establishes the issue, such as an
   executable failure, violated invariant, incorrect control or data flow, or a
   contradicted documented contract. A possibility alone is not a defect.

## Finding threshold

Report an issue only when all of the following are true:

1. It is introduced by the reviewed change, or exposed by it in a way that
   materially affects the correctness or acceptance of the changed behavior.
2. It has a concrete correctness, reliability, portability, performance, or
   acceptance impact.
3. The claim is supported by code flow, an executable check, a documented
   contract, or another project source of truth.
4. The issue is actionable enough to identify the behavior or invariant that
   must be corrected or protected.

Make the evidentiary status clear: distinguish confirmed defects, strongly
inferred defects supported by concrete code or control-flow evidence, and
validation gaps. Treat missing coverage as a finding only when the changed
behavior is regression-prone or contractually important and the missing
executable check creates a concrete validation gap. Do not report speculative
possibilities, purely stylistic preferences, unrelated pre-existing defects, or
optional improvements as findings.

## Report the review

- Put findings first and order them by the severity definitions in `AGENTS.md`.
- Give each finding the smallest useful file and line reference or references,
  the concrete impact, supporting evidence, and a practical remediation or
  correction direction.
- Keep one root cause per finding. Do not inflate severity because multiple
  symptoms share that cause. Do not report the same underlying defect again as
  a separate test, documentation, or performance finding unless it represents
  an independent root cause.
- If there are no findings, say so explicitly and list residual risks,
  unexecuted tests, or validation gaps.
- Report commands run and distinguish product failures from environment
  failures with evidence.
