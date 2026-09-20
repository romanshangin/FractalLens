---
name: verify-task
description: Verify FractalUI changes with risk-proportionate tests, visible checks, benchmark evidence, and repository hygiene. Use for test, validation, acceptance, or pre-handoff verification requests; not for implementing fixes or publishing changes.
---

# Verify a FractalUI task

Follow the testing rules, subsystem constraints, and acceptance criteria in
`AGENTS.md` and the relevant project validation documents.

## Define the verification scope

1. Check the current branch and working tree before running verification.
   Record pre-existing changes and treat them as user-owned.
2. Inspect the requested change and its complete repository diff against the
   appropriate base, including related tests and documentation.
3. Identify affected behavior, platforms, modules, fallbacks, and roadmap or
   document acceptance criteria.
4. Choose the smallest set of checks that proves those behaviors, while still
   running the full portable suite required by `AGENTS.md` before handing off a
   production-code change.
5. For documentation-only changes, do not run unrelated application tests.

## Run and assess checks

- Start with the nearest targeted tests for fast feedback, then run the required
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
Do not implement a fix unless the user also authorized implementation.
Do not expand verification into unrelated investigation. Record unrelated
failures separately unless they prevent verification of the requested change.

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

State:

- the exact commands or manual checks executed and whether each passed, failed,
  or was skipped;
- the behavior and acceptance criteria those checks cover;
- any environment limitation and the evidence for that classification;
- every relevant behavior, platform, or manual check that remains unverified.

Do not call the task complete if a required check failed, was not run, or if a
required visible behavior was not inspected.
