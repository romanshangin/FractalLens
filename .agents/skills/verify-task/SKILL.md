---
name: verify-task
description: >
  Perform independent post-review verification of a completed FractalLens task.
  Use only when explicitly invoked by the user or orchestrator after implementation
  and independent review are complete. Do not use during implementation,
  developer self-validation, code review, or delivery.
---

# Verify a FractalLens task

## Role boundary

This skill is an independent post-review verification stage.

Use this skill only when all of the following are true:

- implementation is complete;
- independent `review-task` has reported `CLEAN` for the current candidate
  and intended base;
- the user or orchestrator explicitly requested this verification stage.

Do not use this skill:

- during implementation;
- for developer self-validation or pre-handoff testing;
- during code review;
- merely because another workflow asks to run tests or validate a change;
- as part of `implement-task`;
- as part of `review-task`;
- as part of `deliver-task`.

Developer self-validation and independent verification are separate stages.

If a prerequisite is unmet or cannot be established, report `INCOMPLETE` and
stop this stage without invoking another workflow.

Do not invoke other workflow skills from this skill.
You may create normal build, test, and diagnostic artifacts required by
verification.

Do not modify the candidate source, tests, build configuration,
dependencies, documentation, or other validation-relevant content.

The orchestrator verifies candidate identity after this stage.
Keep generated artifacts in ignored build directories or outside the repository;
nonignored untracked files are part of the candidate identity.

Do not implement fixes.
Do not commit, push, create a pull request, or merge changes.

Follow the testing rules, subsystem constraints, and acceptance criteria in
`AGENTS.md` and the relevant project validation documents.

## Define the verification scope

1. Check the current branch and working tree before running verification.
   Record pre-existing changes and treat them as user-owned.
2. Inspect the requested change and its complete repository diff against the
   appropriate base, including related tests and documentation.
   Record the intended base revision, comparison merge base, and candidate
   content identifier from the review handoff. Confirm that this identity
   covers the current complete candidate, including intended uncommitted and
   untracked files; a branch name or HEAD SHA alone is insufficient for a dirty
   candidate. If the candidate or base differs, stop with `INCOMPLETE` until a
   fresh `CLEAN` review is available.
   In orchestrated runs, use the orchestrator-supplied identity rather than
   computing an alternative fingerprint. The orchestrator checks repository
   state before and after this stage and owns candidate fields in the handoff.
3. Identify affected behavior, platforms, modules, fallbacks, and roadmap or
   document acceptance criteria.
4. List the required acceptance checks and map each to sufficient current
   automated execution evidence or a missing check. The full portable suite
   required by `AGENTS.md` remains mandatory for a production-code change, but
   matching execution evidence may satisfy it without re-execution. Apply the
   candidate, definition, environment and log-integrity reuse rules in `AGENTS.md`.
   Independent verification means independently assessing acceptance evidence
   and executing missing checks, not repeating every developer test.
5. For documentation-only changes, do not run unrelated application tests.
6. Identify planned tests and benchmark scripts that initialize JavaFX before
   executing them. Apply the `AGENTS.md` display rule to both: if the current
   sandbox is known to have no display, run in an active desktop session from
   the first attempt. If desktop access is unavailable, classify the check as
   unverified without first attempting it in that sandbox.

## Run and assess checks

- Reuse sufficient current PASS evidence; do not rerun it merely for confidence.
  A prose handoff alone is not reusable automated execution evidence. Inspect
  shared registry records and referenced check logs for coverage and skips.
  Run missing checks through the supplied `scripts/orchestration/check.ts` context in an
  orchestrated task. Replace stale, insufficient or suspicious evidence;
  explain any forced rerun. Standalone verification without harness evidence
  must execute the required checks.
- For missing checks, start with the nearest targeted tests, then the required
  portable and subsystem-specific checks from `AGENTS.md`.
- Validate observable output, frame contents, fallback behavior, or visible UI
  behavior where callbacks, logs, or model tests are only proxies.
- For UI, native, launcher, GPU, packaging, or clean-machine acceptance, record
  the actual environment and do not generalize results to an untested platform
  or device.
- If a required check cannot run because its platform, hardware, credentials,
  toolchain, or external dependency is unavailable, classify it as unverified
  due to an environment limitation, not as passed or as a product failure.
- For performance acceptance, use the fixed control/candidate protocol and
  evidence requirements from the relevant benchmark document. Timing alone is
  not a correctness check. Do not claim a performance improvement from a
  candidate-only measurement.
- Treat a skipped test as skipped, not passed.

When a check fails, determine whether the evidence identifies a product defect,
a test defect, or an environment failure. Rerun only when there is a concrete
reason the rerun can distinguish those cases; do not hide or suppress a failure.
Report required fixes for a separate implementation stage.
Do not expand verification into unrelated investigation. Record unrelated
failures separately unless they prevent verification of the requested change;
do not attribute them to the change without evidence.

## Repository hygiene

Before reporting the result, run:

```shell
git diff --check
git status --short
```

Inspect the diff and status for accidental generated output, IDE metadata,
temporary files, and unrelated changes. Preserve and identify pre-existing
user-owned changes.

## Report the result

Start the report with exactly one verification status:

- `PASS` — the current candidate has a matching `CLEAN` review and all required
  checks and acceptance criteria passed, including required visible checks;
- `FAIL` — a required check or acceptance criterion has a confirmed product or
  test failure;
- `INCOMPLETE` — prerequisites, necessary evidence, or required checks could
  not be established or completed, including environment limitations and
  skipped or unrun required checks.

Before reporting, confirm that the candidate and base still match the review
handoff. If they changed during verification, report `INCOMPLETE` and identify
any failures observed for the earlier candidate. Otherwise a confirmed required
failure takes precedence over incomplete checks; report `FAIL` and list the
remaining gaps. Environment failures alone do not establish `FAIL`.

Then state:

- the candidate identity, base revision, and matching `CLEAN` review evidence;
- the exact commands or manual checks, distinguishing reused execution evidence
  (original stage and environment) from freshly executed checks, and whether each passed, failed,
  was skipped, or could not be verified;
- the behavior and acceptance criteria those checks cover;
- any environment limitation and the evidence for that classification;
- every relevant behavior, platform, or manual check that remains unverified.

Do not call the task complete if a required check failed, was not run, was
skipped, could not be verified, or if required visible behavior was not
inspected.

After reporting the result, stop and wait for the user or orchestrator to start
the next workflow stage.
