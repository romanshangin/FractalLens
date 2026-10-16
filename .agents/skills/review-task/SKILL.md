---
name: review-task
description: >
  Perform an independent read-only review of a completed FractalLens
  implementation or re-review developer fixes. Report actionable findings
  supported by evidence. Use only for the independent review stage.
  Do not implement fixes, perform independent verification, or deliver changes.
---

# Review a FractalLens task

## Role boundary

This skill is the independent read-only review stage of the task workflow.

Its responsibility is to assess a completed implementation or developer fixes,
identify actionable findings supported by evidence, and report the review result.

Do not invoke or perform:

- `implement-task`;
- `verify-task`;
- `deliver-task`;
- implementation or fixes;
- independent post-review verification;
- commit, push, pull-request creation, or merge operations.

Read-only repository inspection and diagnostic commands are allowed when needed
to establish or falsify a review finding.

Review-time testing is diagnostic evidence, not independent acceptance
verification.

After reporting the review result, stop and wait for the user or orchestrator
to start the next workflow stage.

Follow all project-wide rules, constraints, testing requirements, Git policy,
and severity definitions in `AGENTS.md`.

## Keep review read-only

Inspect the code, comparison diff, tests, and relevant documentation without
modifying files, applying fixes, creating commits, pushing, or changing pull
requests. Read-only diagnostic commands and tests are allowed when they are
relevant and proportionate.

Inspect supplied automated execution evidence and its referenced logs when
needed to assess coverage or skips. Do not rerun a sufficient current PASS
merely for general confidence. A prose developer handoff alone is not reusable
execution evidence. Keep review read-only; do not write to the check registry.
Diagnostics for a concrete suspected finding remain allowed, including a fresh
or extended check when existing evidence cannot confirm or falsify the finding.
Report diagnostics separately from reused evidence.

Prefer the narrowest diagnostic test or command that can confirm or falsify a
suspected finding.

Run broader tests only when they are necessary to establish the validity or
impact of a concrete suspected finding.

Do not run broad acceptance validation merely to prove that the completed task
works; that belongs to `verify-task`.

If the user requests review and fixes together, complete and report the
independent review only. Do not implement the findings within this skill.
Implementation must be started separately with `implement-task` so that the
review remains independent.

## Review method

1. Determine the comparison base from the user's request, repository state, and
   Git metadata. In orchestrated runs, use the supplied base. Otherwise prefer
   an explicitly named base, then `main` (the task-branch base required by
   `AGENTS.md`) when the merge base with it can be established. If the
   base cannot be established, review the observable changes relevant to the
   request and state the comparison limitation instead of guessing. Inspect the
   complete relevant diff. Account for existing working-tree changes when they
   overlap with or affect the reviewed behavior, but do not attribute unrelated
   user-owned changes to the review target.
2. Read the affected implementation flow, tests, and project sources of truth.
3. Constrain the review to the changed behavior and the implementation paths
   necessary to validate it.
   For re-review, inspect the complete current diff and explicitly check closure
   of each finding or verification failure supplied as historical evidence.
   Do not treat an earlier `CLEAN` result as acceptance of developer fixes.
4. Look for concrete regressions and material executable-coverage gaps.
   Prioritize:
   1. secrets, credentials, or personal and machine-specific data prohibited by
      the `AGENTS.md` privacy rules in added files, evidence, logs, or
      generated output, and benchmark files added outside the explicit public
      selection; the read-only checks
      `python3 scripts/publication/check_public_data.py` and
      `python3 scripts/publication/check_secrets.py --tree` may serve as
      diagnostics;
   2. mathematical errors or loss of decimal precision;
   3. stale-frame publication after cancellation or a scene change;
   4. races and leaks of listeners, executors, or native resources;
   5. blocking the JavaFX Application Thread;
   6. broken CPU fallback or cross-platform startup;
   7. GPU/CPU conformance failures;
   8. resize, pan, zoom, Reset View, or fractal-change artifacts;
   9. changed behavior that is regression-prone or contractually important but
      lacks an executable check, creating a concrete validation gap;
   10. performance regressions, or performance claims lacking a valid control
      measurement when the claim is part of the change's acceptance evidence;
   11. documentation or roadmap claims unsupported by acceptance evidence;
   12. accidental generated output, IDE metadata, temporary files, or
       unrelated changes included in the candidate.
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

## Candidate provenance

Record the intended base revision, comparison merge base, and a content
identifier covering the complete candidate, including intended uncommitted and
untracked files. In orchestrated runs, use the identity supplied and checked by
the orchestrator; do not compute an alternative fingerprint or emit model-owned
candidate fields in the compact handoff. Standalone reviews may use a tree ID
or content fingerprint; a branch name or HEAD SHA alone is insufficient for a
dirty candidate. Include the identity in the report
so verification and delivery can establish that they assess the same candidate.

Before reporting, confirm that the candidate has not changed during review.
If it has, report `INCOMPLETE`; do not carry a `CLEAN` result to changed content.

## Report the review

Start the report with exactly one review status:

- `CLEAN` — no actionable findings were identified;
- `FINDINGS` — one or more actionable findings were identified;
- `INCOMPLETE` — the review could not be completed with sufficient confidence
  because the comparison base, required source, environment, or other necessary
  evidence was unavailable.

Use `INCOMPLETE` when necessary evidence is unavailable, even if actionable
findings were identified in the reviewed portion. Report those findings and
the unresolved review scope.

- After the status, put findings first and order them by the severity definitions
  in `AGENTS.md`.
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

A `CLEAN` review means that no actionable review findings were identified.
It does not mean that independent verification has passed.

After reporting the result, stop.
