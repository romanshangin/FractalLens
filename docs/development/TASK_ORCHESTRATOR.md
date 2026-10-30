# Task orchestrator v1

Local TypeScript deterministic state machine, executed by Codex CLI (default)
or Claude Code (`--executor claude`). No manager model, runtime dependencies,
parallel agents, automatic merge or unknown-result recovery.

## Requirements and launch

Use Node.js 24 or later (native TypeScript type stripping), Git, and one
authenticated stage executor:

- Codex CLI (default, `--executor codex`) that supports `exec`,
  `--approve-for-me`, `--ephemeral`, and `--output-last-message`;
- Claude Code CLI (`--executor claude`) that supports `--print`,
  `--output-format stream-json` with `--verbose`, `--effort`, `--permission-mode auto|dontAsk`,
  `--permission-prompts none`, `--no-session-persistence`, `--tools`,
  `--strict-mcp-config`, `--disable-slash-commands` and `--settings`. Auto mode
  must be available to the configured account.

GitHub access is needed by the delivery executor. The application build and
Maven dependencies are unchanged.

For TypeScript checking and IntelliJ IDEA editor support, install the locked
development-only Node.js type definitions:

```sh
npm ci --prefix scripts/orchestration
```

`scripts/orchestration/tsconfig.json` explicitly loads the `node` types and uses NodeNext
module resolution, strict checking, and no emitted JavaScript. The scripts
package declares ES modules so `import.meta` and native `.ts` imports match
their runtime behavior. Configure IDEA to use this TypeScript project.
Type definitions are only needed for editing/type checking, not runtime launch.

Inspect the plan without running models or publishing anything:

```sh
node scripts/orchestration/task-orchestrator.ts --task "P2.3 - Task description" --dry-run
```

Execute the workflow:

```sh
node scripts/orchestration/task-orchestrator.ts --task "P2.3 - Task description"
```

Executing the workflow authorizes standard delivery of the validated task:
commit intended changes when needed, push the task branch, and create or update
its PR. It never authorizes force-push, merge, close, or automatic merge.
Implementation establishes or reuses the task branch according to `AGENTS.md`;
delivery does not create or reorganize branches. Run only one workflow at a time
in a checkout and preserve unrelated user-owned work.

Execute the same workflow with Claude Code stage executors:

```sh
node scripts/orchestration/task-orchestrator.ts --executor claude --task "P2.3 - Task description"
```

Both executors share state machine, handoffs, repair budget, candidate identity,
check harness, resume/delivery gates. Only executor, role models and prompt skill
binding differ; each run uses one executor throughout.

Options: `--repo` selects another checkout; `--codex`/`--claude` selects the CLI.
Arguments bypass shells; Windows needs native executables, not `.cmd` wrappers.
Model IDs are passed exactly as listed, subject to account availability; no silent
substitution. `--base` selects the remote-tracking PR base (default `origin/main`).
Refresh `origin/release-2`/`refs/remotes/origin/release-2` from their configured remote
before models; roles receive original ref, normalized PR branch (`release-2`) and
frozen SHA. Local branches (`main`, `refs/heads/main`), commits and tags are rejected
before stages: they cannot prove remote freshness. Use `origin/main`,
`origin/release-2` or `upstream/main`. Missing base/failed refresh stops before models.
Dry-run neither fetches nor mutates.

Freeze base SHA through implementation/review/verification. Before DELIVER,
refresh again; changed/unavailable SHA stops `INCOMPLETE` before executor launch.
Refresh timeout: 30 seconds. This pre-publication check does not lock the remote,
which can advance afterward.

## Roles and transitions

| Stage | Skill | Codex model | Claude Code model | Reasoning effort | Sandbox | Escalation |
| --- | --- | --- | --- | --- | --- | --- |
| IMPLEMENT / IMPLEMENT_FIXES | `implement-task` | `gpt-6.1-sol` | `claude-opus-5-5` | medium | workspace-write | allowed |
| REVIEW | `review-task` | `gpt-6.1-sol` | `claude-opus-5-5` | medium | read-only | never |
| VERIFY | `verify-task` | `gpt-6-luna` | `claude-sonnet-5-5` | medium | workspace-write | never |
| DELIVER | `deliver-task` | `gpt-6-luna` | `claude-sonnet-5-5` | low | workspace-write | allowed |

Executors bind `.agents/skills/<skill>/SKILL.md`: Codex via `$skill`, Claude via
explicit full-file reading. Claude disables skills/slash commands; `.claude/skills`
`verify-task`/`deliver-task` wrappers disable model invocation.

### Codex permissions

REVIEW/VERIFY use explicit `--sandbox`, `approval_policy="never"`, no
`--approve-for-me`; IMPLEMENT/IMPLEMENT_FIXES/DELIVER use approval review via
`--approve-for-me`. VERIFY may write build/test/diagnostic output only in ignored
build paths or outside the repository, never candidate source, tests, build settings,
dependencies, docs or validation-affecting content. Post-VERIFY identity checks
stop on mutations, including nonignored untracked artifacts. Unavailable network/
desktop/access leaves checks `INCOMPLETE`; VERIFY cannot escalate. Sandbox governs
local commands; external MCP/connectors have separate controls.

### Claude Code permissions

All Claude stages use `claude --print --output-format stream-json --verbose`,
role `--model`/`--effort`, `--no-session-persistence`, `--permission-prompts none`
(deny prompts), `--disable-slash-commands`, `--disallowedTools Agent` (no subagents),
`--settings` with `autoMemoryEnabled=false` (no saved-memory reads/writes).

- IMPLEMENT/IMPLEMENT_FIXES/DELIVER: `--permission-mode auto`, classifier review
  equivalent to Codex `--approve-for-me`; user sandbox/permission/MCP settings apply.
- REVIEW/VERIFY: `--permission-mode dontAsk`; only `Bash`, `Read`, `Grep`, `Glob`,
  no `Edit`, `Write`, `NotebookEdit`, `Agent` or web. `--strict-mcp-config` loads
  no MCP. Bash sandbox requires `failIfUnavailable=true`,
  `allowUnsandboxedCommands=false`: no missing-sandbox bypass/unsandboxed retry.
  REVIEW cannot write repository/private run directory; VERIFY may write default
  repository scope and private run registry/logs. Network is limited to configured
  domains; other required access leaves checks `INCOMPLETE`, like Codex.

The Claude Code sandbox is available on macOS, Linux and WSL; on other
platforms REVIEW and VERIFY stop as `INCOMPLETE`. Flag settings combine with
user and managed settings: sandbox `excludedCommands`, allowed domains or
managed policies from those files still apply and are trusted local
configuration. Like the Codex sandbox, this governs local commands, not an
attestation that a stage behaved as instructed.

Each invocation is ephemeral with task/latest relevant handoffs only. Codex reads
its final-message file; Claude reads the last stdout `type=result` event's `result`,
requiring `subtype=success`, `is_error=false`. Only `command_lifecycle` may follow;
other trailing events/non-JSON lines mean incomplete execution. Progress/stderr/
diagnostics never select state. First nonempty report line must be a bare role
status such as `CLEAN`, without Markdown or extra text.

Handoffs contain only the evidence needed by the next role:

| Invocation | Supplied evidence |
| --- | --- |
| Initial IMPLEMENT | Task only |
| Initial REVIEW | Latest implementation report |
| IMPLEMENT_FIXES | Latest REVIEW `FINDINGS` or VERIFY `FAIL` report |
| REVIEW after fixes | Latest implementation and previous review; also the failure report for VER-driven fixes |
| VERIFY | Latest implementation and current `CLEAN` review |
| DELIVER | Current REV/VER statuses and compact candidate/check summaries; no DEV report or full REV/VER text |

Re-review covers the complete current diff and closure of prior findings/failures.
Historical evidence is no new `CLEAN`/`PASS`. Older repairs stay privately archived,
not accumulated in prompts; every invocation receives the configured repair limit.

Passing REV/VER reports must include exactly one fenced `delivery-handoff`
JSON block, in addition to their normal report:

```delivery-handoff
{"checks":["<concise command/result/coverage>"],"limitations":[]}
```

Model supplies checks/limitations only; candidate fields are rejected. Limits:
6,000 characters, each array ≤16 nonempty strings ≤512 characters. `checks` must
be nonempty; `limitations` may be empty. No raw logs/full reasoning. Only exact
`delivery-handoff` fence counts (`json` means missing); prompts name/show it.
Missing/malformed/duplicate/oversized/inconsistent evidence stops `INCOMPLETE`
before DELIVER. Skipped VER needs REV evidence only. Full reports stay private.

Every stage report also ends with one fenced `stage-summary` JSON block, which
the runner prints to the console after the stage finishes:

| Stage | Block | Console summary |
| --- | --- | --- |
| IMPLEMENT, IMPLEMENT_FIXES | `{"implemented":["..."],"problems":[{"kind":"blocker\|gap","summary":"..."}]}` | Status, what was implemented and how, blockers and gaps |
| REVIEW | `{"problems":[{"kind":"Critical\|Major\|Minor","summary":"..."}]}` | Status, finding count per severity and each finding, or `no problems found` |
| VERIFY | `{"problems":[{"kind":"failure\|unverified\|environment","summary":"..."}]}` | Status, problem count per kind and each problem, or `no problems found` |
| DELIVER | `{"pr":"<URL or null>","problems":[{"kind":"blocker\|incomplete\|ci","summary":"..."}]}` | Status, problems, PR link |

Same limits as `delivery-handoff`; kinds ≤32 characters. Informational only:
missing/duplicate/malformed `stage-summary` (including a `json` fence) prints status,
expected fence and `report.md` pointer without changing results/workflow. Missing
DELIVER `pr` falls back to its first GitHub PR URL. Replace model control characters
before printing; failed execution prints its failure message instead.

| Stage result | Next step |
| --- | --- |
| IMPLEMENT `READY_FOR_REVIEW` | REVIEW |
| IMPLEMENT `BLOCKED` | STOP |
| REVIEW `FINDINGS` | IMPLEMENT_FIXES, then fresh REVIEW |
| REVIEW `CLEAN` | VERIFY, or DELIVER under explicit skip policy |
| REVIEW `INCOMPLETE` | STOP |
| VERIFY `FAIL` | IMPLEMENT_FIXES, then fresh REVIEW and VERIFY |
| VERIFY `PASS` | DELIVER |
| VERIFY `INCOMPLETE` | Save human verification checkpoint; STOP; explicit resume starts at VERIFY |
| Any DELIVER status | STOP; report PR and CI state |
| Missing/invalid status, executor error, or timeout | STOP as `INCOMPLETE` |

At most three repair rounds are allowed across review and verification
failures combined. `--max-repair-rounds 0`, `1`, or `2` can lower that limit;
values above three are rejected. Exceeding the limit returns `BLOCKED` with a
review or verification repair-limit reason. Repairs invalidate old handoffs;
a prior `CLEAN` or `PASS` cannot bypass fresh review after a fix.

`READY_FOR_REVIEW`: implementation and required developer self-validation complete,
not full acceptance. Unavailable independent verification/manual UI acceptance is
a gap, not an IMPLEMENT/IMPLEMENT_FIXES blocker unless `AGENTS.md`/task explicitly
makes it an implementation gate. Developer checks remain mandatory.
`verificationRequired` only routes REVIEW `CLEAN` to VERIFY or DELIVER. Missing
required VERIFY acceptance yields `INCOMPLETE`, stopping delivery.

Independent verification is required by default. Only explicit caller policy
may skip the stage:

```sh
node scripts/orchestration/task-orchestrator.ts --task "Task description" --skip-verification
```

This passes `verificationRequired=false` to all executors and the delivery
gate. It does not waive developer self-validation, project tests, or acceptance
criteria. The caller must ensure the task permits skipping independent VER;
v1 does not classify risk or decide whether verification is required.

## Automated check evidence and reuse

Each stage receives a private `check-context.json` path and a compact summary of
current automated execution evidence. Run developer self-validation and missing
verification checks through the command shown in the stage prompt:

```sh
node scripts/orchestration/check.ts --context "${CHECK_CONTEXT}" --check portable-suite
node scripts/orchestration/check.ts --context "${CHECK_CONTEXT}" --check orchestrator-tests
node scripts/orchestration/check.ts --context "${CHECK_CONTEXT}" --check git-diff-check
node scripts/orchestration/check.ts --context "${CHECK_CONTEXT}" --check scene-history-targeted \
  -- ./mvnw --batch-mode --no-transfer-progress -Dtest=SceneHistoryTest test
```

`portable-suite`: repository-root checked-in Wrapper
`./mvnw --batch-mode --no-transfer-progress test`; environment probe `./mvnw --version`.
`orchestrator-tests`: both TypeScript test files, current Node. `git-diff-check`:
`git diff --check`. Reserved IDs reject overrides. Windows built-in Maven uses
`mvnw.cmd` through `cmd.exe` with fixed arguments. Other IDs need descriptive
coverage names and executable/argument array after `--`; arguments, selectors,
profiles/JVM flags enter definition hash. No shell: Windows custom commands need
native executables or explicit script interpreters, never implicit `.cmd` execution.

Harness checks identity before/after execution, captures exit/stdout/stderr and
atomically appends private `evidence/checks.json`. Fields: `checkId`, `candidate`,
`checkDefinitionHash`, argument-array `command`, `environment`, original `stage`,
UTC `timestamp`, `result`, `exitCode`, `reusable`, `summary`, relative `log`,
`logHash`. Logs stay private/out of prompts. Maven totals retain skips; Node uses
outer reporter totals, not nested diagnostics. Other commands need log coverage
inspection. Exit zero with skips does not pass skipped tests.

Identical calls return `CACHED PASS` without execution only for the latest matching
reusable PASS, intact log and unchanged candidate/definition/environment. Only
execution creates records; cache hits retain stage/time. Base/merge-base/content
(tracked or nonignored)/executable-definition changes invalidate reuse; staging/
committing identical content and ignored outputs do not. Old records remain for
diagnosis, not current evidence. Later same-key failure supersedes PASS;
FAIL/INCOMPLETE never skip execution.

Strict environment hash: OS/release, architecture, Node version/path, Java/Maven
probe output, executable bytes, PATH/PATHEXT, JAVA/JDK/MAVEN/MVN, CLASSPATH, NODE,
LD/DYLD, locale, timezone/display identifiers. Values/raw probe output stay inside
a private digest. Unrelated role-specific Codex/Claude IDs do not invalidate reuse;
any compared environment mismatch reruns even portable checks.

v1 is a local test cache, not attestation. External services, arbitrary variables,
GPU/driver state, desktop focus/appearance, benchmark power and external dependencies
need `--no-reuse`: retain execution, forbid future cache hits. Manual acceptance
uses `--human-evidence` plus independent assessment, never cached PASS. Disable
reuse for custom commands depending on untracked external scripts/mutable ignored
inputs. `--fresh` reruns suspicious evidence or concrete diagnostics; explain why
in the report. `--timeout-seconds`: 1–86,400, default 1,800. Missing executables,
timeout/signal/identity mutation yield INCOMPLETE, never PASS; nonzero exit yields
FAIL, still requiring VERIFY to distinguish environment/product causes. Timeout
may leave descendants running; inspect them.

The stages keep distinct responsibilities:

- IMPLEMENT/IMPLEMENT_FIXES: targeted development checks plus required final
  portable/subsystem checks. Later edits, including docs/tests, invalidate evidence.
- REVIEW: inspect code/tests/shared evidence; do not repeat valid PASS for confidence.
  Read-only diagnostics for concrete suspected findings are allowed; report them.
  REVIEW cannot write harness records.
- VERIFY: independently map acceptance to current evidence, run missing checks,
  replace suspicious/insufficient evidence; separately report reused/new checks.
  Required UI/native/platform/manual gaps still yield INCOMPLETE.
- DELIVER: use compact review/verification handoffs and check summary; execution
  evidence never replaces CLEAN/PASS gates.

Roles share the full registry/check logs, not private prompts/execution logs.
Prompts contain ≤16 summaries, each ≤1,024 characters; inspect registry for more.
Malformed registries, corrupt/missing logs and concurrent `evidence/active.lock`
writers fail closed. Inspect interrupted processes before stale-lock removal;
no evidence passes to later stages with an active lock. Artifacts live outside
candidate with private permissions; never commit/upload. Contexts/registries are
trusted local inputs, not signed proof against malicious writers. Direct commands
outside the harness are not imported from prose/Maven output.

Resume reuses the private evidence directory after branch/base/candidate guards,
without rerunning earlier stages/matching checks. State format/delivery gates are
unchanged; older checkpoints without registries have no reusable evidence.

## Candidate identity

After successful DEV, compute `{baseRevision, mergeBase, contentId}` for REV/VER/
DELIVER. Recheck before/after independent stages and before DELIVER even if VER is
skipped. Mismatch/calculation failure stops `INCOMPLETE`; delivery mutations are
not undone. Only matching code-stamped REV/VER evidence reaches delivery.

One deterministic algorithm covers tracked paths changed against the frozen base
and nonignored untracked paths:

1. Use the frozen base commit and compute its merge base with HEAD.
2. Enumerate tracked paths changed from the frozen base to the checkout, plus
   nonignored untracked paths, with Git's NUL-delimited output; sort decoded
   UTF-8 paths in JavaScript string order. Disable rename detection so that
   renamed paths are represented as a deletion and an addition.
3. For each present file, hash a newline-terminated JSON record of its
   repository-relative path, Git mode, and SHA-256 of bytes (or symlink target
   bytes). Git's
   `core.filemode` and `core.symlinks` policies govern checkout representations.
   Record deleted changed paths explicitly.
4. Prefix the aggregate SHA-256 input with `fractallens-working-tree-v2` and a
   newline; publish its digest as `sha256:<digest>`.

File timestamps and staging state do not enter the hash. Staging or committing
identical content preserves identity. Ignored generated files do not enter it;
unchanged tracked files outside the candidate diff do not enter it. Unmerged
paths, submodules, unsupported file types, non-UTF-8 paths,
assume-unchanged/skip-worktree flags, and paths escaping the repository fail
closed. The calculator does not mutate the index or write Git objects.

Candidate includes every change from frozen base. Exclude unrelated user edits
from its branch; stage intended task files only. Guard detects local content/base
changes, not complete staging, real test execution or remote PR existence.
DELIVER must verify staging, local/remote task SHA and PR head/base. No atomic
repository lock is provided.

### Standalone stages

Standalone stages share `computeCandidateIdentity` in
`scripts/orchestration/task-orchestrator.ts`, yielding comparable identities.
Resolve base once at first identity; later calculations reuse the handoff's frozen
`baseRevision`, never re-resolve its ref. Run from repository root with Node.js 24+;
after setting base, the command works in POSIX and PowerShell:

```sh
BASE_REVISION="$(git rev-parse --verify 'main^{commit}')"
node --input-type=module -e "const { computeCandidateIdentity } = await import('./scripts/orchestration/task-orchestrator.ts'); console.log(JSON.stringify(computeCandidateIdentity(process.cwd(), process.argv[1])));" "$BASE_REVISION"
```

Use the task's explicit base instead of `main`. Importing this command starts no
workflow, changes no index and writes no Git objects; report all
`{baseRevision, mergeBase, contentId}` fields. Tree IDs, `git stash create`, HEAD,
branch names or custom file/diff SHA-256 are not comparable substitutes. Calculation
failure means unavailable identity/`INCOMPLETE`, never another fingerprint.
Orchestrated stages use the supplied/checked identity, not this command.

## Stop behavior and private artifacts

Executor timeout defaults to 30 minutes; `--stage-timeout-minutes` accepts whole
minutes 1–1,440. No retry; reasons are `Codex stage timed out after <n> minutes`
or `Claude Code stage timed out after <n> minutes`. macOS/Linux stop then kill the
whole executor tree and groups, including separate Bash tool groups; Windows uses
`taskkill /t`. Already-detached tools can survive: inspect before restart.
`check.ts` has independent `--timeout-seconds` (default 1,800); long checks require
both limits increased. New `--task` starts IMPLEMENT. Only valid identity-checked
`VERIFY=INCOMPLETE` creates a checkpoint; executor errors/timeouts/mutations,
incomplete review or any delivery result do not. Inspect private artifacts first.

Runs create private system-temp `fractallens-task-*`, with numbered invocation
folders: `prompt.txt`, `execution.log`, `report.md`, applicable error file. Claude
streams stdout JSON into `execution.log`, retaining crash/timeout progress/tools;
stderr → `stderr.log`; final `result` (usage/cost included) → `output.json`.
Root has `result.json` and atomically replaced `state.json`. No `--add-dir` artifact
grant: Codex host writes final-message file; Claude runner derives `report.md`
from final stdout. Prompts forbid reading other roles' private logs/prompts; this
limits context, not same-user read access. Artifacts may contain private task,
machine/diagnostic data: keep private, never commit/upload as evidence. None are
written inside the repository.

Exit code `0` means requested delivery completed, including when reported CI is
pending or failed. It does not mean CI passed or acceptance is complete. Exit
code `1` means `BLOCKED`, `INCOMPLETE`, or an invalid invocation. After DELIVER,
the runner does not poll CI, repair CI failures, or make the human merge decision.

## Resume and human verification

After VERIFY reports `INCOMPLETE`, the run's private `state.json` retains the
task, repository and branch, frozen remote base, executor, verification policy,
shared repair budget, complete stage history, candidate identity and `nextStage=VERIFY`.
For example, a review repair followed by missing UI acceptance retains:

```text
IMPLEMENT       READY_FOR_REVIEW
REVIEW          FINDINGS
IMPLEMENT_FIXES READY_FOR_REVIEW
REVIEW          CLEAN
VERIFY          INCOMPLETE
repairRounds = 1
nextStage = VERIFY
```

Perform the missing manual acceptance on that exact candidate. Save a private
UTF-8 evidence file outside the candidate (or in ignored output), describing
which checks ran, their results, platform/environment, and remaining gaps. Then:

```sh
node scripts/orchestration/task-orchestrator.ts --resume "${TEMP_DIR}/fractallens-task-example" \
  --human-evidence "${TEMP_DIR}/manual-acceptance.md"
```

`--resume` alone retries VERIFY without new human evidence. Evidence must be
nonempty, ≤65,536 characters. Verifier receives current implementation, matching
CLEAN review, prior incomplete report and saved/new human evidence, then assesses
coverage/gaps; human evidence neither directly grants PASS nor skips VERIFY.
PASS → DELIVER; FAIL → remaining repair budget/fresh review; INCOMPLETE → another
checkpoint. Fixes invalidate human evidence. IMPLEMENT/REVIEW repeat only if
resumed VERIFY finds a failure needing fixes.

Before models, resume refreshes/requires unchanged remote base SHA, named branch
and fingerprint; changes need fresh review/verification. Task/repository/base/
executor/verification policy/repair limit cannot change; CLI executable/timeout
may. Pre-executor-selection states resume on Codex. `--dry-run` only displays plan,
without fetch/checkpoint consumption/models; it does not establish current base or candidate freshness.

Exclusive `active.lock` prevents concurrent runs; consume checkpoint before models.
Delivery completion, executor errors or unexpected results cannot replay it.
Crash may leave lock/nonresumable state: inspect processes/repository/artifacts
before removal. Never edit `state.json` around gates. Checkpoints/human evidence
are private trusted local inputs, not signed attestations/proof of execution.

## Local validation

```sh
node --test scripts/orchestration/test_task_orchestrator.ts
node --test scripts/orchestration/test_validation_evidence.ts
```

Synthetic handoffs/fake CLIs test routing, shared repair limits, prior findings,
compact handoffs, code-owned identities, invocation artifacts, both executors'
role/model/effort/sandbox/approval arguments, Claude stream-json parsing, bare
statuses (trailing whitespace accepted, decorations rejected), exceptions,
timeouts including separate Bash groups and dry-run. Temporary Git fixtures cover
hashing, ignored outputs, staging/commit invariance, modes/symlinks, user-work
preservation, remote refresh/frozen comparisons and pre-delivery base movement/
unavailability. Resume covers history/budget, human evidence, repeated pauses,
checkpoint consumption, stale source/branch/base and locks. Real synthetic-command
evidence tests cover cross-stage/resume hits, candidate/definition/environment
invalidation, failure supersession, log integrity, unavailable execution, timeout,
mutation/read-only bounds. No model tokens/Git delivery; no proof of live model
availability, authentication, real role behavior, PR/CI or desktop access.
Tests are separate from Maven and currently not in CI.

The Codex adapter follows the official
[non-interactive Codex documentation](https://developers.openai.com/codex/noninteractive)
for execution and final-message output. The Claude Code adapter follows the
[headless mode](https://code.claude.com/docs/en/headless) and
[sandboxing](https://code.claude.com/docs/en/sandboxing) documentation. Flags
were checked against the local CLI help (Claude Code 2.1.311). Sandbox and
permission approvals still apply; v1 does not bypass them to obtain a
successful stage result.
