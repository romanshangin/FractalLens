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
   explicitly selected another base.
2. Inspect the full diff and separate intended task changes from pre-existing or
   unrelated user-owned changes.
3. Confirm that `verify-task` completed the checks required by `AGENTS.md` for
   this change. If a required check failed or remains unrun, report that gap and
   do not bypass it.
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
- Summarize the outcome and rationale, list validation commands and results, and
  disclose anything still unverified.
- Do not claim roadmap or acceptance completion without the required evidence.
- Inspect CI for the PR head SHA and do not merge while required checks fail.
- Merge or close the pull request only when the user explicitly requests that
  action.

After an authorized merge, update local `main` safely and verify local and remote
`main` parity. Do not rewrite, discard, or silently absorb unrelated working-tree
changes to accomplish delivery.

## Report delivery state

List the changed files, validation results, commit SHA, pushed branch, pull
request URL and state, relevant CI result, and anything that remains unverified.
State explicitly which requested external actions were not performed.
