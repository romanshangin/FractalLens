---
name: deliver-task
description: Perform explicitly authorized FractalLens delivery actions—commit, push, pull-request creation or update, merge, or close—with scoped staging, validation provenance, CI evidence, and publication checks. Use only for those actions, not for implementation, review, or verification alone.
---

# Deliver FractalLens changes

Follow the Git policy in `AGENTS.md`. This workflow does not grant permission to
commit, push, create, update, merge, or close a pull request: perform only the
external mutations the user explicitly requested.

Commit, push, force-push, pull-request creation or update, merge, and close are
protected delivery mutations. Authorization is per protected mutation and is
not transitive. A generic request to publish or deliver selects this workflow
but does not authorize unspecified protected mutations. Do not infer permission
for prerequisite protected mutations from the requested outcome. A request to
push does not authorize a commit; a request to create or update a pull request
does not authorize a commit or push; and a request to merge does not authorize
rebasing, updating, pushing, or force-pushing the head branch. If a requested
action depends on an unauthorized protected mutation, stop at that boundary and
report the required action.

When a short request says only "deliver" or names a roadmap item, treat the
protected action set as unspecified. Complete read-only preflight, then ask
which of commit, push, pull-request creation/update, merge, or close is
authorized before performing any of those mutations. Do not infer the missing
authorization from the existence of a task branch or a likely PR workflow.

## Preflight

1. Inspect the state of every resource affected by the requested protected
   mutation.
2. For actions that commit, push, create a pull request from local work, deliver
   changed content, change candidate context, or merge, perform local candidate
   preflight:
   - Confirm the current branch, its merge base, remotes, and working-tree
     status. Preserve a task branch already selected by the user unless it is
     `main`. When a new task branch is required, follow the change-based naming
     rule in `AGENTS.md` (`feature/`, `fix/`, or `chore/`) and create it from
     the intended current base revision, normally the refreshed `origin/main`,
     unless the user explicitly selected another base. Safely refresh the
     relevant remote refs before relying on them; if that is not possible,
     report their freshness as unverified instead of describing a local
     remote-tracking ref as current. Do not require local `main` to be current
     merely to establish the task branch.
     If intended uncommitted changes are on the base branch, create the task
     branch without discarding or stashing them when Git can do so safely. Do
     not stash, reset, overwrite with checkout, or relocate user-owned changes
     merely to normalize branch state. If the intended branch cannot be
     established safely, stop before mutating Git state and report the blocker.
   - Inspect the full diff and separate intended task changes from pre-existing
     or unrelated user-owned changes.
   - Audit untracked content before staging. Benchmark output directories can
     contain thousands of raw logs and process artifacts. Check file count,
     aggregate size, generated-file policy, and whether each artifact is
     required for reproducibility. Do not stage an entire directory merely
     because its top-level name matches the task; stage an explicit reviewed
     evidence set when raw output retention is not required.
   - Apply the verification gate when committing or delivering changed content
     and before merging. Confirm that `verify-task` completed the checks required
     by `AGENTS.md`. If a required check failed or remains unrun, report that gap
     and do not bypass it. Before committing, confirm that the staged diff is
     exactly the intended deliverable covered by the latest verification. Treat
     verification as stale if the staged or committed content changes afterward,
     or if a rebase, conflict resolution, or another history-changing operation
     changes the base revision in a way that can affect the validated result.
     When unrelated working-tree changes can affect the build, tests,
     configuration, dependencies, fixtures, or runtime behavior, do not treat
     verification performed with those changes present as evidence for the
     staged candidate. Run `verify-task` in an equivalent state containing the
     intended deliverable without validation-relevant unrelated changes, without
     discarding, stashing, or relocating user-owned work. Prefer a separate
     worktree or another non-destructive isolated state when verification cannot
     otherwise exclude those unrelated changes. Re-run `verify-task` when
     source, tests, build, configuration, dependencies, or other
     validation-relevant files changed; documentation-only changes may reuse
     prior verification only when they cannot affect the validated behavior.
   - Run the final repository-hygiene checks and exclude generated output, IDE
     metadata, temporary files, and unrelated benchmark artifacts. Run
     `git diff --cached --check` after staging, not only before staging, and
     classify whitespace findings in generated logs. Preserve a finding only
     when the log is an intentional retained evidence artifact and document
     that hygiene exception; otherwise remove the artifact before committing.
   - If the sandbox cannot write `.git/index.lock`, `.git/FETCH_HEAD`, or other
     repository metadata, request the minimal approved Git operation with
     escalation rather than working around Git state files. Afterwards rerun
     status and relevant SHA/ref checks; do not describe remote freshness as
     verified when `git fetch` did not succeed.
3. For metadata-only pull-request updates or close operations that do not change
   candidate context, inspect the pull request and required remote state only.
   Do not require unrelated local repository preflight or allow unrelated local
   working-tree changes to block the requested administrative action.

## Commit and push when authorized

- Stage only the intended files.
- Review the staged diff before committing.
- Use a concise English commit message that describes the delivered outcome.
- Push only the task branch; never substitute a direct push to `main` for the
  pull-request workflow.
- If the remote task branch has diverged or a push is rejected as
  non-fast-forward, inspect the remote commits and stop before overwriting them.
  Never force-push by default. Use `--force-with-lease` only when the user
  explicitly authorizes a force-push and after confirming that no unexpected
  remote work would be lost; never use plain `--force`.
- Verify the local commit SHA and the remote task-branch SHA after pushing.

## Pull request actions when authorized

- For a newly created pull request, use the intended base branch, normally
  `main`, and the pushed task branch as the head.
- Reuse an existing open pull request when it matches the intended head and
  base; do not create a duplicate for the same candidate context. If only
  pull-request creation was authorized and a matching pull request already
  exists, do not modify it; report it instead. If an open pull request for the
  head branch targets a different base, treat it as a different candidate
  context and follow the user's authorized request and repository policy.
  Verify an existing pull request's base and head, but update its base, title,
  body, or other metadata only when the corresponding pull-request update is
  authorized. When metadata changes are authorized, keep its title and body
  aligned with the delivered diff and current validation evidence.
- Treat a pull-request base change as a candidate-context change. Refresh the
  diff, mergeability, validation applicability, and CI evidence before claiming
  readiness or merging. A matching pull-request head and remote task-branch SHA
  is not sufficient evidence after the base changes. Treat prior verification
  as stale when the new base can affect the validated result; re-run
  `verify-task` before claiming readiness or merging.
- Summarize the outcome and rationale, list validation commands and results, and
  disclose anything still unverified.
- Do not claim roadmap or acceptance completion without the required evidence.
- After each push, refresh the pull-request state. Verify that the pull-request
  head SHA matches the pushed remote task-branch SHA before interpreting CI or
  merging. This establishes head provenance but does not replace candidate-level
  CI evidence.
- Merge only when every required check applicable to the current pull-request
  candidate has completed successfully. Ensure that the evidence belongs to the
  current head and base context; checks for an earlier or superseded candidate
  are stale. Treat a missing, pending, cancelled, failed, or stale required check
  as not mergeable. If the repository has no required CI checks, report that fact
  instead of presenting absent CI as successful validation. If required CI
  remains pending when delivery is otherwise complete, report the pending state
  rather than polling indefinitely, treating it as success, or bypassing it.
- Before merging, confirm that the pull request is mergeable against the current
  base and has no unresolved conflicts. If repository policy requires the head
  to be current with the base, treat an out-of-date head as not mergeable until
  it is updated and `verify-task` covers the resulting candidate.
- Merge or close the pull request only when the user explicitly requests that
  action. Honor an explicitly requested merge strategy only when repository
  policy and settings permit it; otherwise report the blocker. When no strategy
  was requested, use the strategy required or configured as the default by
  repository settings or `AGENTS.md`. Do not select a different strategy merely
  for convenience.

After an authorized merge, update the local base branch, normally `main`, when
that can be done safely without disturbing user-owned work; otherwise leave the
working tree unchanged and report that the local base branch was not
synchronized. When updated, verify local and remote base-branch parity. Do not
rewrite, discard, or silently absorb unrelated working-tree changes to
accomplish delivery.

## Report delivery state

List the changed files, validation results, commit SHA, pushed branch, pull
request URL, state and mergeability, relevant CI result, and anything that
remains unverified. Report only states that apply, and distinguish actions that
were not requested, not performed, blocked, or not applicable. State explicitly
which requested external actions were not performed.
