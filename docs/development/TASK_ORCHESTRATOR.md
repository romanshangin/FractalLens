# Task orchestrator v1

The local TypeScript runner selects workflow stages with a deterministic state
machine. It uses no manager model, runtime dependencies, parallel agents, automatic
merge, or automatic recovery from unknown results.

## Requirements and launch

Use Node.js 24 or later (native TypeScript type stripping), Git, and an
authenticated Codex CLI that supports `exec`, `--approve-for-me`, `--ephemeral`,
and `--output-last-message`. GitHub access is needed by the delivery executor.
The application build and Maven dependencies are unchanged.

For TypeScript checking and IntelliJ IDEA editor support, install the locked
development-only Node.js type definitions:

```sh
npm ci --prefix scripts
```

`scripts/tsconfig.json` explicitly loads the `node` types and uses NodeNext
module resolution, strict checking, and no emitted JavaScript. The scripts
package declares ES modules so `import.meta` and native `.ts` imports match
their runtime behavior. Configure IDEA to use this TypeScript project.
Type definitions are only needed for editing/type checking, not runtime launch.

Inspect the plan without running models or publishing anything:

```sh
node scripts/task-orchestrator.ts --task "P2.3 - Task description" --dry-run
```

Execute the workflow:

```sh
node scripts/task-orchestrator.ts --task "P2.3 - Task description"
```

Executing the workflow authorizes standard delivery of the validated task:
commit intended changes when needed, push the task branch, and create or update
its PR. It never authorizes force-push, merge, close, or automatic merge.
Implementation establishes or reuses the task branch according to `AGENTS.md`;
delivery does not create or reorganize branches. Run only one workflow at a time
in a checkout and preserve unrelated user-owned work.

Use `--repo` for another FractalLens checkout and `--codex` for an explicit CLI
executable. Arguments are passed without a shell. On Windows, use the native
Codex executable rather than a `.cmd` wrapper. Model IDs are passed exactly as
listed below; availability depends on the configured Codex account. There is
no silent model substitution.
Use `--base` to select the intended remote-tracking PR-base ref (default: `origin/main`).
Remote-tracking refs such as `origin/release-2` or
`refs/remotes/origin/release-2` are refreshed from their configured remote before
model execution. Every role receives the original ref, normalized PR branch
(`release-2`), and frozen base SHA. Local branches (`main`, `refs/heads/main`),
commit IDs and tags are rejected before any role starts: they cannot establish
freshness of the remote PR base. Use `origin/main`, `origin/release-2`, or
`upstream/main` instead.
An unavailable base or failed refresh stops before model execution. Dry-run
performs no fetch or other mutation.

The comparison base SHA stays fixed through implementation, review and
verification. Immediately before DELIVER, the runner refreshes the remote base
again and requires the same SHA. A changed or unavailable base stops as
`INCOMPLETE` before the delivery executor starts. Refresh commands have a
30-second timeout. This is a pre-publication check, not a lock on the remote
branch; it can still advance after the check.

## Roles and transitions

| Stage | Skill | Model | Reasoning effort | Sandbox |
| --- | --- | --- | --- | --- |
| IMPLEMENT / IMPLEMENT_FIXES | `implement-task` | `gpt-6.1-sol` | medium | workspace-write |
| REVIEW | `review-task` | `gpt-6.1-sol` | medium | read-only |
| VERIFY | `verify-task` | `gpt-6-luna` | medium | read-only |
| DELIVER | `deliver-task` | `gpt-6-luna` | low | workspace-write |

REV/VER receive `approval_policy="never"` and never `--approve-for-me`.
Their sandboxed local commands cannot edit the candidate or request an
escalation. Sandbox policies govern local commands; externally configured MCP
servers and connectors have separate controls. The repository identity guard
also detects candidate changes made outside sandboxed local commands.

Strict read-only also blocks build-output/cache/temp writes. The local macOS
CLI's `:read-only` sandbox probe denied both repository and temporary-file
writes. Checks requiring those writes cannot run under this policy: report
`INCOMPLETE` and stop, without substituting DEV self-validation for independent
VER. A writable-output test harness would be a separate capability design;
v1 does not automatically grant workspace writes to make Maven pass.

Each invocation starts a fresh ephemeral CLI session with the task and only the
latest relevant handoffs. The runner reads the final message file; CLI progress
and diagnostic output cannot select a state. Its first nonempty line must be a
bare role status, such as `CLEAN`, without Markdown decoration or other text.

Handoffs contain only the evidence needed by the next role:

| Invocation | Supplied evidence |
| --- | --- |
| Initial IMPLEMENT | Task only |
| Initial REVIEW | Latest implementation report |
| IMPLEMENT_FIXES | Latest REVIEW `FINDINGS` or VERIFY `FAIL` report |
| REVIEW after fixes | Latest implementation and previous review; also the failure report for VER-driven fixes |
| VERIFY | Latest implementation and current `CLEAN` review |
| DELIVER | Current REV/VER statuses and compact candidate/check summaries; no DEV report or full REV/VER text |

Re-review must inspect the complete current diff and explicitly check closure
of previous findings/failures. Historical evidence cannot serve as a new
`CLEAN` or `PASS`. Older repair rounds are archived privately, not accumulated
in prompts. The configured repair limit is passed to every invocation.

Passing REV/VER reports must include exactly one fenced `delivery-handoff`
JSON block, in addition to their normal report:

```delivery-handoff
{"checks":["<concise command/result/coverage>"],"limitations":[]}
```

The model reports only checks and limitations; candidate fields are rejected.
The block is limited to 6,000 characters and each array to 16 nonempty strings
of at most 512 characters. `checks` must not
be empty; `limitations` may be empty. Do not place raw logs or full reasoning
inside this block. Missing, malformed, duplicate, oversized, or inconsistent
evidence stops the runner as `INCOMPLETE` before DELIVER. With VER skipped,
only REV evidence is required. Full reports remain in private run artifacts.

| Stage result | Next step |
| --- | --- |
| IMPLEMENT `READY_FOR_REVIEW` | REVIEW |
| IMPLEMENT `BLOCKED` | STOP |
| REVIEW `FINDINGS` | IMPLEMENT_FIXES, then fresh REVIEW |
| REVIEW `CLEAN` | VERIFY, or DELIVER under explicit skip policy |
| REVIEW `INCOMPLETE` | STOP |
| VERIFY `FAIL` | IMPLEMENT_FIXES, then fresh REVIEW and VERIFY |
| VERIFY `PASS` | DELIVER |
| VERIFY `INCOMPLETE` | STOP |
| Any DELIVER status | STOP; report PR and CI state |
| Missing/invalid status, executor error, or timeout | STOP as `INCOMPLETE` |

At most three repair rounds are allowed across review and verification
failures combined. `--max-repair-rounds 0`, `1`, or `2` can lower that limit;
values above three are rejected. Exceeding the limit returns `BLOCKED` with a
review or verification repair-limit reason. Repairs invalidate old handoffs;
a prior `CLEAN` or `PASS` cannot bypass fresh review after a fix.

`READY_FOR_REVIEW` means implementation is complete and required developer
self-validation passed; it is not full task acceptance. Independent verification
or manual UI acceptance that cannot be performed during implementation is
reported as a verification gap and does not by itself block IMPLEMENT or
IMPLEMENT_FIXES, unless `AGENTS.md` or the task explicitly makes that check an
implementation-stage gate. Required developer self-validation remains mandatory.
`verificationRequired` selects only the transition after REVIEW `CLEAN`: VERIFY
or DELIVER. When VERIFY cannot perform a required acceptance check, it reports
`INCOMPLETE` and the workflow stops before delivery.

Independent verification is required by default. Only explicit caller policy
may skip the stage:

```sh
node scripts/task-orchestrator.ts --task "Task description" --skip-verification
```

This passes `verificationRequired=false` to all executors and the delivery
gate. It does not waive developer self-validation, project tests, or acceptance
criteria. The caller must ensure the task permits skipping independent VER;
v1 does not classify risk or decide whether verification is required.

## Candidate identity

The orchestrator computes `{baseRevision, mergeBase, contentId}` after each
successful DEV stage and supplies that identity to REV, VER and DELIVER. It
recomputes repository state before and after each independent stage, including
before DELIVER even when VER is skipped. A mismatch or calculation failure
stops as `INCOMPLETE`; mutations already made by delivery are not undone.
Only matching, code-stamped REV/VER evidence can reach delivery.

One deterministic algorithm covers the complete checkout candidate:

1. Use the frozen base commit and compute its merge base with HEAD.
2. Enumerate index-tracked and nonignored untracked paths with Git's NUL-delimited
   output; sort decoded UTF-8 paths in JavaScript string order. Omit missing/deleted files.
3. For each file, hash a newline-terminated JSON record of repository-relative
   path, Git mode, and SHA-256 of bytes (or symlink target bytes). Git's
   `core.filemode` and `core.symlinks` policies govern checkout representations.
4. Prefix the aggregate SHA-256 input with `fractallens-working-tree-v1` and a
   newline; publish its digest as `sha256:<digest>`.

File timestamps and staging state do not enter the hash. Staging or committing
identical content preserves identity. Ignored generated files do not enter it;
tracked ignored files still do. Unmerged paths, submodules, unsupported file
types, non-UTF-8 paths, assume-unchanged/skip-worktree flags, and paths escaping
the repository fail closed. The
calculator does not mutate the index or write Git objects.

V1 fingerprints all tracked and nonignored untracked checkout files as the
validation environment; it does not infer a task-file subset. Preserve unrelated
user work and stage only intended task changes. Unrelated build, configuration,
or fixture changes that affect validation require an isolated candidate, as
specified by the workflow skills. Unchanged unrelated files can remain dirty
after delivery; a globally clean checkout is not required. Staging and committing
only the task files preserves the fingerprint.
The guard detects local content/base changes; it does not prove that all intended
task files were committed, that model-reported checks passed, or that a remote PR
exists. The delivery role must check intended staging, local/remote task SHA,
and PR head/base. The guard is not an atomic repository lock.

## Stop behavior and private artifacts

The default timeout is 1,800 seconds per executor. Adjust it with
`--stage-timeout-seconds` (1 through 86,400). A timeout kills the CLI process and
stops the workflow without retrying. This is a process timeout, not a complete
supervisor for every tool subprocess; inspect remaining work before restarting.
There is no automatic resume. A rerun starts at IMPLEMENT; do not blindly rerun
after a partially completed delivery.

Each live run creates a private `fractallens-task-*` directory under the system
temporary directory. Each invocation has a separate numbered directory with
`prompt.txt`, `execution.log`, `report.md`, and an error file when applicable.
The run root contains `result.json`. No artifact directory is granted through
`--add-dir`; the CLI host writes its own final-message file. Prompts prohibit
inspection of other roles' private prompts/logs. This limits supplied context,
but is not a hard read-isolation boundary for processes under the same user.
These files may contain private
task, machine, or diagnostic data. Keep them private; do not commit or upload
them as public evidence. No run artifacts are written into the repository.

Exit code `0` means requested delivery completed, including when reported CI is
pending or failed. It does not mean CI passed or acceptance is complete. Exit
code `1` means `BLOCKED`, `INCOMPLETE`, or an invalid invocation. After DELIVER,
the runner does not poll CI, repair CI failures, or make the human merge decision.

## Local validation

```sh
node --test scripts/test_task_orchestrator.ts
```

Tests use synthetic handoffs and fake CLI processes. They check routing, shared
repair limits, historical finding preservation, compact handoffs, candidate
code-owned identity, per-invocation artifact directories, role/model/effort and
sandbox/approval arguments, strict status parsing, exception messages, timeout, and dry-run
behavior. Temporary Git fixtures check hashing, ignored outputs, staging/commit
invariance, file modes, symlinks, unrelated work preservation, remote base refresh,
frozen comparisons and stopping before delivery when the base moves or is unavailable.
They spend no model tokens and
perform no Git delivery. They do not establish live model availability,
authentication, real role behavior, PR creation, CI results, or desktop access.
This test command is separate from Maven and is not currently wired into CI.

The CLI adapter follows the official
[non-interactive Codex documentation](https://developers.openai.com/codex/noninteractive)
for execution and final-message output, with flags checked against the local
CLI help. Sandbox approvals still apply; v1 does not bypass them to obtain a
successful stage result.
