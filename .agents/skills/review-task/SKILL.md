---
name: review-task
description: Perform a read-only FractalUI code, diff, or change assessment and report actionable findings with evidence and remediation. Use for review, audit, assessment, or re-review requests; not for implementing fixes unless the user explicitly includes implementation.
---

# Review a FractalUI task

Follow all project-wide rules, constraints, testing requirements, Git policy,
and severity definitions in `AGENTS.md`.

## Keep review read-only

Inspect the code, comparison diff, tests, and relevant documentation without
modifying files, applying fixes, creating commits, pushing, or changing pull
requests. Read-only diagnostic commands and tests are allowed when they are
relevant and proportionate.

If the user explicitly requests both review and implementation, report the
review findings first, then use the `implement-task` workflow for the authorized
fixes.

## Review method

1. Establish the intended comparison target and inspect the complete relevant
   diff, including existing working-tree changes.
2. Read the affected implementation flow, tests, and project sources of truth.
3. Look for concrete regressions and missing executable coverage. Prioritize:
   1. mathematical errors or loss of decimal precision;
   2. stale-frame publication after cancellation or a scene change;
   3. races and leaks of listeners, executors, or native resources;
   4. blocking the JavaFX Application Thread;
   5. broken CPU fallback or cross-platform startup;
   6. GPU/CPU conformance failures;
   7. resize, pan, zoom, Reset View, or fractal-change artifacts;
   8. tests that do not cover changed behavior;
   9. performance claims without a valid control measurement;
   10. documentation or roadmap claims unsupported by acceptance evidence.
4. Validate suspected findings against the actual behavior or contract before
   reporting them. Do not elevate stylistic preference into a finding.

## Report the review

- Put findings first and order them by the severity definitions in `AGENTS.md`.
- Give each finding a precise file and line reference, the concrete impact, and
  a practical remediation.
- Keep one root cause per finding. Do not inflate severity because multiple
  symptoms share that cause.
- If there are no findings, say so explicitly and list residual risks,
  unexecuted tests, or validation gaps.
- Report commands run and distinguish product failures from environment
  failures with evidence.
