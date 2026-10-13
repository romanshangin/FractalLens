---
name: deliver-task
description: >
  Publish a reviewed and, when required, independently verified FractalLens task by committing
  the intended changes, pushing the task branch, and creating or updating its
  pull request. Use only for the delivery stage after required review and
  verification gates have passed. Do not implement, review, verify, merge, or
  close pull requests.
---

# Deliver FractalLens changes

## Role boundary

This skill is the publication stage of the task workflow.

Its responsibility is to publish an already implemented, reviewed, and, when
required, independently verified candidate as a pull request.

Do not invoke or perform:

- `implement-task`;
- `review-task`;
- `verify-task`;
- implementation or fixes;
- independent review;
- independent verification;
- pull-request merge or close.

Do not modify the validated candidate except for delivery metadata such as the
commit message or pull-request title and body.

If delivery would require changing source, tests, build configuration,
dependencies, or other validation-relevant content, stop. The candidate must
return to the implementation workflow.

After publishing the pull request and reporting its state, stop. Leave the
merge decision to the human.

Follow the Git, privacy, and publication rules in `AGENTS.md`.

## Delivery authorization

An explicit request to execute `deliver`, `publish`, or this delivery stage
authorizes the standard pull-request delivery sequence:

1. Commit the validated candidate when intended uncommitted changes exist.
2. Push the task branch.
3. Create or update the matching pull request.

The request may come from the user or an orchestrator acting within the
user-authorized task. An instruction to inspect or edit this skill is not an
instruction to execute delivery.

This authorization never includes force-push, merge, close, destructive Git
operations, or changes to validation-relevant content. Honor explicit limits:
a request for only one delivery action authorizes only that action. Do not
infer delivery authorization merely from a roadmap item or an existing branch.

## Verification provenance

Independent verification is required by default. Only an explicit user or
orchestrator task policy `verificationRequired=false` may skip this stage;
missing verification evidence is not a policy decision. Skipping the stage
does not waive required tests or acceptance criteria in `AGENTS.md` or project
validation documents. Record the explicit skip policy in the delivery report.

Before publishing changed content, require `review-task = CLEAN` and, when
verification is required, `verify-task = PASS` for the same exact candidate and
intended base being delivered. Compare the candidate content identifier, base
revision, comparison merge base, and complete PR diff with the required
handoffs, including intended uncommitted and untracked files.

Orchestrated handoffs may contain compact candidate provenance, check results,
and limitations instead of full developer/reviewer reasoning or execution logs.
Validate that evidence against the repository under the same gates.
In orchestrated runs, candidate identity is computed and attached by code,
not supplied by REV/VER models. Honor the expected base and complete candidate;
do not silently exclude unrelated checkout content to obtain a different tree.

Treat review as stale after any candidate content or base change. Treat
verification as stale after validation-relevant source, tests, build,
configuration, dependency, or base changes, including rebase or conflict
resolution. Staging or committing identical candidate content does not by
itself invalidate either report; establish content and base equivalence rather
than relying only on the earlier HEAD SHA.

Documentation-only changes require a fresh `CLEAN` review. When verification
is required, that stage may reuse unaffected prior checks, but must issue a
`PASS` handoff for the current candidate. Delivery must not infer applicability.

Evidence collected with validation-relevant unrelated working-tree changes
does not establish verification of a candidate that excludes those changes.

If required review or verification is missing, failed, incomplete, or stale,
stop before publishing and report the gap. Do not invoke workflow skills or
rerun acceptance validation automatically.

## Preflight

- Confirm the intended task branch, base, merge base, remote, and working-tree
  state. Do not deliver directly from `main`. If the candidate is not on an
  appropriate task branch, stop and report the branch-state problem. Do not
  create or switch branches, rebase, resolve conflicts, or reorganize
  implementation work during delivery.
- Safely refresh relevant remote refs before relying on them. If that fails,
  report remote freshness as unverified and stop any action that depends on it.
- Inspect the complete candidate diff, including committed, staged, unstaged,
  and untracked content. Separate intended changes from unrelated user-owned
  work; preserve that work without stashing, resetting, or relocating it.
- Audit untracked and generated content before staging. For benchmark output,
  check file count, aggregate size, publication policy, and reproducibility
  needs. Stage an explicit reviewed evidence set, not an entire directory
  merely because its name matches the task.
- Apply the privacy and secret-scan requirements in `AGENTS.md` before
  committing or publishing evidence. Keep raw logs, diagnostic dumps, and
  unselected working evidence private.
- If the sandbox cannot write Git metadata such as `.git/index.lock` or
  `.git/FETCH_HEAD`, request the minimal approved operation with escalation
  rather than working around Git state files. Recheck status and relevant
  SHAs/refs afterward.
- For an explicitly limited metadata-only PR update that leaves head and base
  unchanged, inspect the PR and relevant remote evidence. Do not let unrelated
  local changes block that administrative action.

## Commit and push

- Stage only intended files. Do not include unrelated staged changes.
- Inspect the staged diff and confirm it matches the validated candidate.
- Run `git diff --cached --check` after staging. Exclude accidental generated
  output, IDE metadata, temporary files, and unrelated benchmark artifacts.
  Preserve whitespace findings only in intentional retained evidence with a
  documented hygiene exception; otherwise stop and report the issue without
  rewriting the candidate.
- Commit only when intended uncommitted changes exist, using a concise English
  message describing the outcome.
- Push only the intended task branch. Never push directly to `main`.
- If the remote branch has diverged or a push is rejected as non-fast-forward,
  inspect the remote commits and stop before overwriting them. Use
  `--force-with-lease` only with explicit user authorization for force-push and
  after confirming that no unexpected remote work would be lost. Never use
  plain `--force`.
- Verify that the local commit SHA matches the remote task-branch SHA after
  pushing.

## Create or update the pull request

- Use the intended base, normally `main`, and the pushed task branch as head.
- Reuse an existing open PR with the matching head and base; do not create a
  duplicate. If an existing PR for the head targets a different base, stop and
  report the candidate-context mismatch instead of retargeting it.
- Keep the PR title and body aligned with the delivered diff and validation
  evidence. Describe the outcome, rationale, checks and results, and unverified
  areas. Do not claim roadmap or acceptance completion without evidence.
- After creating a PR, call the pull-request attachment tool when available.
  Attachment is best-effort: if unavailable or unsuccessful, report the PR URL
  and attachment limitation without blocking successful publication.
- Refresh PR state after pushing and creating or updating the PR. Confirm that
  the PR head SHA matches the pushed remote task-branch SHA and that its base
  still matches the validated context before interpreting CI evidence.

## CI state

Report required CI checks for the current PR head and base as:

- `PASS` — all required checks completed successfully for this candidate;
- `FAIL` — at least one required check failed, timed out, or was cancelled;
- `PENDING` — required evidence is missing, queued, running, stale, or could
  not be retrieved;
- `NOT_CONFIGURED` — confirmed that no required CI checks are configured.

Include relevant Qodana results and distinguish additional checks from required
checks. Results from an earlier head or base are stale. Do not treat missing or
unavailable configuration as `NOT_CONFIGURED`.

Do not wait indefinitely for pending CI. Report pending or failed checks
without bypassing them. Do not merge or close the PR, enable automatic merge, or
modify CI requirements.

## Report delivery state

Start the report with exactly one delivery status:

- `DELIVERED` — all requested delivery actions completed; for standard delivery,
  the PR exists and its head and base match the published candidate;
- `BLOCKED` — a concrete gate, authorization, branch, or environment problem
  prevented delivery before any requested commit, push, or PR mutation succeeded;
- `INCOMPLETE` — some requested mutations succeeded but delivery did not finish,
  or the resulting publication state could not be confirmed.

CI status is separate from delivery status: pending or failed CI does not undo
a confirmed publication. A best-effort attachment failure alone does not change
`DELIVERED` to `INCOMPLETE`.

Report the delivered files, review and verification evidence, commit SHA,
pushed branch, PR URL and state, head/base provenance, required CI status and
relevant Qodana results, and anything still unverified.

Distinguish completed, blocked, not requested, and not applicable actions.
If publication stopped partway through, report exactly which mutations
succeeded and which remain outstanding. A published PR with pending or failed
CI is not acceptance completion.

After reporting the result, stop.
