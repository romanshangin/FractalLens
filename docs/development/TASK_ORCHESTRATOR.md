# Task orchestrator v1

The local TypeScript runner selects workflow stages with a deterministic state
machine. It uses no manager model, runtime dependencies, parallel agents, automatic
merge, or automatic recovery from unknown results. Stages run through the Codex
CLI (default) or Claude Code (`--executor claude`).

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

The state machine, handoffs, repair budget, candidate identity, check harness,
resume and delivery gates are shared; only the stage executor, role models and
the skill binding line of the prompt differ. One run uses one executor for all
stages.

Use `--repo` for another FractalLens checkout, and `--codex` or `--claude` for
an explicit CLI executable. Arguments are passed without a shell. On Windows,
use the native executable rather than a `.cmd` wrapper. Model IDs are passed
exactly as listed below; availability depends on the configured account. There
is no silent model substitution.
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

| Stage | Skill | Codex model | Claude Code model | Reasoning effort | Sandbox | Escalation |
| --- | --- | --- | --- | --- | --- | --- |
| IMPLEMENT / IMPLEMENT_FIXES | `implement-task` | `gpt-6.1-sol` | `claude-opus-5-5` | medium | workspace-write | allowed |
| REVIEW | `review-task` | `gpt-6.1-sol` | `claude-opus-5-5` | medium | read-only | never |
| VERIFY | `verify-task` | `gpt-6-luna` | `claude-sonnet-5-5` | medium | workspace-write | never |
| DELIVER | `deliver-task` | `gpt-6-luna` | `claude-sonnet-5-5` | low | workspace-write | allowed |

Every executor is bound to the shared skill file
`.agents/skills/<skill>/SKILL.md`. Codex prompts use its `$skill` mention;
Claude Code prompts tell the model to read that file in full, because the
`.claude/skills` wrappers for `verify-task` and `deliver-task` disable model
invocation and Claude Code stages run with skills and slash commands disabled.

### Codex permissions

REVIEW and VERIFY receive their explicit `--sandbox` setting and
`approval_policy="never"`, without `--approve-for-me`. IMPLEMENT,
IMPLEMENT_FIXES and DELIVER use `--approve-for-me` to allow approval review.
VERIFY may create normal build, test and diagnostic artifacts in ignored build
directories or outside the repository. It must not modify candidate source,
tests, build configuration, dependencies, documentation or other content that
affects validation. The orchestrator checks candidate identity after VERIFY;
nonignored untracked artifacts also change identity and stop the run.
Checks needing unavailable network, desktop or other access remain `INCOMPLETE`;
VERIFY cannot escalate. Sandbox policies govern local commands; external MCP
servers and connectors have separate controls.

### Claude Code permissions

Every Claude Code stage runs `claude --print --output-format stream-json --verbose` with the
role's `--model` and `--effort`, `--no-session-persistence`,
`--permission-prompts none` (anything that would prompt is denied),
`--disable-slash-commands`, `--disallowedTools Agent` (no subagents) and
`--settings` with `autoMemoryEnabled=false`, so stages neither read nor write
saved Claude Code memories.

- IMPLEMENT, IMPLEMENT_FIXES and DELIVER use `--permission-mode auto`: the
  auto-mode classifier reviews tool calls instead of a person, the equivalent
  of Codex `--approve-for-me`. The user's own sandbox, permission and MCP
  settings apply.
- REVIEW and VERIFY use `--permission-mode dontAsk` with only the `Bash`,
  `Read`, `Grep` and `Glob` tools (no `Edit`, `Write`, `NotebookEdit`, `Agent`
  or web tools) and `--strict-mcp-config`, which loads no MCP servers. Bash runs
  in the Claude Code sandbox with `failIfUnavailable=true` and
  `allowUnsandboxedCommands=false`: a missing sandbox stops the stage instead
  of running unsandboxed, and commands cannot request an unsandboxed retry.
  REVIEW denies sandboxed writes to the repository and the private run
  directory; VERIFY keeps the default write scope (the repository) and may
  also write the private run directory, which holds the check registry and
  logs. Sandboxed network access is limited to configured domains, so checks
  needing other network access remain `INCOMPLETE`, as with Codex.

The Claude Code sandbox is available on macOS, Linux and WSL; on other
platforms REVIEW and VERIFY stop as `INCOMPLETE`. Flag settings combine with
user and managed settings: sandbox `excludedCommands`, allowed domains or
managed policies from those files still apply and are trusted local
configuration. Like the Codex sandbox, this governs local commands, not an
attestation that a stage behaved as instructed.

Each invocation starts a fresh ephemeral CLI session with the task and only the
latest relevant handoffs. The Codex runner reads the final message file; the
Claude Code runner reads only the `result` field of the final stream-json event
on stdout and requires `type=result`, `subtype=success` and `is_error=false`. CLI
progress, stderr and diagnostic output cannot select a state. The first
nonempty line of the report must be a bare role status, such as `CLEAN`,
without Markdown decoration or other text.

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
inside this block. The runner finds the block only by its exact
`delivery-handoff` fence info string, so a block fenced as `json` counts as
missing; the stage prompts state the fence name and show a literal example.
Missing, malformed, duplicate, oversized, or inconsistent
evidence stops the runner as `INCOMPLETE` before DELIVER. With VER skipped,
only REV evidence is required. Full reports remain in private run artifacts.

Every stage report also ends with one fenced `stage-summary` JSON block, which
the runner prints to the console after the stage finishes:

| Stage | Block | Console summary |
| --- | --- | --- |
| IMPLEMENT, IMPLEMENT_FIXES | `{"implemented":["..."],"problems":[{"kind":"blocker\|gap","summary":"..."}]}` | Status, what was implemented and how, blockers and gaps |
| REVIEW | `{"problems":[{"kind":"Critical\|Major\|Minor","summary":"..."}]}` | Status, finding count per severity and each finding, or `no problems found` |
| VERIFY | `{"problems":[{"kind":"failure\|unverified\|environment","summary":"..."}]}` | Status, problem count per kind and each problem, or `no problems found` |
| DELIVER | `{"pr":"<URL or null>","problems":[{"kind":"blocker\|incomplete\|ci","summary":"..."}]}` | Status, problems, PR link |

The block uses the same size limits as `delivery-handoff`, with kinds of at
most 32 characters. It is informational only: a missing, duplicate or malformed
block, including one fenced as `json` instead of `stage-summary`, prints the
status, the expected fence and a pointer to `report.md`, and never changes the
stage result or the workflow. When DELIVER omits `pr`, the first GitHub pull
request URL in its report is shown instead. Control characters in model text
are replaced before printing. A stage whose execution fails prints the failure
message instead.

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

`portable-suite` always runs `./mvnw --batch-mode --no-transfer-progress test`
through the checked-in Maven Wrapper from the repository root, and the
environment probe records `./mvnw --version`.
`orchestrator-tests` runs both TypeScript test files using the current Node
executable. `git-diff-check` runs `git diff --check`. Reserved IDs reject command
overrides. On Windows the built-in Maven command explicitly invokes `mvnw.cmd`
through `cmd.exe` with fixed arguments. Other IDs require an executable and
argument array after `--`; choose
descriptive IDs that identify coverage. Arguments, selectors, profiles and JVM
flags enter the definition hash. Commands run without a shell. On Windows use
native executables (or an explicit interpreter for command scripts); implicit
`.cmd` shell execution for custom commands is not supported.

The harness computes candidate identity before and after execution, captures the
command's exit status and stdout/stderr, and atomically appends an execution record
to `evidence/checks.json` under the private run directory. Each record includes
`checkId`, `candidate`, `checkDefinitionHash`, argument-array `command`,
`environment`, original `stage`, UTC `timestamp`, `result`, `exitCode`, `reusable`,
`summary`, relative `log` path and `logHash`. Logs are private, not injected into
prompts. Maven's final test-count summary retains skipped counts; Node test runs
use the outer reporter totals rather than counts printed by nested diagnostics.
Other commands report exit status and require log inspection for coverage.
An exit-zero check can contain skipped tests; it does not establish that those
tests passed.

A second identical invocation returns `CACHED PASS` and does not execute the
command when the latest matching record is reusable PASS, its log is intact,
and candidate, definition and environment still match. Only actual executions
create records; a cache hit keeps its original stage and time. Changes to base,
merge base, tracked/nonignored candidate content or executable definition
invalidate reuse. Staging or committing identical content and ignored build
output preserve it. Old records remain for diagnosis after fixes but are not
supplied as current evidence. A later failure for the same key blocks an older
PASS. FAIL and INCOMPLETE never skip execution.

Environment comparison is deliberately strict: OS/release, architecture, Node
version/path, Java and Maven version-probe output, command executable bytes and
selected tool/runtime variables are hashed. The variable set covers PATH/PATHEXT,
JAVA/JDK/MAVEN/MVN settings, CLASSPATH, NODE settings, LD/DYLD settings, locale,
timezone and display identifiers. Values and raw version-probe output remain
inside a private digest. Unrelated per-role Codex or Claude Code identifiers do not invalidate
reuse. Environment mismatches cause fresh execution, even for portable checks.

This v1 is a local automated-test cache, not an attestation service. It cannot
infer changing external services, arbitrary environment variables, GPU/driver
state, desktop focus/appearance, benchmark power conditions or dependencies
outside the candidate. Use `--no-reuse` for such checks; the record is retained
but cannot satisfy future runs by cache hit. Manual acceptance continues through
`--human-evidence` and independent assessment, never an automatic cached PASS.
Do not use custom check commands whose meaning depends on untracked external
scripts or mutable ignored inputs unless reuse is disabled. `--fresh` forces a
new execution for suspicious evidence or a concrete diagnostic reason; explain
that reason in the stage report. `--timeout-seconds` accepts 1 through 86,400
(default 1,800). Missing executables, timeout/signal or candidate identity changes
produce INCOMPLETE, never PASS. A nonzero command exit produces FAIL; VERIFY must
still distinguish environment failure from a product defect. Process timeout
does not guarantee that every descendant has stopped; inspect remaining work.

The stages keep distinct responsibilities:

- IMPLEMENT/IMPLEMENT_FIXES run targeted regression checks while developing and
  the required portable/subsystem checks on the final candidate before handoff.
  Edits after a check invalidate it, including documentation or test edits.
- REVIEW reads code, tests and shared execution evidence. It does not repeat a
  valid PASS for general confidence. Narrow diagnostics for a concrete suspected
  finding remain allowed under its existing read-only permissions; their results
  belong in the review report. REVIEW cannot write harness records.
- VERIFY independently maps required acceptance criteria to sufficient current
  execution evidence, executes missing checks and replaces suspicious/insufficient
  evidence. Report reused and freshly executed checks separately. Required UI,
  native/platform or manual gaps still mean INCOMPLETE.
- DELIVER receives the existing compact review/verification handoffs and shared
  check summary; execution evidence cannot replace CLEAN/PASS gates.

The full registry and referenced check logs are explicitly shared between roles;
private role prompts/execution logs remain out of scope. Prompts include at most
16 check summaries of 1,024 characters each; inspect the registry for the rest.
Malformed registries fail closed. Missing/corrupt logs cannot supply reusable
evidence. Concurrent harness writers fail closed using `evidence/active.lock`;
inspect interrupted processes before removing a leftover lock. Evidence is
not supplied to a subsequent stage while a harness execution lock remains active.
Artifacts are created outside the candidate checkout with private permissions and must not be
committed or uploaded. Check contexts/registries are trusted local inputs, not
signed proof against a malicious local writer. Commands executed directly,
outside this harness, are not automatically imported from prose or Maven output.

Resume uses the same private evidence directory after the existing branch/base/
candidate checkpoint guards pass. It does not rerun earlier stages or their
matching automated checks. The existing state format and delivery gates are
unchanged; old checkpoints without a registry have no reusable execution evidence.

## Candidate identity

The orchestrator computes `{baseRevision, mergeBase, contentId}` after each
successful DEV stage and supplies that identity to REV, VER and DELIVER. It
recomputes repository state before and after each independent stage, including
before DELIVER even when VER is skipped. A mismatch or calculation failure
stops as `INCOMPLETE`; mutations already made by delivery are not undone.
Only matching, code-stamped REV/VER evidence can reach delivery.

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

The candidate includes all changes from the frozen base to the checkout. Keep
unrelated user edits out of the candidate branch and stage only intended task
changes. The guard detects local content/base changes; it does not prove that
all intended task files were committed, that model-reported checks passed, or
that a remote PR exists. The delivery role must check intended staging,
local/remote task SHA, and PR head/base. The guard is not an atomic repository
lock.

### Standalone stages

Workflow stages run without the orchestrator use the same
`computeCandidateIdentity` implementation from
`scripts/orchestration/task-orchestrator.ts`, so identities from standalone and
orchestrated stages are directly comparable. Resolve the comparison base to a
commit SHA once, when the first stage records the candidate identity, and pass
that frozen SHA unchanged to every later calculation for the candidate. Later
stages take the SHA from the earlier handoff's `baseRevision` instead of
resolving the base ref again. Run from the repository root with Node.js 24 or
later; the same command line works in POSIX shells and PowerShell once the base
variable is set:

```sh
BASE_REVISION="$(git rev-parse --verify 'main^{commit}')"
node --input-type=module -e "const { computeCandidateIdentity } = await import('./scripts/orchestration/task-orchestrator.ts'); console.log(JSON.stringify(computeCandidateIdentity(process.cwd(), process.argv[1])));" "$BASE_REVISION"
```

Use the explicitly named base instead of `main` when the task names one. The
command only imports the module; it does not start a workflow, mutate the
index or write Git objects. It prints `{baseRevision, mergeBase, contentId}`;
report all three fields. A Git tree ID, `git stash create` output, HEAD SHA,
branch name, or self-computed SHA-256 of files or diffs is not a substitute,
because later stages cannot compare it with this identity. If the calculation
fails, the identity is unavailable: report the stage as `INCOMPLETE` instead of
falling back to another fingerprint. Orchestrated stages do not run this
command; they use the identity supplied and checked by the orchestrator.

## Stop behavior and private artifacts

The default timeout is 30 minutes per executor. Adjust it with
`--stage-timeout-minutes` (a whole number of minutes, 1 through 1,440). A
timeout stops the workflow without retrying. The result reason reports
`Codex stage timed out after <n> minutes` or
`Claude Code stage timed out after <n> minutes`. On macOS and Linux the
timeout stops the executor's whole process tree, including tool commands that
run in their own process groups (Claude Code starts each Bash tool command in
one), and then kills those processes and their groups; on Windows it uses
`taskkill /t`. A tool process that already detached from the tree before the
timeout can still survive; inspect remaining work before restarting.
Commands run through `check.ts` have their own `--timeout-seconds` limit
(default 1,800); a stage that runs a longer check needs both a larger stage
timeout and a larger check timeout.
A new `--task` run starts at IMPLEMENT. Only a valid, identity-checked
`VERIFY=INCOMPLETE` result creates a resumable checkpoint. Executor errors,
timeouts, candidate mutations, incomplete review, and any delivery result do
not create one; inspect private artifacts before deciding how to proceed.

Each live run creates a private `fractallens-task-*` directory under the system
temporary directory. Each invocation has a separate numbered directory with
`prompt.txt`, `execution.log`, `report.md`, and an error file when applicable.
For Claude Code stages `execution.log` receives the stdout stream-json events
as they are produced, so a timed-out or crashed stage still shows its progress
and tool activity; stderr goes to `stderr.log`. The final `result` event,
including usage and cost fields, is also copied to `output.json`.
The run root contains `result.json` and atomically replaced `state.json`. No artifact directory is granted through
`--add-dir`; the Codex CLI host writes its own final-message file and the
Claude Code runner writes `report.md` from the final stdout event. Prompts prohibit
inspection of other roles' private prompts/logs. This limits supplied context,
but is not a hard read-isolation boundary for processes under the same user.
These files may contain private
task, machine, or diagnostic data. Keep them private; do not commit or upload
them as public evidence. No run artifacts are written into the repository.

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

`--resume` alone retries VERIFY without new manual evidence. The evidence file
must be nonempty and at most 65,536 characters. The verifier receives the current
implementation, matching CLEAN review, previous incomplete verification report,
and any saved/new human evidence. It assesses coverage and remaining gaps;
manual evidence does not directly authorize PASS or skip VERIFY. PASS allows
DELIVER, FAIL uses the remaining repair budget and requires fresh review, and
INCOMPLETE saves another checkpoint. Fixes invalidate prior human evidence.
Earlier IMPLEMENT/REVIEW stages are not repeated unless resumed VERIFY finds a
failure requiring fixes.

Resume refreshes the remote base and requires the same base SHA, named branch,
and candidate fingerprint before invoking any model. Changes require fresh
review and verification. Task, repository, base, executor, verification policy
and repair limit cannot be overridden on resume; CLI executable and timeout may
be changed. States written before executor selection resume with Codex.
`--dry-run` displays the resumed plan without fetching, consuming the checkpoint
or invoking models. It does not establish current candidate/base freshness.

Runs use an exclusive `active.lock` directory and consume the old checkpoint
before model execution. Completed delivery, executor errors or unexpected
results cannot replay from that checkpoint. A crash leaves the lock and a
potentially nonresumable state: inspect running processes, repository and
private artifacts before removing the stale lock. Do not edit `state.json` to
bypass a gate. Checkpoints and human evidence are local trusted inputs, not
signed attestations or proof that reported checks actually ran. Keep them private.

## Local validation

```sh
node --test scripts/orchestration/test_task_orchestrator.ts
node --test scripts/orchestration/test_validation_evidence.ts
```

Tests use synthetic handoffs and fake CLI processes. They check routing, shared
repair limits, historical finding preservation, compact handoffs, candidate
code-owned identity, per-invocation artifact directories, role/model/effort and
sandbox/approval arguments for both executors, Claude Code stream-json result parsing,
strict status parsing (trailing whitespace on the
status line is accepted; decorations are not), exception messages, timeout (including
Claude Code tool commands in separate process groups), and dry-run
behavior. Temporary Git fixtures check hashing, ignored outputs, staging/commit
invariance, file modes, symlinks, unrelated work preservation, remote base refresh,
frozen comparisons and stopping before delivery when the base moves or is unavailable.
Resume tests cover persisted history/budget, manual evidence assessment, repeated
pauses, checkpoint consumption, stale source/branch/base rejection and run locks.
Execution-evidence tests run real synthetic commands and check cache hits across
stages/resume, candidate/definition/environment invalidation, failure supersession,
log integrity, unavailable execution, timeout, mutation and read-only boundaries.
They spend no model tokens and
perform no Git delivery. They do not establish live model availability,
authentication, real role behavior, PR creation, CI results, or desktop access.
This test command is separate from Maven and is not currently wired into CI.

The Codex adapter follows the official
[non-interactive Codex documentation](https://developers.openai.com/codex/noninteractive)
for execution and final-message output. The Claude Code adapter follows the
[headless mode](https://code.claude.com/docs/en/headless) and
[sandboxing](https://code.claude.com/docs/en/sandboxing) documentation. Flags
were checked against the local CLI help (Claude Code 2.1.311). Sandbox and
permission approvals still apply; v1 does not bypass them to obtain a
successful stage result.
