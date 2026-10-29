import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, lstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, readlinkSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { currentEvidence, evidenceSummary, type CheckEvidence } from './validation-evidence.ts';

export type Stage = 'IMPLEMENT' | 'IMPLEMENT_FIXES' | 'REVIEW' | 'VERIFY' | 'DELIVER';
export type Executor = 'codex' | 'claude';
export type FinalStatus = 'DELIVERED' | 'BLOCKED' | 'INCOMPLETE';
export type CandidateIdentity = Readonly<{ baseRevision: string; mergeBase: string; contentId: string }>;
export type Handoff = { stage: Stage; status: string; report: string; candidate?: CandidateIdentity };
export type Request = {
  stage: Stage;
  task: string;
  baseRef: string;
  prBase: string;
  baseRevision: string;
  verificationRequired: boolean;
  repairRound: number;
  maxRepairRounds: number;
  handoffs: Handoff[];
  candidate?: CandidateIdentity;
  humanEvidence?: string;
  checkContext?: string;
  evidence?: CheckEvidence[];
};
export type Checkpoint = {
  candidate: CandidateIdentity;
  repairRounds: number;
  nextStage: 'VERIFY';
  history: Handoff[];
  humanEvidence?: string;
};
export type WorkflowResult = {
  status: FinalStatus;
  reason: string;
  repairRounds: number;
  history: Handoff[];
  checkpoint?: Checkpoint;
};

export const ROLES = {
  IMPLEMENT: { skill: 'implement-task', model: 'gpt-6.1-sol', effort: 'medium', sandbox: 'workspace-write', escalation: 'allowed', statuses: ['READY_FOR_REVIEW', 'BLOCKED'] },
  IMPLEMENT_FIXES: { skill: 'implement-task', model: 'gpt-6.1-sol', effort: 'medium', sandbox: 'workspace-write', escalation: 'allowed', statuses: ['READY_FOR_REVIEW', 'BLOCKED'] },
  REVIEW: { skill: 'review-task', model: 'gpt-6.1-sol', effort: 'medium', sandbox: 'read-only', escalation: 'never', statuses: ['CLEAN', 'FINDINGS', 'INCOMPLETE'] },
  VERIFY: { skill: 'verify-task', model: 'gpt-6-luna', effort: 'medium', sandbox: 'workspace-write', escalation: 'never', statuses: ['PASS', 'FAIL', 'INCOMPLETE'] },
  DELIVER: { skill: 'deliver-task', model: 'gpt-6-luna', effort: 'low', sandbox: 'workspace-write', escalation: 'allowed', statuses: ['DELIVERED', 'BLOCKED', 'INCOMPLETE'] },
} as const;

// Claude Code roles keep the skill, status, sandbox and escalation policy of ROLES.
export const CLAUDE_MODELS = {
  IMPLEMENT: { model: 'claude-opus-5-5', effort: 'medium' },
  IMPLEMENT_FIXES: { model: 'claude-opus-5-5', effort: 'medium' },
  REVIEW: { model: 'claude-opus-5-5', effort: 'medium' },
  VERIFY: { model: 'claude-sonnet-5-5', effort: 'medium' },
  DELIVER: { model: 'claude-sonnet-5-5', effort: 'low' },
} as const satisfies Record<Stage, { model: string; effort: string }>;

export type Role = Omit<(typeof ROLES)[Stage], 'model' | 'effort'> & { readonly model: string; readonly effort: string };

export function roleFor(executor: Executor, stage: Stage): Role {
  return executor === 'claude' ? { ...ROLES[stage], ...CLAUDE_MODELS[stage] } : ROLES[stage];
}

function isExecutor(value: unknown): value is Executor {
  return value === 'codex' || value === 'claude';
}

export function prepareBase(repository: string, baseRef: string): {
  baseRef: string;
  prBase: string;
  baseRevision: string;
  assertFresh: () => void;
} {
  const git = (...args: string[]): string => {
    const result = spawnSync('git', args, { cwd: repository, encoding: 'utf8', timeout: 30_000, killSignal: 'SIGKILL' });
    if (result.error || result.status !== 0) throw new Error(`Cannot resolve or refresh intended PR base: ${baseRef}`);
    return result.stdout.trim();
  };
  const ref = git('rev-parse', '--symbolic-full-name', '--verify', '--end-of-options', baseRef);
  if (!ref.startsWith('refs/remotes/')) {
    throw new Error('--base must name a remote-tracking branch, such as origin/main; local branches, tags and commit IDs are unsupported');
  }
  const name = ref.slice('refs/remotes/'.length);
  const remote = git('remote').split('\n').filter(item => item && name.startsWith(`${item}/`))
    .sort((a, b) => b.length - a.length)[0];
  if (!remote) throw new Error(`No configured remote for PR base: ${baseRef}`);
  const prBase = name.slice(remote.length + 1);
  git('check-ref-format', `refs/heads/${prBase}`);
  const refresh = (): string => {
    git('fetch', '--no-tags', '--no-write-fetch-head', '--', remote, `+refs/heads/${prBase}:${ref}`);
    return git('rev-parse', '--verify', '--end-of-options', `${ref}^{commit}`);
  };
  const baseRevision = refresh();
  return { baseRef, prBase, baseRevision, assertFresh: () => {
    if (refresh() !== baseRevision) throw new Error('Intended PR base changed since workflow start; fresh review and verification are required');
  } };
}

export function computeCandidateIdentity(repository: string, base = 'origin/main'): CandidateIdentity {
  const git = (...args: string[]): Buffer => {
    const result = spawnSync('git', ['--no-optional-locks', ...args], { cwd: repository, maxBuffer: 64 * 1024 * 1024 });
    if (result.error || result.status !== 0) throw new Error('Cannot establish Git candidate state');
    return result.stdout;
  };
  const baseRevision = git('rev-parse', '--verify', '--end-of-options', `${base}^{commit}`).toString().trim();
  const mergeBase = git('merge-base', baseRevision, 'HEAD').toString().trim();
  const decodePaths = (bytes: Buffer): string => {
    const decoded = bytes.toString('utf8');
    if (!Buffer.from(decoded).equals(bytes)) throw new Error('Non-UTF-8 candidate paths are unsupported');
    return decoded;
  };
  const tracked = new Map<string, string>();
  if (decodePaths(git('ls-files', '-v', '-z')).split('\0').some(entry => /^[a-zS] /.test(entry))) {
    throw new Error('Assume-unchanged and skip-worktree paths are unsupported candidates');
  }
  const stageEntries = decodePaths(git('ls-files', '--stage', '-z')).split('\0').filter(Boolean);
  for (const entry of stageEntries) {
    const separator = entry.indexOf('\t');
    const [mode, , stage] = entry.slice(0, separator).split(' ');
    if (stage !== '0' || mode === '160000') throw new Error('Unmerged paths and submodules are unsupported candidates');
    tracked.set(entry.slice(separator + 1), mode);
  }
  const changedTrackedPaths = decodePaths(git('diff', '--no-renames', '--name-only', '-z', baseRevision)).split('\0').filter(Boolean);
  const paths = new Set([...changedTrackedPaths, ...decodePaths(git('ls-files', '--others', '--exclude-standard', '-z')).split('\0').filter(Boolean)]);
  const fileMode = spawnSync('git', ['config', '--bool', '--get', 'core.filemode'], { cwd: repository, encoding: 'utf8' });
  if (fileMode.error || (fileMode.status !== 0 && fileMode.status !== 1)) throw new Error('Cannot establish Git file-mode policy');
  const observeExecutable = fileMode.stdout.trim() !== 'false';
  const symlinkPolicy = spawnSync('git', ['config', '--bool', '--get', 'core.symlinks'], { cwd: repository, encoding: 'utf8' });
  if (symlinkPolicy.error || (symlinkPolicy.status !== 0 && symlinkPolicy.status !== 1)) throw new Error('Cannot establish Git symlink policy');
  const root = realpathSync(repository);
  const hash = createHash('sha256').update('fractallens-working-tree-v2\n');
  for (const path of [...paths].sort()) {
    const file = join(root, path);
    let stat;
    try { stat = lstatSync(file); }
    catch (error) {
      if (['ENOENT', 'ENOTDIR'].includes((error as NodeJS.ErrnoException).code ?? '')) {
        hash.update(JSON.stringify([path, 'deleted']) + '\n');
        continue;
      }
      throw error;
    }
    const parent = relative(root, realpathSync(dirname(file)));
    if (isAbsolute(parent) || parent === '..' || parent.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`)) {
      throw new Error('Candidate path escapes repository');
    }
    if (!stat.isFile() && !stat.isSymbolicLink()) throw new Error('Unsupported candidate file type');
    const mode = stat.isSymbolicLink() || (symlinkPolicy.stdout.trim() === 'false' && tracked.get(path) === '120000') ? '120000'
      : observeExecutable ? (stat.mode & 0o111 ? '100755' : '100644') : tracked.get(path) ?? '100644';
    const bytes = stat.isSymbolicLink() ? readlinkSync(file, { encoding: 'buffer' }) : readFileSync(file);
    hash.update(JSON.stringify([path, mode, createHash('sha256').update(bytes).digest('hex')]) + '\n');
  }
  return Object.freeze({ baseRevision, mergeBase, contentId: `sha256:${hash.digest('hex')}` });
}

function sameCandidate(a: CandidateIdentity | undefined, b: CandidateIdentity): boolean {
  return a?.baseRevision === b.baseRevision && a.mergeBase === b.mergeBase && a.contentId === b.contentId;
}

export function parseStatus(stage: Stage, report: string): string {
  // Trailing whitespace is formatting noise; decorations and extra words still fail closed.
  const status = report.trim().split(/\r?\n/, 1)[0].trimEnd();
  if (!(ROLES[stage].statuses as readonly string[]).includes(status)) {
    throw new Error(`${stage} returned no valid first-line status`);
  }
  return status;
}

function compactHandoff(handoff: Handoff): Handoff {
  const blocks = [...handoff.report.matchAll(/^```delivery-handoff\s*\r?\n([\s\S]*?)^```\s*$/gm)];
  if (blocks.length !== 1 || blocks[0][1].length > 6000) throw new Error('Missing or oversized delivery handoff (expected exactly one ```delivery-handoff fenced block)');
  const data = JSON.parse(blocks[0][1]);
  const summaries = (value: unknown): value is string[] => Array.isArray(value) && value.length <= 16
    && value.every(item => typeof item === 'string' && item.trim().length > 0 && item.length <= 512);
  if (!data || Object.keys(data).some(key => key !== 'checks' && key !== 'limitations')
      || !summaries(data.checks) || data.checks.length === 0 || !summaries(data.limitations)) {
    throw new Error('Invalid delivery handoff');
  }
  return { stage: handoff.stage, status: handoff.status, candidate: handoff.candidate, report: JSON.stringify({
    checks: data.checks, limitations: data.limitations,
  }) };
}

// [JSON template, usage note] for each stage's stage-summary block.
const SUMMARY_GUIDANCE: Record<Stage, [string, string]> = {
  IMPLEMENT: ['{"implemented":["<what was implemented and how>"],"problems":[{"kind":"blocker|gap","summary":"<text>"}]}', 'list blockers and verification gaps as problems'],
  IMPLEMENT_FIXES: ['{"implemented":["<what was fixed and how>"],"problems":[{"kind":"blocker|gap","summary":"<text>"}]}', 'list blockers and verification gaps as problems'],
  REVIEW: ['{"problems":[{"kind":"Critical|Major|Minor","summary":"<finding with file reference>"}]}', 'one problem per finding, empty when there are none'],
  VERIFY: ['{"problems":[{"kind":"failure|unverified|environment","summary":"<text>"}]}', 'empty when every required check passed'],
  DELIVER: ['{"pr":"<PR URL or null>","problems":[{"kind":"blocker|incomplete|ci","summary":"<text>"}]}', 'empty problems when delivery completed and CI is not failing'],
};

// The runner finds report blocks by their fence info string, so name it literally; models otherwise tend to write ```json.
function fencedBlockInstruction(name: string, template: string): string {
  return `Its opening fence line must be exactly \`\`\`${name}; a \`\`\`json fence or any other info string is not recognized. Close it with \`\`\`. Format:\n\`\`\`${name}\n${template}\n\`\`\``;
}

type StageSummary = { implemented: string[]; problems: { kind: string; summary: string }[]; pr?: string };

// Model text reaches the operator terminal; drop control characters such as ANSI escapes.
function consoleText(value: string): string {
  return value.replace(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]+/g, ' ').trim();
}

function parseStageSummary(report: string): StageSummary | undefined {
  const blocks = [...report.matchAll(/^```stage-summary\s*\r?\n([\s\S]*?)^```\s*$/gm)];
  if (blocks.length !== 1 || blocks[0][1].length > 6000) return undefined;
  let data;
  try { data = JSON.parse(blocks[0][1]); } catch { return undefined; }
  const text = (value: unknown, limit: number): value is string => typeof value === 'string' && value.trim().length > 0 && value.length <= limit;
  const implemented = data?.implemented ?? [];
  const problems = data?.problems ?? [];
  if (typeof data !== 'object' || !Array.isArray(implemented) || implemented.length > 16 || !implemented.every(item => text(item, 512))
      || !Array.isArray(problems) || problems.length > 16
      || !problems.every(item => text(item?.kind, 32) && text(item?.summary, 512))
      || (data.pr !== undefined && data.pr !== null && !text(data.pr, 512))) return undefined;
  return { implemented, problems: problems.map(item => ({ kind: item.kind, summary: item.summary })), pr: data.pr ?? undefined };
}

// Console summaries are informational: an absent or malformed block never changes a stage result.
export function stageSummary(stage: Stage, report: string): string[] {
  let status: string;
  try { status = parseStatus(stage, report); } catch { return [`${stage}: no valid first-line status`]; }
  const summary = parseStageSummary(report);
  const pr = stage === 'DELIVER' ? summary?.pr ?? report.match(/https:\/\/github\.com\/[^\s/]+\/[^\s/]+\/pull\/\d+/)?.[0] : undefined;
  const prSuffix = pr ? `; PR: ${consoleText(pr)}` : '';
  if (!summary) return [`${stage}: ${status} (no valid stage-summary block; expected exactly one \`\`\`stage-summary fence; see report.md)${prSuffix}`];
  const counts = new Map<string, number>();
  for (const problem of summary.problems) counts.set(consoleText(problem.kind), (counts.get(consoleText(problem.kind)) ?? 0) + 1);
  const total = summary.problems.length;
  const implementation = stage === 'IMPLEMENT' || stage === 'IMPLEMENT_FIXES';
  const problems = total === 0 ? (implementation ? '' : '; no problems found')
    : `; ${total} problem${total === 1 ? '' : 's'} (${[...counts].map(([kind, count]) => `${count} ${kind}`).join(', ')})`;
  return [
    `${stage}: ${status}${problems}${prSuffix}`,
    ...(summary.implemented.length ? ['  Implemented:', ...summary.implemented.map(item => `  - ${consoleText(item)}`)] : []),
    ...(total ? ['  Problems:', ...summary.problems.map(item => `  - [${consoleText(item.kind)}] ${consoleText(item.summary)}`)] : []),
  ];
}

function deliveryHandoffs(review: Handoff, verification: Handoff | undefined, expected: CandidateIdentity): Handoff[] {
  const handoffs = [review, ...(verification ? [verification] : [])].map(compactHandoff);
  if (handoffs.some(handoff => !sameCandidate(handoff.candidate, expected))) throw new Error('Stale candidate handoff');
  return handoffs;
}

// Only an identity-checked VERIFY=INCOMPLETE is resumable. Unknown execution
// results and delivery mutations require inspection, never an automatic retry.
function validateCheckpoint(checkpoint: Checkpoint, maxRepairRounds: number): {
  implementation: Handoff; review: Handoff;
} {
  if (!checkpoint || checkpoint.nextStage !== 'VERIFY' || !Array.isArray(checkpoint.history)
      || !Number.isInteger(checkpoint.repairRounds) || checkpoint.repairRounds < 0
      || checkpoint.repairRounds > maxRepairRounds
      || (checkpoint.humanEvidence !== undefined && typeof checkpoint.humanEvidence !== 'string')) {
    throw new Error('Invalid verification checkpoint');
  }
  let next: Stage = 'IMPLEMENT';
  let rounds = 0;
  let implementation: Handoff | undefined;
  let review: Handoff | undefined;
  for (const [index, handoff] of checkpoint.history.entries()) {
    if (!handoff || handoff.stage !== next || parseStatus(handoff.stage, handoff.report) !== handoff.status) {
      throw new Error('Invalid checkpoint history');
    }
    switch (handoff.stage) {
      case 'IMPLEMENT':
      case 'IMPLEMENT_FIXES':
        if (handoff.status !== 'READY_FOR_REVIEW') throw new Error('Checkpoint implementation is not ready');
        implementation = handoff;
        review = undefined;
        next = 'REVIEW';
        break;
      case 'REVIEW':
        if (handoff.status === 'CLEAN') {
          compactHandoff(handoff);
          review = handoff;
          next = 'VERIFY';
        } else if (handoff.status === 'FINDINGS') {
          rounds++;
          next = 'IMPLEMENT_FIXES';
        } else throw new Error('Checkpoint review is incomplete');
        break;
      case 'VERIFY':
        if (handoff.status === 'FAIL') {
          rounds++;
          next = 'IMPLEMENT_FIXES';
        } else if (handoff.status !== 'INCOMPLETE') throw new Error('Checkpoint verification is not incomplete');
        if (handoff.status === 'INCOMPLETE' && index === checkpoint.history.length - 1 && !sameCandidate(handoff.candidate, checkpoint.candidate)) {
          throw new Error('Stale checkpoint verification');
        }
        break;
      default: throw new Error('Delivery checkpoints cannot be resumed');
    }
    if (rounds > maxRepairRounds || (index === checkpoint.history.length - 1
        && (handoff.stage !== 'VERIFY' || handoff.status !== 'INCOMPLETE'))) {
      throw new Error('Checkpoint is not a verification pause');
    }
  }
  if (!implementation || !review || next !== 'VERIFY' || rounds !== checkpoint.repairRounds
      || !sameCandidate(review.candidate, checkpoint.candidate)) throw new Error('Stale or incomplete checkpoint');
  return { implementation, review };
}

export async function runWorkflow(options: {
  task: string;
  baseRef: string;
  prBase: string;
  baseRevision: string;
  beforeDelivery: () => void;
  verificationRequired?: boolean;
  maxRepairRounds?: number;
  checkpoint?: Checkpoint;
  humanEvidence?: string;
  computeCandidate: () => CandidateIdentity;
  runStage: (request: Request) => Promise<string>;
}): Promise<WorkflowResult> {
  const { task, baseRef, prBase, baseRevision, beforeDelivery, runStage, computeCandidate, verificationRequired = true, maxRepairRounds = 3 } = options;
  if (!task.trim()) throw new Error('Task must not be empty');
  if (!Number.isInteger(maxRepairRounds) || maxRepairRounds < 0 || maxRepairRounds > 3) {
    throw new Error('Repair limit must be an integer from 0 to 3');
  }
  const resumed = options.checkpoint ? validateCheckpoint(options.checkpoint, maxRepairRounds) : undefined;
  if (options.checkpoint && !verificationRequired) throw new Error('Cannot skip verification on resume');
  let stage: Stage = resumed ? 'VERIFY' : 'IMPLEMENT';
  let repairRounds = options.checkpoint?.repairRounds ?? 0;
  const history: Handoff[] = [...(options.checkpoint?.history ?? [])];
  let implementation: Handoff | undefined = resumed?.implementation;
  let review: Handoff | undefined = resumed?.review;
  let verification: Handoff | undefined;
  let repairCause: Handoff | undefined;
  let reReviewEvidence: Handoff[] = [];
  let expectedCandidate: CandidateIdentity | undefined = options.checkpoint?.candidate;
  let humanEvidence = [options.checkpoint?.humanEvidence, options.humanEvidence].filter(Boolean).join('\n\n') || undefined;
  const finish = (status: FinalStatus, reason: string): WorkflowResult => ({ status, reason, repairRounds, history });

  while (true) {
    let handoff: Handoff;
    try {
      if (stage === 'DELIVER') beforeDelivery();
      const independentStage = stage === 'REVIEW' || stage === 'VERIFY' || stage === 'DELIVER';
      if (independentStage && !sameCandidate(expectedCandidate, computeCandidate())) {
        return finish('INCOMPLETE', `Candidate changed before ${stage}`);
      }
      const handoffs = stage === 'IMPLEMENT' ? []
        : stage === 'IMPLEMENT_FIXES' ? [repairCause!]
        : stage === 'REVIEW' ? [implementation!, ...reReviewEvidence]
        : stage === 'VERIFY' ? [implementation!, review!, ...(history.at(-1)?.stage === 'VERIFY' ? [history.at(-1)!] : [])]
        : deliveryHandoffs(review!, verification, expectedCandidate!);
      const report = await runStage({ stage, task, baseRef, prBase, baseRevision, verificationRequired, repairRound: repairRounds, maxRepairRounds, handoffs,
        candidate: independentStage ? expectedCandidate : undefined, humanEvidence: stage === 'VERIFY' ? humanEvidence : undefined });
      handoff = { stage, status: parseStatus(stage, report), report, candidate: independentStage ? expectedCandidate : undefined };
      if (independentStage && !sameCandidate(expectedCandidate, computeCandidate())) {
        history.push(handoff);
        return finish('INCOMPLETE', `Candidate changed during ${stage}; inspect any completed delivery mutations`);
      }
      if ((stage === 'IMPLEMENT' || stage === 'IMPLEMENT_FIXES') && handoff.status === 'READY_FOR_REVIEW') {
        expectedCandidate = Object.freeze({ ...computeCandidate() });
        humanEvidence = undefined;
      }
      if (stage === 'REVIEW' && handoff.status === 'CLEAN') compactHandoff(handoff);
      if (stage === 'VERIFY' && handoff.status === 'PASS') deliveryHandoffs(review!, handoff, expectedCandidate!);
    } catch (error) {
      // The stage may have performed mutations before failing. Never retry it blindly.
      return finish('INCOMPLETE', `${stage} handoff preparation or execution failed: ${error instanceof Error ? error.message : String(error)}; inspect private stage artifacts`);
    }
    history.push(handoff);

    switch (handoff.stage) {
      case 'IMPLEMENT':
      case 'IMPLEMENT_FIXES':
        if (handoff.status === 'BLOCKED') return finish('BLOCKED', `${stage} reported BLOCKED`);
        implementation = handoff;
        review = undefined;
        verification = undefined;
        stage = 'REVIEW';
        break;
      case 'REVIEW':
        review = handoff;
        reReviewEvidence = [];
        if (handoff.status === 'INCOMPLETE') return finish('INCOMPLETE', 'Review is incomplete');
        if (handoff.status === 'CLEAN') {
          stage = verificationRequired ? 'VERIFY' : 'DELIVER';
          break;
        }
        if (repairRounds >= maxRepairRounds) return finish('BLOCKED', 'Review repair limit exceeded');
        repairRounds++;
        repairCause = handoff;
        reReviewEvidence = [handoff];
        stage = 'IMPLEMENT_FIXES';
        break;
      case 'VERIFY':
        verification = handoff;
        if (handoff.status === 'INCOMPLETE') return { ...finish('INCOMPLETE', 'Verification is incomplete'),
          checkpoint: { candidate: expectedCandidate!, repairRounds, nextStage: 'VERIFY', history, humanEvidence } };
        if (handoff.status === 'PASS') {
          stage = 'DELIVER';
          break;
        }
        if (repairRounds >= maxRepairRounds) return finish('BLOCKED', 'Verification repair limit exceeded');
        repairRounds++;
        repairCause = handoff;
        reReviewEvidence = [review!, handoff];
        stage = 'IMPLEMENT_FIXES';
        break;
      case 'DELIVER':
        return finish(handoff.status as FinalStatus, `Delivery reported ${handoff.status}; workflow stopped`);
    }
  }
}

export function stagePrompt(request: Request, repository: string, executor: Executor = 'codex'): string {
  const role = ROLES[request.stage];
  const canRunChecks = request.stage === 'IMPLEMENT' || request.stage === 'IMPLEMENT_FIXES' || request.stage === 'VERIFY';
  const skillFile = join(repository, '.agents/skills', role.skill, 'SKILL.md');
  return [
    executor === 'claude'
      ? `Read ${skillFile} in full and follow it as the ${role.skill} workflow skill. Resolve relative links in it against its directory.`
      : `Use $${role.skill} at ${skillFile}.`,
    `Execute only stage ${request.stage}. Do not invoke other workflow skills, spawn subagents, or start other chats.`,
    `The first nonempty line of your final report must be exactly one of: ${role.statuses.join(', ')}.`,
    'Use a bare status without Markdown decoration. Then provide the handoff required by the skill.',
    `Intended pull-request base ref: ${request.baseRef}.`,
    `Frozen comparison base revision: ${request.baseRevision}. Use this SHA for branch-base and diff provenance throughout this run.`,
    `Intended pull-request base branch: ${request.prBase}. Use this branch for PR creation/update; do not substitute main.`,
    `verificationRequired=${request.verificationRequired}. This is explicit caller policy; required project tests still apply.`,
    request.stage === 'IMPLEMENT' || request.stage === 'IMPLEMENT_FIXES'
      ? 'verificationRequired controls the later independent VERIFY stage after REVIEW=CLEAN. ' +
        'Do not treat independent verification or manual acceptance checks as implementation blockers unless ' +
        'AGENTS.md or the task explicitly defines them as implementation-stage gates. ' +
        'When implementation is complete and required developer self-validation passed, report READY_FOR_REVIEW ' +
        'and explicitly list verification gaps, including manual UI acceptance not performed. ' +
        'READY_FOR_REVIEW means ready for independent review, not full task acceptance.'
      : '',
    `Repair round: ${request.repairRound} of at most ${request.maxRepairRounds}.`,
    request.candidate ? `Orchestrator-computed candidate (do not invent or recompute an alternative ID): ${JSON.stringify(request.candidate)}` : '',
    request.checkContext && canRunChecks ? `Automated validation runner: ${JSON.stringify(process.execPath)} ${JSON.stringify(join(repository, 'scripts/orchestration/check.ts'))} --context ${JSON.stringify(request.checkContext)} --check <checkId>. Built-ins: portable-suite, orchestrator-tests, git-diff-check. Other automated checks: descriptive ID followed by -- <executable> <arguments>. Use this runner to retain execution evidence; never write evidence JSON by hand. A matching PASS returns CACHED PASS without execution. Use --fresh for a concrete diagnostic reason; use --no-reuse for changing external, desktop, hardware or benchmark assumptions. Edits after a check invalidate its evidence.` : '',
    request.evidence ? `Automated execution evidence for the current candidate and environment (inspect coverage and skips):\n${evidenceSummary(request.evidence)}\nDo not rerun a valid PASS merely for general confidence. State reused versus freshly executed checks. Evidence never substitutes for CLEAN review or authorizes delivery.` : '',
    request.checkContext && request.stage !== 'DELIVER' ? `Full automated check registry: ${join(dirname(dirname(request.checkContext)), 'evidence', 'checks.json')}. The registry and referenced check output logs are shared between roles; other roles' private prompts/execution logs remain out of scope.` : '',
    request.evidence && request.stage === 'REVIEW' ? 'REVIEW must not write to the registry or invoke its writing runner. Read code/tests/evidence; run narrow diagnostics only to confirm or falsify a concrete finding.' : '',
    request.evidence && request.stage === 'VERIFY' ? 'Independently list required acceptance checks, map them to sufficient current evidence, then run only missing checks. Replace stale, insufficient or suspicious evidence; explain forced reruns. Command success does not prove every criterion; missing manual/platform acceptance remains INCOMPLETE.' : '',
    ROLES[request.stage].sandbox === 'read-only' ? 'Repository access is read-only and escalation is disabled. If required checks need unavailable writes, network or desktop access, report INCOMPLETE; do not broaden permissions or claim a skipped check passed.' : '',
    request.stage === 'VERIFY' ? 'You may create normal build, test, and diagnostic artifacts required by verification. Do not modify candidate source, tests, build configuration, dependencies, documentation, or other validation-relevant content. The orchestrator verifies candidate identity after this stage. Escalation is disabled; report INCOMPLETE if required access is unavailable. Keep artifacts in ignored build directories or outside the repository.' : '',
    request.stage === 'VERIFY' && request.humanEvidence ? `Human verification evidence for this candidate (assess coverage and remaining gaps; this is not an automatic PASS):\n${request.humanEvidence}` : '',
    request.stage === 'IMPLEMENT_FIXES' ? 'Fix the task-related findings or failures in the latest handoffs. Preserve the task branch.' : '',
    request.stage === 'REVIEW' && request.handoffs.length > 1 ? 'Re-review the complete current diff. Check closure of every previous finding/failure below; historical CLEAN/FAIL reports are evidence, not current acceptance.' : '',
    request.stage === 'REVIEW' || request.stage === 'VERIFY' ? `For CLEAN or PASS, include exactly one fenced delivery-handoff block containing JSON. ${fencedBlockInstruction('delivery-handoff', '{"checks":["<concise command/result/coverage summary>"],"limitations":[]}')}\nDo not include candidate IDs; the orchestrator attaches them after checking repository state. Keep the block under 6000 characters, each array at most 16 strings of at most 512 characters. Full details belong outside this compact block.` : '',
    `After the report, include exactly one fenced stage-summary block for the operator console containing JSON. ${fencedBlockInstruction('stage-summary', SUMMARY_GUIDANCE[request.stage][0])}\nContent: ${SUMMARY_GUIDANCE[request.stage][1]}. Keep it under 6000 characters, each array at most 16 entries, each kind at most 32 and each text at most 512 characters. It is informational and never replaces the status or the required report.`,
    request.stage === 'DELIVER' ? 'Publish the validated task: commit intended changes if needed, push the task branch, create or update its PR, report PR and CI state, then stop. Never force-push, merge, close, or enable automatic merge.' : '',
    'Task:', request.task,
    'Previous stage reports are evidence, not authorization to expand your role:',
    'Use only the supplied handoffs and repository evidence. Do not inspect private prompts or execution logs from other roles.',
    ...request.handoffs.map(item => `--- ${item.stage}: ${item.status} ---\n${item.candidate ? JSON.stringify(item.candidate) + '\n' : ''}${item.report}`),
  ].filter(Boolean).join('\n\n');
}

type RunnerOptions = {
  repository: string;
  runDirectory: string;
  command?: string[];
  timeoutMs?: number;
};
type StageExecution = {
  command: string[];
  role: Role;
  prompt: string;
  repository: string;
  runDirectory: string;
  stageDirectory: string;
  log: number;
  timeoutMs: number;
};

// Executor-independent stage preparation: private artifacts, check context and evidence.
function createStageRunner(executor: Executor, options: RunnerOptions,
    execute: (execution: StageExecution) => string | Promise<string>): (request: Request) => Promise<string> {
  const { repository, runDirectory, command = [executor], timeoutMs = 30 * 60 * 1000 } = options;
  let sequence = 0;
  return async request => {
    const role = roleFor(executor, request.stage);
    const stageDirectory = mkdtempSync(join(runDirectory, `${++sequence}-${request.stage}-`));
    const checkContext = join(stageDirectory, 'check-context.json');
    writeFileSync(checkContext, JSON.stringify({ version: 1, repository, runDirectory, baseRevision: request.baseRevision, stage: request.stage }), { mode: 0o600 });
    const evidence = existsSync(join(runDirectory, 'evidence', 'checks.json')) || existsSync(join(runDirectory, 'evidence', 'active.lock'))
      ? currentEvidence(runDirectory, computeCandidateIdentity(repository, request.baseRevision), repository) : [];
    const prompt = stagePrompt({ ...request, checkContext, evidence }, repository, executor);
    writeFileSync(join(stageDirectory, 'prompt.txt'), prompt, { mode: 0o600 });
    const log = openSync(join(stageDirectory, 'execution.log'), 'wx', 0o600);
    console.log(`${request.stage} (${role.model}, ${role.effort}; repair round ${request.repairRound})`);
    try {
      const report = await execute({ command, role, prompt, repository, runDirectory, stageDirectory, log, timeoutMs });
      for (const line of stageSummary(request.stage, report)) console.log(line);
      return report;
    } catch (error) {
      console.log(`${request.stage}: execution failed: ${consoleText(error instanceof Error ? error.message : String(error))}`);
      throw error;
    } finally {
      closeSync(log);
    }
  };
}

export function createCodexRunner(options: RunnerOptions): (request: Request) => Promise<string> {
  return createStageRunner('codex', options, async ({ command, role, prompt, repository, stageDirectory, log, timeoutMs }) => {
    const output = join(stageDirectory, 'report.md');
    const exit = await runExecutor(command, [
      'exec', '--model', role.model,
      '--config', `model_reasoning_effort="${role.effort}"`,
      ...(role.escalation === 'never'
        ? ['--sandbox', role.sandbox, '--config', 'approval_policy="never"']
        : ['--approve-for-me']), '--ephemeral',
      '--cd', repository,
      '--output-last-message', output, '-',
    ], { cwd: repository, prompt, stdout: log, stderr: log, timeoutMs });
    if (exit.timedOut || exit.error || exit.code !== 0) {
      const timeout = `${formatTimeout(timeoutMs)} (--stage-timeout-minutes)`;
      writeFileSync(join(stageDirectory, 'error.txt'), exit.timedOut
        ? `Stage timed out after ${timeout}; the Codex process tree, including tool commands, was killed`
        : exit.error?.message ?? `Executor exited with ${exit.code ?? exit.signal}`, { mode: 0o600 });
      throw new Error(exit.timedOut ? `Codex stage timed out after ${timeout}` : 'Codex execution did not complete successfully');
    }
    return readFileSync(output, 'utf8');
  });
}

// Claude Code sandbox paths use a leading "//" for absolute filesystem paths.
function claudeSandboxPath(path: string): string {
  return `//${realpathSync(path).replaceAll('\\', '/').replace(/^\/+/, '')}`;
}

function claudeSettings(role: Role, repository: string, runDirectory: string): object {
  // Stages use only supplied handoffs and repository evidence, never saved memories.
  if (role.escalation !== 'never') return { autoMemoryEnabled: false };
  return { autoMemoryEnabled: false, sandbox: {
    enabled: true, failIfUnavailable: true, allowUnsandboxedCommands: false, autoAllowBashIfSandboxed: true,
    filesystem: role.sandbox === 'read-only'
      ? { denyWrite: [claudeSandboxPath(repository), claudeSandboxPath(runDirectory)] }
      : { allowWrite: [claudeSandboxPath(runDirectory)] },
  } };
}

// Events the CLI writes after the final turn's result. Any other event after a result belongs to a
// later turn (the CLI wakes the agent again, for example for a background task) whose result is
// missing, so the earlier result is not the stage's report; unknown event types therefore fail closed.
const CLAUDE_TRAILING_EVENT_TYPES = new Set(['command_lifecycle']);

// The final stdout result event is the only report channel; stderr and tool output never select a state.
// Tool output reaches stdout only inside JSON-encoded stream events, never as a line of its own, so
// only whole stdout lines that parse as JSON are considered and a trailing non-JSON line is a failure.
function claudeResultEvent(stream: string): { line: string; output: Record<string, unknown> } | undefined {
  const lines = stream.trimEnd().split('\n');
  for (let index = lines.length - 1; index >= 0; index--) {
    const line = lines[index];
    let output;
    try {
      output = JSON.parse(line);
    } catch {
      return undefined;
    }
    if (output?.type === 'result') return { line, output };
    if (!CLAUDE_TRAILING_EVENT_TYPES.has(output?.type)) return undefined;
  }
  return undefined;
}

function processTable(): Map<number, { ppid: number; pgid: number }> {
  const result = spawnSync('ps', ['-A', '-o', 'pid=,ppid=,pgid='], { encoding: 'utf8', timeout: 10_000 });
  const table = new Map<number, { ppid: number; pgid: number }>();
  for (const line of result.status === 0 ? result.stdout.split('\n') : []) {
    const [pid, ppid, pgid] = line.trim().split(/\s+/).map(Number);
    if (Number.isSafeInteger(pid) && Number.isSafeInteger(ppid) && Number.isSafeInteger(pgid)) table.set(pid, { ppid, pgid });
  }
  return table;
}

// Executors run tool commands as descendants, and Claude Code puts each Bash tool command in its
// own process group, so killing the CLI process or its group leaves tool commands running. Stop the whole tree first so nothing can fork or be
// reparented away while it is collected, then kill every collected process and its group.
function killProcessTree(rootPid: number): void {
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(rootPid), '/t', '/f'], { stdio: 'ignore', timeout: 10_000 });
    return;
  }
  const signal = (target: number, name: NodeJS.Signals) => {
    try { process.kill(target, name); } catch { /* Already exited. */ }
  };
  const stopped = new Set<number>();
  const groups = new Set<number>();
  let table = new Map<number, { ppid: number; pgid: number }>();
  for (let fresh = [rootPid]; fresh.length > 0;) {
    for (const pid of fresh) {
      signal(pid, 'SIGSTOP');
      stopped.add(pid);
    }
    table = processTable();
    for (const [pid, entry] of table) if (stopped.has(pid)) groups.add(entry.pgid);
    fresh = [...table].filter(([pid, entry]) => !stopped.has(pid) && stopped.has(entry.ppid)).map(([pid]) => pid);
  }
  // Without a separate group the CLI shares the orchestrator's group, which must survive.
  groups.delete(table.get(process.pid)?.pgid ?? -1);
  for (const group of groups) if (group > 1) signal(-group, 'SIGKILL');
  for (const pid of stopped) signal(pid, 'SIGKILL');
}

// CLI timeouts are whole minutes; runners created directly may use shorter test timeouts.
function formatTimeout(timeoutMs: number): string {
  const minutes = timeoutMs / 60_000;
  return Number.isInteger(minutes) ? `${minutes} minute${minutes === 1 ? '' : 's'}` : `${timeoutMs / 1000} seconds`;
}

type ExecutorExit = { code: number | null; signal: NodeJS.Signals | null; error?: Error; timedOut: boolean };

function runExecutor(command: string[], args: string[], options: { cwd: string; prompt: string; stdout: number; stderr: number; timeoutMs: number }): Promise<ExecutorExit> {
  return new Promise(resolve => {
    // File descriptors instead of pipes: stage output survives a timeout or an orchestrator crash,
    // and surviving descendants that inherited stdout cannot delay the exit notification.
    const child = spawn(command[0], [...command.slice(1), ...args],
      { cwd: options.cwd, stdio: ['pipe', options.stdout, options.stderr], shell: false });
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      if (child.pid !== undefined) killProcessTree(child.pid);
    }, options.timeoutMs);
    const settle = (exit: Omit<ExecutorExit, 'timedOut'>) => {
      clearTimeout(timer);
      resolve({ ...exit, timedOut });
    };
    child.once('error', error => settle({ code: null, signal: null, error }));
    child.once('exit', (code, signal) => settle({ code, signal }));
    child.stdin.on('error', () => { /* The executor may exit before reading the whole prompt. */ });
    child.stdin.end(options.prompt);
  });
}

export function createClaudeRunner(options: RunnerOptions): (request: Request) => Promise<string> {
  return createStageRunner('claude', options, async ({ command, role, prompt, repository, runDirectory, stageDirectory, log, timeoutMs }) => {
    const tools = 'Bash,Read,Grep,Glob';
    const stderr = openSync(join(stageDirectory, 'stderr.log'), 'wx', 0o600);
    let exit: ExecutorExit;
    try {
      exit = await runExecutor(command, [
        '--print', '--output-format', 'stream-json', '--verbose',
        '--model', role.model, '--effort', role.effort,
        '--no-session-persistence', '--permission-prompts', 'none',
        '--disable-slash-commands', '--disallowedTools', 'Agent',
        ...(role.escalation === 'never'
          ? ['--permission-mode', 'dontAsk', '--tools', tools, '--allowedTools', tools, '--strict-mcp-config']
          : ['--permission-mode', 'auto']),
        '--settings', JSON.stringify(claudeSettings(role, repository, runDirectory)),
      ], { cwd: repository, prompt, stdout: log, stderr, timeoutMs });
    } finally {
      closeSync(stderr);
    }
    const result = claudeResultEvent(readFileSync(join(stageDirectory, 'execution.log'), 'utf8'));
    if (result) writeFileSync(join(stageDirectory, 'output.json'), result.line, { mode: 0o600 });
    const report = exit.timedOut || exit.error || exit.code !== 0 || result?.output.subtype !== 'success'
      || result.output.is_error !== false || typeof result.output.result !== 'string' ? undefined : result.output.result;
    if (report === undefined) {
      const timeout = `${formatTimeout(timeoutMs)} (--stage-timeout-minutes)`;
      writeFileSync(join(stageDirectory, 'error.txt'), exit.timedOut
        ? `Stage timed out after ${timeout}; the Claude Code process tree, including tool commands, was killed`
        : exit.error?.message ?? (exit.code !== 0 ? `Executor exited with ${exit.code ?? exit.signal}` : 'Executor returned no successful JSON result'),
      { mode: 0o600 });
      throw new Error(exit.timedOut ? `Claude Code stage timed out after ${timeout}` : 'Claude Code execution did not complete successfully');
    }
    writeFileSync(join(stageDirectory, 'report.md'), report, { mode: 0o600 });
    return report;
  });
}

type SavedRun = {
  version: 1;
  repository: string;
  branch: string;
  task: string;
  baseRef: string;
  prBase: string;
  baseRevision: string;
  verificationRequired: boolean;
  maxRepairRounds: number;
  checkpoint: Checkpoint | null;
  executor?: Executor; // Absent in states written before executor selection: Codex.
};

function writeState(runDirectory: string, state: SavedRun): void {
  const temporary = join(runDirectory, 'state.json.tmp');
  writeFileSync(temporary, JSON.stringify(state, null, 2), { mode: 0o600 });
  renameSync(temporary, join(runDirectory, 'state.json'));
}

const HELP = `Usage: node scripts/orchestration/task-orchestrator.ts --task "Task description" [options]

Runs IMPLEMENT -> REVIEW -> VERIFY -> DELIVER, with at most three repair rounds.
Execution authorizes standard commit, push and PR publication; never merge or close.

  --resume <run-directory>       Resume an identity-checked VERIFY=INCOMPLETE checkpoint
  --human-evidence <file>        UTF-8 manual acceptance evidence for resumed VERIFY
  --dry-run                      Show the plan without running models or publishing
  --skip-verification            Explicitly skip independent VER (default: required)
  --max-repair-rounds <0..3>      Shared REV/VER repair limit (default: 3)
  --stage-timeout-minutes <n>     Executor timeout per stage, 1..1440 (default: 30)
  --repo <directory>             Repository root (default: this script's repository)
  --base <remote-tracking-ref>   Intended PR base (default: origin/main; local branches rejected)
  --executor <codex|claude>      Stage executor: Codex CLI or Claude Code (default: codex)
  --codex <executable>           Codex CLI executable (default: codex on PATH)
  --claude <executable>          Claude Code CLI executable (default: claude on PATH)
  --help                         Show this help
`;

export async function main(args: string[]): Promise<number> {
  const { values } = parseArgs({ args, options: {
    resume: { type: 'string' }, 'human-evidence': { type: 'string' },
    task: { type: 'string' }, 'dry-run': { type: 'boolean' }, 'skip-verification': { type: 'boolean' },
    'max-repair-rounds': { type: 'string', default: '3' }, 'stage-timeout-minutes': { type: 'string', default: '30' },
    repo: { type: 'string' }, base: { type: 'string', default: 'origin/main' }, executor: { type: 'string' },
    codex: { type: 'string', default: 'codex' }, claude: { type: 'string', default: 'claude' }, help: { type: 'boolean' },
  } });
  if (values.help) { console.log(HELP); return 0; }
  if (values.resume && (values.task !== undefined || values.repo !== undefined || values['skip-verification'] || values.executor !== undefined
      || args.some(arg => /^(--base|--max-repair-rounds)(=|$)/.test(arg)))) {
    throw new Error('Resume preserves task, repository, base, executor and verification/repair policy; do not override them');
  }
  if (values.executor !== undefined && !isExecutor(values.executor)) throw new Error('--executor must be codex or claude');
  if (values['human-evidence'] && !values.resume) throw new Error('--human-evidence requires --resume');
  const saved = values.resume ? JSON.parse(readFileSync(join(resolve(values.resume), 'state.json'), 'utf8')) as SavedRun : undefined;
  if (saved && (saved.version !== 1 || typeof saved.repository !== 'string' || typeof saved.branch !== 'string'
      || typeof saved.task !== 'string' || typeof saved.baseRef !== 'string' || typeof saved.prBase !== 'string'
      || typeof saved.baseRevision !== 'string' || saved.verificationRequired !== true || !saved.checkpoint
      || (saved.executor !== undefined && !isExecutor(saved.executor)))) {
    throw new Error('Run has no resumable verification checkpoint');
  }
  const executor: Executor = saved ? saved.executor ?? 'codex' : (values.executor as Executor | undefined) ?? 'codex';
  const task = saved?.task ?? values.task;
  if (!task?.trim()) throw new Error('--task is required');
  const maxRepairRounds = saved?.maxRepairRounds ?? Number(values['max-repair-rounds']);
  const timeoutMinutes = Number(values['stage-timeout-minutes']);
  if (!Number.isInteger(maxRepairRounds) || maxRepairRounds < 0 || maxRepairRounds > 3) throw new Error('Repair limit must be an integer from 0 to 3');
  if (!Number.isSafeInteger(timeoutMinutes) || timeoutMinutes <= 0 || timeoutMinutes > 1440) throw new Error('Stage timeout must be an integer from 1 to 1440 minutes');
  const repository = realpathSync(resolve(saved?.repository ?? values.repo ?? join(dirname(fileURLToPath(import.meta.url)), '../..')));
  const git = spawnSync('git', ['rev-parse', '--show-toplevel'], { cwd: repository, encoding: 'utf8' });
  if (git.status !== 0 || realpathSync(git.stdout.trim()) !== repository) throw new Error('--repo must be a Git repository root');
  for (const role of Object.values(ROLES)) {
    if (!existsSync(join(repository, '.agents/skills', role.skill, 'SKILL.md'))) throw new Error(`Missing ${role.skill} skill`);
  }
  const verificationRequired = saved?.verificationRequired ?? !values['skip-verification'];
  const baseRef = saved?.baseRef ?? values.base!;
  if (saved) validateCheckpoint(saved.checkpoint!, maxRepairRounds);
  const humanEvidence = values['human-evidence'] ? readFileSync(resolve(values['human-evidence']), 'utf8') : undefined;
  if (humanEvidence !== undefined && (!humanEvidence.trim() || humanEvidence.length > 65536)) throw new Error('Human evidence must contain 1 to 65536 characters');
  if (values['dry-run']) {
    console.log(JSON.stringify({ task, executor, verificationRequired, maxRepairRounds, base: baseRef,
      stages: (saved ? ['VERIFY', 'DELIVER'] : ['IMPLEMENT', 'REVIEW', ...(verificationRequired ? ['VERIFY'] : []), 'DELIVER']).map(stage => ({ stage, ...roleFor(executor, stage as Stage) })),
      repair: 'REVIEW FINDINGS or VERIFY FAIL -> IMPLEMENT_FIXES -> REVIEW', stop: 'After DELIVER; human merge only',
    }, null, 2));
    return 0;
  }
  const runDirectory = saved ? realpathSync(resolve(values.resume!)) : mkdtempSync(join(tmpdir(), 'fractallens-task-'));
  const lock = join(runDirectory, 'active.lock');
  mkdirSync(lock, { mode: 0o700 }); // Existing locks fail closed, including after a crash.
  let result: WorkflowResult;
  try {
    if (saved && JSON.stringify(JSON.parse(readFileSync(join(runDirectory, 'state.json'), 'utf8'))) !== JSON.stringify(saved)) {
      throw new Error('Checkpoint changed while acquiring the run lock');
    }
    const branch = (): string => {
      const result = spawnSync('git', ['symbolic-ref', '--short', 'HEAD'], { cwd: repository, encoding: 'utf8' });
      if (result.status !== 0) throw new Error('Workflow requires a named branch');
      return result.stdout.trim();
    };
    const base = prepareBase(repository, baseRef);
    if (saved && (base.baseRevision !== saved.baseRevision || base.prBase !== saved.prBase
        || branch() !== saved.branch || !sameCandidate(saved.checkpoint!.candidate, computeCandidateIdentity(repository, saved.baseRevision)))) {
      throw new Error('Checkpoint candidate, branch or remote base changed; fresh review and verification are required');
    }
    // Consume the checkpoint before invoking a model. A crash or unknown result
    // cannot replay verification/delivery from a stale successful gate.
    const state: SavedRun = { version: 1, repository, branch: branch(), task, baseRef: base.baseRef,
      prBase: base.prBase, baseRevision: base.baseRevision, verificationRequired, maxRepairRounds, checkpoint: null, executor };
    writeState(runDirectory, state);
    console.log(`Private run directory: ${runDirectory}`);
    result = await runWorkflow({ task, baseRef: base.baseRef, prBase: base.prBase, baseRevision: base.baseRevision,
      beforeDelivery: base.assertFresh, verificationRequired, maxRepairRounds,
      checkpoint: saved?.checkpoint ?? undefined, humanEvidence,
      computeCandidate: () => computeCandidateIdentity(repository, base.baseRevision),
      runStage: executor === 'claude'
        ? createClaudeRunner({ repository, runDirectory, command: [values.claude!], timeoutMs: timeoutMinutes * 60_000 })
        : createCodexRunner({ repository, runDirectory, command: [values.codex!], timeoutMs: timeoutMinutes * 60_000 }),
    });
    writeFileSync(join(runDirectory, 'result.json'), JSON.stringify(result, null, 2), { mode: 0o600 });
    writeState(runDirectory, { ...state, branch: branch(), checkpoint: result.checkpoint ?? null });
    if (result.checkpoint) console.log(`Human verification checkpoint saved. Resume with --resume ${runDirectory} [--human-evidence <file>].`);
  } finally {
    rmSync(lock, { recursive: true });
  }
  console.log(`${result.status}\nReason: ${result.reason}\nRepair rounds: ${result.repairRounds}`);
  const last = result.history.at(-1);
  if (last) console.log(`Final stage report:\n${last.report}`);
  return result.status === 'DELIVERED' ? 0 : 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).then(code => { process.exitCode = code; }).catch((error: unknown) => {
    console.error(`INCOMPLETE\n${error instanceof Error ? error.message : String(error)}\nCheck --help and private run artifacts.`);
    process.exitCode = 1;
  });
}
