---
name: deliver-pr
description: Deliver validated FractalUI changes on a codex task branch through a pull request, with scoped staging, English commit text, CI evidence, and publication checks. Use only when the user explicitly asks to commit, push, create or update a PR, merge, or publish changes.
---

# Deliver a FractalUI pull request

Follow the Git policy in `AGENTS.md`. This workflow does not grant permission to
commit, push, create, update, merge, or close a pull request: perform only the
external mutations the user explicitly requested.

## Preflight

1. Confirm the current branch, its merge base, remotes, and working-tree status.
   New work must use a `codex/` task branch created from `main` unless the user
   explicitly selected another base. If intended uncommitted changes are on the
   base branch, create the task branch without discarding or stashing them when
   Git can do so safely. Do not stash, reset, overwrite with checkout, or
   relocate user-owned changes merely to normalize branch state. If the intended
   branch cannot be established safely, stop before mutating Git state and
   report the blocker.
2. Inspect the full diff and separate intended task changes from pre-existing or
   unrelated user-owned changes.
3. Confirm that `verify-task` completed the checks required by `AGENTS.md` for
   this change. If a required check failed or remains unrun, report that gap and
   do not bypass it. Treat verification as stale when the deliverable diff
   changes afterward. Re-run the relevant verification when source, tests,
   build, configuration, dependencies, or other validation-relevant files
   changed; documentation-only changes may reuse prior verification only when
   they cannot affect the validated behavior.
4. Run the final repository-hygiene checks and exclude generated output, IDE
   metadata, temporary files, and unrelated benchmark artifacts.

## Commit and push when authorized

- Stage only the intended files.
- Review the staged diff before committing.
- Use a concise English commit message that describes the delivered outcome.
- Push only the task branch; never substitute a direct push to `main` for the
  pull-request workflow.
- Verify the local commit SHA and the remote task-branch SHA after pushing.

## Pull request actions when authorized

- Use the intended base branch, normally `main`, and the pushed task branch as
  the head.
- Reuse an existing open pull request for the task branch when one exists; do
  not create a duplicate. Verify its base and head before updating it. When PR
  creation or metadata changes are authorized, keep its title and body aligned
  with the delivered diff and current validation evidence.
- Summarize the outcome and rationale, list validation commands and results, and
  disclose anything still unverified.
- Do not claim roadmap or acceptance completion without the required evidence.
- After each push, refresh the pull-request state. Verify that the pull-request
  head SHA matches the pushed remote task-branch SHA before interpreting CI or
  merging; CI results for an earlier SHA are stale.
- Merge only when every required check for the current pull-request head SHA has
  completed successfully. Treat a missing, pending, cancelled, failed, or stale
  required check as not mergeable. If the repository has no required CI checks,
  report that fact instead of presenting absent CI as successful validation.
- Merge or close the pull request only when the user explicitly requests that
  action. Honor an explicitly requested merge strategy; otherwise use the
  strategy required or configured as the default by repository settings or
  `AGENTS.md`. Do not select a different strategy merely for convenience.

After an authorized merge, update local `main` safely and verify local and remote
`main` parity. Do not rewrite, discard, or silently absorb unrelated working-tree
changes to accomplish delivery.

## Report delivery state

List the changed files, validation results, commit SHA, pushed branch, pull
request URL and state, relevant CI result, and anything that remains unverified.
Report only states that apply, and distinguish actions that were not requested,
not performed, blocked, or not applicable. State explicitly which requested
external actions were not performed.
