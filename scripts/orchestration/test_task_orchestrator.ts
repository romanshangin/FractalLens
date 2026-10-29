import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test, { type TestContext } from 'node:test';
import { CLAUDE_MODELS, computeCandidateIdentity, createClaudeRunner, createCodexRunner, main, parseStatus, prepareBase, ROLES, runWorkflow, stagePrompt, stageSummary } from './task-orchestrator.ts';
import type { Request, Stage } from './task-orchestrator.ts';

const BASE = { baseRef: 'origin/main', prBase: 'main', baseRevision: 'synthetic-base', beforeDelivery: () => {} };
const CANDIDATE = { baseRevision: 'synthetic-base', mergeBase: 'synthetic-merge-base', contentId: 'synthetic-content' };

function gitFixture(t: TestContext, withUnrelatedTrackedFile = false) {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-identity-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const repository = join(root, 'repository');
  const hooks = join(root, 'empty-hooks');
  mkdirSync(repository);
  mkdirSync(hooks);
  const git = (...args: string[]) => {
    const result = spawnSync('git', ['-c', `core.hooksPath=${hooks}`, '-c', 'commit.gpgsign=false', ...args], { cwd: repository, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git('init', '-b', 'main');
  git('config', 'user.name', 'Synthetic Author');
  git('config', 'user.email', 'noreply@example.org');
  writeFileSync(join(repository, 'tracked.txt'), 'Initial content\n');
  if (withUnrelatedTrackedFile) writeFileSync(join(repository, 'unrelated.txt'), 'Unchanged baseline content\n');
  writeFileSync(join(repository, '.gitignore'), 'ignored/\n');
  git('add', '--all');
  git('commit', '-m', 'Synthetic base');
  git('switch', '-c', 'chore/synthetic-task');
  return { repository, git, identity: () => computeCandidateIdentity(repository, 'main') };
}

test('identity covers tracked, untracked and deleted content and survives staging/commit', t => {
  const fixture = gitFixture(t);
  const { repository, git, identity } = fixture;
  const index = readFileSync(join(repository, '.git/index'));
  const initial = identity();
  assert.deepEqual(identity(), initial);
  assert.deepEqual(readFileSync(join(repository, '.git/index')), index);
  writeFileSync(join(repository, 'tracked.txt'), 'Changed content\n');
  const changed = identity();
  assert.notEqual(changed.contentId, initial.contentId);
  writeFileSync(join(repository, 'untracked file\nwith newline.txt'), 'Untracked content');
  const untracked = identity();
  assert.notEqual(untracked.contentId, changed.contentId);
  git('add', '--all');
  assert.deepEqual(identity(), untracked);
  git('commit', '-m', 'Synthetic candidate');
  assert.deepEqual(identity(), untracked);
  rmSync(join(repository, 'tracked.txt'));
  assert.notEqual(identity().contentId, untracked.contentId);
});

test('identity is independent of unchanged tracked files outside the candidate diff', t => {
  const withoutExtraFile = gitFixture(t);
  const withExtraFile = gitFixture(t, true);
  for (const fixture of [withoutExtraFile, withExtraFile]) {
    writeFileSync(join(fixture.repository, 'tracked.txt'), 'Candidate change\n');
  }
  assert.equal(withoutExtraFile.identity().contentId, withExtraFile.identity().contentId);
});

test('staging and committing a rename preserve candidate identity', t => {
  const { repository, git, identity } = gitFixture(t);
  git('config', 'diff.renames', 'true');
  renameSync(join(repository, 'tracked.txt'), join(repository, 'renamed.txt'));
  const renamed = identity();
  git('add', '--all');
  assert.deepEqual(identity(), renamed);
  git('commit', '-m', 'Synthetic rename');
  assert.deepEqual(identity(), renamed);
});

test('restoring and staging the source of a rename change candidate identity', t => {
  const { repository, git, identity } = gitFixture(t);
  git('config', 'diff.renames', 'true');
  const source = join(repository, 'tracked.txt');
  const original = readFileSync(source);
  renameSync(source, join(repository, 'renamed.txt'));
  git('add', '--all');
  const renamed = identity();
  writeFileSync(source, original);
  git('add', 'tracked.txt');
  assert.notEqual(identity().contentId, renamed.contentId);
});

test('ignored build output does not affect identity; base advancement does', t => {
  const { repository, git, identity } = gitFixture(t);
  const before = identity();
  mkdirSync(join(repository, 'ignored'));
  writeFileSync(join(repository, 'ignored/output.txt'), 'Generated output');
  assert.deepEqual(identity(), before);
  git('switch', 'main');
  git('commit', '--allow-empty', '-m', 'Synthetic base advance');
  git('switch', 'chore/synthetic-task');
  const after = identity();
  assert.notEqual(after.baseRevision, before.baseRevision);
  assert.equal(after.contentId, before.contentId);
});

test('file modes and symlink targets are part of the canonical candidate', t => {
  if (process.platform === 'win32') { t.skip('Requires POSIX executable modes and symlinks'); return; }
  const { repository, git, identity } = gitFixture(t);
  git('config', 'core.filemode', 'true');
  git('config', 'core.symlinks', 'true');
  const before = identity();
  chmodSync(join(repository, 'tracked.txt'), 0o755);
  assert.notEqual(identity().contentId, before.contentId);
  const link = join(repository, 'link.txt');
  symlinkSync('tracked.txt', link);
  const linked = identity();
  git('add', '--all');
  assert.deepEqual(identity(), linked);
  rmSync(link);
  writeFileSync(link, 'tracked.txt');
  assert.notEqual(identity().contentId, linked.contentId);
});

test('unavailable base and unsupported submodule candidates fail closed', t => {
  const { repository, git, identity } = gitFixture(t);
  assert.throws(() => computeCandidateIdentity(repository, 'missing-base'));
  writeFileSync(join(repository, 'tracked.txt'), 'Candidate change\n');
  git('update-index', '--assume-unchanged', 'tracked.txt');
  assert.throws(identity, /Assume-unchanged/);
  git('update-index', '--no-assume-unchanged', 'tracked.txt');
  git('update-index', '--add', '--cacheinfo', `160000,${git('rev-parse', 'HEAD')},nested`);
  assert.throws(identity, /submodules/);
});
function withEvidence(report: string): string {
  return [report, '```delivery-handoff', JSON.stringify({ checks: ['Synthetic check passed'], limitations: [] }), '```'].join('\n');
}

function scripted(reports: string[], options: { verificationRequired?: boolean; maxRepairRounds?: number } = {}) {
  // Successful synthetic fixtures carry the compact publication contract.
  const fixtures = reports.map(report => ['CLEAN', 'PASS'].includes(report.split('\n', 1)[0]) ? withEvidence(report) : report);
  const requests: Request[] = [];
  const result = runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic task', ...options, runStage: async request => {
    requests.push(request);
    assert.ok(fixtures.length, 'Unexpected extra stage invocation');
    return fixtures.shift()!;
  } });
  return { requests, result };
}

test('default path publishes once after CLEAN and PASS, with sequential role handoffs', async () => {
  const run = scripted(['READY_FOR_REVIEW\nImplemented', 'CLEAN\nReviewed', 'PASS\nVerified', 'DELIVERED\nPR and CI state']);
  assert.equal((await run.result).status, 'DELIVERED');
  assert.deepEqual(run.requests.map(r => r.stage), ['IMPLEMENT', 'REVIEW', 'VERIFY', 'DELIVER']);
  assert.deepEqual(run.requests.at(-1)!.handoffs.map(h => h.status), ['CLEAN', 'PASS']);
  assert.ok(run.requests.every(r => r.verificationRequired));
});

test('explicit policy skips VER while retaining CLEAN review', async () => {
  const run = scripted(['READY_FOR_REVIEW', 'CLEAN', 'DELIVERED'], { verificationRequired: false });
  assert.equal((await run.result).status, 'DELIVERED');
  assert.deepEqual(run.requests.map(r => r.stage), ['IMPLEMENT', 'REVIEW', 'DELIVER']);
  assert.ok(run.requests.every(r => !r.verificationRequired));
  assert.deepEqual(run.requests.at(-1)!.handoffs.map(h => h.status), ['CLEAN']);
});

test('review findings trigger fixes and a fresh review before VER', async () => {
  const run = scripted(['READY_FOR_REVIEW', 'FINDINGS\nFix cancellation', 'READY_FOR_REVIEW\nFixed', 'CLEAN', 'PASS', 'DELIVERED']);
  const result = await run.result;
  assert.equal(result.status, 'DELIVERED');
  assert.equal(result.repairRounds, 1);
  assert.deepEqual(run.requests.map(r => r.stage), ['IMPLEMENT', 'REVIEW', 'IMPLEMENT_FIXES', 'REVIEW', 'VERIFY', 'DELIVER']);
  assert.match(run.requests[2].handoffs.at(-1)!.report, /Fix cancellation/);
  assert.deepEqual(run.requests[2].handoffs.map(h => h.status), ['FINDINGS']);
  assert.deepEqual(run.requests[3].handoffs.map(h => h.report), ['READY_FOR_REVIEW\nFixed', 'FINDINGS\nFix cancellation']);
});

test('VER failure triggers DEV, REV and VER again, discarding stale handoffs', async () => {
  const run = scripted(['READY_FOR_REVIEW', 'CLEAN\nOld', 'FAIL\nIncorrect pixels', 'READY_FOR_REVIEW', 'CLEAN\nNew', 'PASS', 'DELIVERED']);
  assert.equal((await run.result).status, 'DELIVERED');
  assert.deepEqual(run.requests.map(r => r.stage), ['IMPLEMENT', 'REVIEW', 'VERIFY', 'IMPLEMENT_FIXES', 'REVIEW', 'VERIFY', 'DELIVER']);
  assert.match(run.requests[3].handoffs.at(-1)!.report, /Incorrect pixels/);
  assert.deepEqual(run.requests[3].handoffs.map(h => h.status), ['FAIL']);
  assert.deepEqual(run.requests[4].handoffs.map(h => h.status), ['READY_FOR_REVIEW', 'CLEAN', 'FAIL']);
  assert.match(run.requests[4].handoffs[1].report, /CLEAN\nOld/);
  assert.match(run.requests[4].handoffs[2].report, /Incorrect pixels/);
  assert.ok(!run.requests[5].handoffs.some(h => h.status === 'FAIL'));
  assert.deepEqual(run.requests.at(-1)!.handoffs[0].candidate, CANDIDATE);
});

test('fourth review finding stops after exactly three repair rounds', async () => {
  const run = scripted(Array.from({ length: 4 }, () => ['READY_FOR_REVIEW', 'FINDINGS']).flat());
  const result = await run.result;
  assert.equal(result.status, 'BLOCKED');
  assert.equal(result.reason, 'Review repair limit exceeded');
  assert.equal(result.repairRounds, 3);
  assert.equal(run.requests.filter(r => r.stage === 'IMPLEMENT_FIXES').length, 3);
});

test('review and VER repairs share one limit', async () => {
  const run = scripted(['READY_FOR_REVIEW', 'FINDINGS', 'READY_FOR_REVIEW', 'CLEAN', 'FAIL'], { maxRepairRounds: 1 });
  const result = await run.result;
  assert.equal(result.status, 'BLOCKED');
  assert.equal(result.reason, 'Verification repair limit exceeded');
  assert.equal(result.repairRounds, 1);
  assert.equal(run.requests.length, 5);
  assert.ok(run.requests.every(request => request.maxRepairRounds === 1));
});

test('re-review preserves the latest findings without accumulating older rounds', async () => {
  const run = scripted(['READY_FOR_REVIEW', 'FINDINGS\nFirst findings', 'READY_FOR_REVIEW\nFix one',
    'FINDINGS\nRemaining finding', 'READY_FOR_REVIEW\nFix two', 'CLEAN', 'PASS', 'DELIVERED']);
  assert.equal((await run.result).status, 'DELIVERED');
  assert.deepEqual(run.requests[5].handoffs.map(h => h.report), ['READY_FOR_REVIEW\nFix two', 'FINDINGS\nRemaining finding']);
  assert.deepEqual(run.requests[4].handoffs.map(h => h.report), ['FINDINGS\nRemaining finding']);
});

test('delivery receives compact provenance and results without DEV or full REV/VER text', async () => {
  const run = scripted(['READY_FOR_REVIEW\nDeveloper detail marker', 'CLEAN\nReviewer detail marker',
    'PASS\nVerifier detail marker', 'DELIVERED']);
  assert.equal((await run.result).status, 'DELIVERED');
  const request = run.requests.at(-1)!;
  assert.deepEqual(request.handoffs.map(h => h.status), ['CLEAN', 'PASS']);
  assert.ok(request.handoffs.every(h => JSON.parse(h.report).checks[0] === 'Synthetic check passed'));
  assert.ok(request.handoffs.every(h => JSON.stringify(h.candidate) === JSON.stringify(CANDIDATE)));
  assert.doesNotMatch(stagePrompt(request, '/project'), /Developer detail marker|Reviewer detail marker|Verifier detail marker/);
});

test('missing, malformed, ambiguous or oversized compact evidence stops before publication', async () => {
  const valid = withEvidence('CLEAN');
  const invalid = [
    'CLEAN\nNo compact evidence',
    'CLEAN\n```delivery-handoff\ninvalid JSON\n```',
    'CLEAN\n```delivery-handoff\n{"candidate":{}}\n```',
    valid + '\n' + valid,
    'CLEAN\n```delivery-handoff\n' + ' '.repeat(6001) + '\n```',
    'CLEAN\n```delivery-handoff\n' + JSON.stringify({ candidate: CANDIDATE, checks: ['x'.repeat(513)], limitations: [] }) + '\n```',
  ];
  for (const review of invalid) {
    const reports = ['READY_FOR_REVIEW', review, withEvidence('PASS')];
    const stages: Stage[] = [];
    const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: async request => {
      stages.push(request.stage);
      return reports.shift()!;
    } });
    assert.equal(result.status, 'INCOMPLETE');
    assert.deepEqual(stages, ['IMPLEMENT', 'REVIEW']);
  }
});

test('a passing VERIFY handoff fenced as json stops before publication and names the expected fence', async () => {
  // Regression: a verifier labelled its compact evidence ```json instead of ```delivery-handoff.
  const jsonFenced = ['PASS', '```json', JSON.stringify({ checks: ['Synthetic check passed'], limitations: [] }), '```'].join('\n');
  const reports = ['READY_FOR_REVIEW', withEvidence('CLEAN'), jsonFenced];
  const stages: Stage[] = [];
  const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', verificationRequired: true,
    runStage: async request => { stages.push(request.stage); return reports.shift()!; } });
  assert.equal(result.status, 'INCOMPLETE');
  assert.match(result.reason, /expected exactly one ```delivery-handoff fenced block/);
  assert.deepEqual(stages, ['IMPLEMENT', 'REVIEW', 'VERIFY']);
});

test('model-supplied provenance is rejected instead of trusted', async () => {
  const forged = ['CLEAN', '```delivery-handoff', JSON.stringify({ candidate: CANDIDATE, checks: ['Synthetic'], limitations: [] }), '```'].join('\n');
  let calls = 0;
  const reports = ['READY_FOR_REVIEW', forged];
  const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: async () => { calls++; return reports.shift()!; } });
  assert.equal(result.status, 'INCOMPLETE');
  assert.equal(calls, 2);
});

test('repository identity changes before or during independent stages stop the workflow', async () => {
  for (const key of ['baseRevision', 'mergeBase', 'contentId']) {
    for (let changeAt = 2; changeAt <= 7; changeAt++) {
      let computations = 0;
      const reports = ['READY_FOR_REVIEW', withEvidence('CLEAN'), withEvidence('PASS'), 'DELIVERED'];
      const stages: Stage[] = [];
      const result = await runWorkflow({ ...BASE, task: 'Synthetic',
        computeCandidate: () => ++computations >= changeAt ? { ...CANDIDATE, [key]: 'changed' } : CANDIDATE,
        runStage: async request => { stages.push(request.stage); return reports.shift()!; },
      });
      assert.equal(result.status, 'INCOMPLETE');
      assert.match(result.reason, /Candidate changed/);
      assert.equal(stages.length, Math.ceil(changeAt / 2));
    }
  }
});

for (const scenario of [
  { reports: ['BLOCKED'], status: 'BLOCKED' },
  { reports: ['READY_FOR_REVIEW', 'FINDINGS', 'BLOCKED'], status: 'BLOCKED' },
  { reports: ['READY_FOR_REVIEW', 'INCOMPLETE'], status: 'INCOMPLETE' },
  { reports: ['READY_FOR_REVIEW', 'CLEAN', 'INCOMPLETE'], status: 'INCOMPLETE' },
  { reports: ['READY_FOR_REVIEW', 'CLEAN', 'PASS', 'BLOCKED'], status: 'BLOCKED' },
  { reports: ['READY_FOR_REVIEW', 'CLEAN', 'PASS', 'INCOMPLETE'], status: 'INCOMPLETE' },
]) {
  test(`terminal ${scenario.status} at stage ${scenario.reports.length} does not launch another role`, async () => {
    const expectedCalls = scenario.reports.length;
    const run = scripted([...scenario.reports]);
    assert.equal((await run.result).status, scenario.status);
    assert.equal(run.requests.length, expectedCalls);
  });
}

test('unknown, decorated, later-line and wrong-role statuses do not advance', async () => {
  for (const report of ['', 'Done\nREADY_FOR_REVIEW', '**READY_FOR_REVIEW**', 'CLEAN', 'READY_FOR_REVIEW with caveats']) {
    const run = scripted([report]);
    assert.equal((await run.result).status, 'INCOMPLETE');
    assert.equal(run.requests.length, 1);
  }
  assert.equal(parseStatus('REVIEW', '\nCLEAN\r\nNo findings'), 'CLEAN');
  assert.equal(parseStatus('REVIEW', 'CLEAN  \t\nNo findings'), 'CLEAN');
  assert.throws(() => parseStatus('REVIEW', ' CLEAN.\nNo findings'), /no valid first-line status/);
});

test('executor rejection stops without trying to repair or deliver', async () => {
  let calls = 0;
  const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic task', runStage: async () => { calls++; throw new Error('Unavailable'); } });
  assert.equal(result.status, 'INCOMPLETE');
  assert.equal(calls, 1);
});

test('invalid budgets and empty task fail before invoking an executor', async () => {
  for (const maxRepairRounds of [-1, 4, 0.5, NaN]) {
    await assert.rejects(runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', maxRepairRounds, runStage: async () => assert.fail('Must not launch') }));
  }
  await assert.rejects(runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: ' ', runStage: async () => assert.fail('Must not launch') }));
  const run = scripted(['READY_FOR_REVIEW', 'FINDINGS'], { maxRepairRounds: 0 });
  assert.equal((await run.result).repairRounds, 0);
});

test('stage prompts explicitly bind skills, skip policy and role boundaries', () => {
  for (const stage of Object.keys(ROLES) as Stage[]) {
    const prompt = stagePrompt({ ...BASE, stage, task: 'Synthetic task', verificationRequired: false, repairRound: 1, maxRepairRounds: 1, handoffs: [] }, '/project');
    assert.ok(prompt.includes(`Use $${ROLES[stage].skill} at`));
    assert.match(prompt, /verificationRequired=false/);
    assert.match(prompt, /Intended pull-request base ref: origin\/main/);
    assert.match(prompt, /Intended pull-request base branch: main/);
    assert.match(prompt, /Frozen comparison base revision: synthetic-base/);
    assert.match(prompt, /Do not invoke other workflow skills, spawn subagents/);
    assert.match(prompt, /Repair round: 1 of at most 1/);
  }
});

function withSummary(report: string, summary: object): string {
  return [report, '```stage-summary', JSON.stringify(summary), '```'].join('\n');
}

test('stage prompts request a stage-specific console summary block', () => {
  const expected: Record<Stage, RegExp> = {
    IMPLEMENT: /"implemented":\["<what was implemented and how>"\]/,
    IMPLEMENT_FIXES: /"implemented":\["<what was fixed and how>"\]/,
    REVIEW: /"kind":"Critical\|Major\|Minor"/,
    VERIFY: /"kind":"failure\|unverified\|environment"/,
    DELIVER: /"pr":"<PR URL or null>"/,
  };
  for (const stage of Object.keys(ROLES) as Stage[]) {
    const prompt = stagePrompt({ ...BASE, stage, task: 'Synthetic task', verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [] }, '/project');
    assert.match(prompt, /include exactly one fenced stage-summary block for the operator console/);
    assert.match(prompt, /opening fence line must be exactly ```stage-summary; a ```json fence or any other info string is not recognized/);
    assert.ok(prompt.includes('\n```stage-summary\n{'), 'prompt shows a literal stage-summary fence');
    assert.match(prompt, expected[stage]);
    const handoffFence = /opening fence line must be exactly ```delivery-handoff; a ```json fence/;
    if (stage === 'REVIEW' || stage === 'VERIFY') {
      assert.match(prompt, handoffFence);
      assert.ok(prompt.includes('\n```delivery-handoff\n{"checks":'), 'prompt shows a literal delivery-handoff fence');
    } else {
      assert.doesNotMatch(prompt, handoffFence);
    }
  }
});

test('stage summaries report implementation, problem counts and the PR link', () => {
  assert.deepEqual(stageSummary('IMPLEMENT', withSummary('READY_FOR_REVIEW\nDetails', {
    implemented: ['Added stage summaries by parsing a fenced JSON block'], problems: [{ kind: 'gap', summary: 'Manual UI check not run' }],
  })), [
    'IMPLEMENT: READY_FOR_REVIEW; 1 problem (1 gap)',
    '  Implemented:', '  - Added stage summaries by parsing a fenced JSON block',
    '  Problems:', '  - [gap] Manual UI check not run',
  ]);
  assert.deepEqual(stageSummary('IMPLEMENT_FIXES', withSummary('READY_FOR_REVIEW', { implemented: ['Fixed the finding'], problems: [] })),
    ['IMPLEMENT_FIXES: READY_FOR_REVIEW', '  Implemented:', '  - Fixed the finding']);
  assert.deepEqual(stageSummary('REVIEW', withSummary('FINDINGS\nDetails', { problems: [
    { kind: 'Major', summary: 'Race in A.java:10' }, { kind: 'Minor', summary: 'Missing test' }, { kind: 'Major', summary: 'Leak in B.java:5' },
  ] })), [
    'REVIEW: FINDINGS; 3 problems (2 Major, 1 Minor)', '  Problems:',
    '  - [Major] Race in A.java:10', '  - [Minor] Missing test', '  - [Major] Leak in B.java:5',
  ]);
  assert.deepEqual(stageSummary('REVIEW', withEvidence(withSummary('CLEAN', { problems: [] }))), ['REVIEW: CLEAN; no problems found']);
  assert.deepEqual(stageSummary('VERIFY', withSummary('PASS', { problems: [] })), ['VERIFY: PASS; no problems found']);
  assert.deepEqual(stageSummary('DELIVER', withSummary('DELIVERED', { pr: 'https://github.com/owner/repo/pull/7', problems: [] })),
    ['DELIVER: DELIVERED; no problems found; PR: https://github.com/owner/repo/pull/7']);
  assert.deepEqual(stageSummary('DELIVER', withSummary('BLOCKED', { pr: null, problems: [{ kind: 'blocker', summary: 'Push rejected' }] })),
    ['DELIVER: BLOCKED; 1 problem (1 blocker)', '  Problems:', '  - [blocker] Push rejected']);
});

test('missing, malformed or duplicate stage summaries fall back without changing the status', () => {
  const fallback = (stage: Stage, status: string) => [`${stage}: ${status} (no valid stage-summary block; expected exactly one \`\`\`stage-summary fence; see report.md)`];
  assert.deepEqual(stageSummary('REVIEW', 'FINDINGS\nFree-form findings'), fallback('REVIEW', 'FINDINGS'));
  assert.deepEqual(stageSummary('VERIFY', 'FAIL\n```stage-summary\n{not json\n```'), fallback('VERIFY', 'FAIL'));
  // Regression: a valid VERIFY payload under a ```json fence is not a stage summary.
  const jsonFenced = ['INCOMPLETE', 'Report', '```json', JSON.stringify({ checks: ['Synthetic'], limitations: [] }), '```',
    '```json', JSON.stringify({ problems: [{ kind: 'unverified', summary: 'Manual check not run' }] }), '```'].join('\n');
  assert.deepEqual(stageSummary('VERIFY', jsonFenced), fallback('VERIFY', 'INCOMPLETE'));
  const duplicate = withSummary(withSummary('PASS', { problems: [] }), { problems: [] });
  assert.deepEqual(stageSummary('VERIFY', duplicate), fallback('VERIFY', 'PASS'));
  assert.deepEqual(stageSummary('REVIEW', withSummary('FINDINGS', { problems: [{ kind: '', summary: 'Unnamed kind' }] })), fallback('REVIEW', 'FINDINGS'));
  assert.deepEqual(stageSummary('REVIEW', withSummary('FINDINGS', { problems: [{ kind: 'Major', summary: 'x'.repeat(513) }] })), fallback('REVIEW', 'FINDINGS'));
  assert.deepEqual(stageSummary('DELIVER', 'DELIVERED\nPR https://github.com/owner/repo/pull/12 is open; CI pending'),
    ['DELIVER: DELIVERED (no valid stage-summary block; expected exactly one \`\`\`stage-summary fence; see report.md); PR: https://github.com/owner/repo/pull/12']);
  assert.deepEqual(stageSummary('REVIEW', '**CLEAN**'), ['REVIEW: no valid first-line status']);
});

test('stage summaries strip terminal control characters from model text', () => {
  const lines = stageSummary('REVIEW', withSummary('FINDINGS', { problems: [{ kind: 'Major\u001b[31m', summary: 'Line one\nline two\u0007' }] }));
  assert.deepEqual(lines, ['REVIEW: FINDINGS; 1 problem (1 Major [31m)', '  Problems:', '  - [Major [31m] Line one line two']);
  assert.ok(lines.every(line => !/[\u0000-\u001f\u007f-\u009f]/.test(line)));
});

test('Claude prompts bind the shared skill file and otherwise match Codex prompts', () => {
  for (const stage of Object.keys(ROLES) as Stage[]) {
    const request = { ...BASE, stage, task: 'Synthetic task', verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [] };
    const [binding, ...rest] = stagePrompt(request, '/project', 'claude').split('\n\n');
    assert.equal(binding, `Read ${join('/project', '.agents/skills', ROLES[stage].skill, 'SKILL.md')} in full and follow it as the ${ROLES[stage].skill} workflow skill. Resolve relative links in it against its directory.`);
    assert.ok(!rest.join('\n\n').includes(`$${ROLES[stage].skill}`));
    assert.deepEqual(rest, stagePrompt(request, '/project').split('\n\n').slice(1));
  }
});

test('implementation prompts separate developer validation from independent acceptance for both policies', () => {
  for (const stage of Object.keys(ROLES) as Stage[]) {
    for (const verificationRequired of [false, true]) {
      const prompt = stagePrompt({ ...BASE, stage, task: 'Synthetic task', verificationRequired,
        repairRound: 0, maxRepairRounds: 3, handoffs: [] }, '/project');
      if (stage === 'IMPLEMENT' || stage === 'IMPLEMENT_FIXES') {
        assert.match(prompt, /verificationRequired controls the later independent VERIFY stage after REVIEW=CLEAN/);
        assert.match(prompt, /Do not treat independent verification or manual acceptance checks as implementation blockers/);
        assert.match(prompt, /AGENTS\.md or the task explicitly defines them as implementation-stage gates/);
        assert.match(prompt, /required developer self-validation passed, report READY_FOR_REVIEW/);
        assert.match(prompt, /explicitly list verification gaps, including manual UI acceptance not performed/);
        assert.match(prompt, /READY_FOR_REVIEW means ready for independent review, not full task acceptance/);
      } else {
        assert.doesNotMatch(prompt, /report READY_FOR_REVIEW/);
      }
    }
  }
});

test('unavailable manual acceptance allows review then stops at independent verification', async () => {
  const run = scripted(['READY_FOR_REVIEW\nAutomated developer self-validation passed.\n' +
    'Verification gaps: manual UI acceptance not performed.', 'CLEAN',
    'INCOMPLETE\nRequired manual UI acceptance unavailable.']);
  const result = await run.result;
  assert.equal(result.status, 'INCOMPLETE');
  assert.equal(result.repairRounds, 0);
  assert.deepEqual(run.requests.map(r => r.stage), ['IMPLEMENT', 'REVIEW', 'VERIFY']);
  assert.match(run.requests[1].handoffs[0].report, /manual UI acceptance not performed/);
});

test('Codex adapter selects requested models/efforts and consumes only the final report file', async t => {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-adapter-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const fake = join(root, 'fake executor.cjs');
  writeFileSync(fake, `const fs = require('node:fs');
const args = process.argv.slice(2);
const output = args[args.indexOf('--output-last-message') + 1];
fs.writeFileSync(output + '.args', JSON.stringify(args));
fs.writeFileSync(output + '.input', fs.readFileSync(0, 'utf8'));
fs.writeFileSync(output, 'CLEAN\\nSynthetic final report');
console.log('FAIL - this log must never be parsed as the report');
`);
  const runner = createCodexRunner({ repository: root, runDirectory: root, command: [process.execPath, fake] });
  for (const stage of Object.keys(ROLES) as Stage[]) {
    assert.equal(await runner({ ...BASE, stage, task: 'Task with `literal` $symbols', verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [] }), 'CLEAN\nSynthetic final report');
    const stageDirectory = readdirSync(root).find(name => name.includes(`-${stage}-`))!;
    const reportFile = join(root, stageDirectory, 'report.md.args');
    const args = JSON.parse(readFileSync(reportFile, 'utf8'));
    assert.equal(args[args.indexOf('--model') + 1], ROLES[stage].model);
    assert.equal(args[args.indexOf('--config') + 1], `model_reasoning_effort="${ROLES[stage].effort}"`);
    assert.equal(args.includes('--approve-for-me'), ROLES[stage].escalation === 'allowed');
    if (ROLES[stage].escalation === 'never') {
      assert.equal(args[args.indexOf('--sandbox') + 1], stage === 'REVIEW' ? 'read-only' : 'workspace-write');
      assert.ok(args.includes('approval_policy="never"'));
    } else {
      assert.ok(!args.includes('--sandbox'), '--approve-for-me must not be combined with --sandbox');
    }
    assert.ok(args.includes('--ephemeral'));
    assert.ok(!args.includes('--add-dir'));
    assert.equal(dirname(args[args.indexOf('--output-last-message') + 1]), join(root, stageDirectory));
    assert.ok(!args.some((arg: string) => arg.startsWith('--dangerously')));
    assert.match(readFileSync(reportFile.replace(/\.args$/, '.input'), 'utf8'), /Task with `literal` \$symbols/);
  }
});

test('full workflow uses sequential fake CLI processes and propagates PR/CI report', async t => {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-workflow-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const fake = join(root, 'workflow.cjs');
  writeFileSync(fake, `const fs = require('node:fs');
const input = fs.readFileSync(0, 'utf8');
const stage = input.match(/Execute only stage ([A-Z_]+)/)[1];
const statuses = { IMPLEMENT: 'READY_FOR_REVIEW', REVIEW: 'CLEAN', VERIFY: 'PASS', DELIVER: 'DELIVERED' };
const args = process.argv.slice(2);
const fence = String.fromCharCode(96).repeat(3);
let report = statuses[stage] + '\\nSynthetic PR; CI=PENDING';
if (stage === 'REVIEW' || stage === 'VERIFY') report += '\\n' + fence + 'delivery-handoff\\n' + JSON.stringify({ checks: ['Synthetic check passed'], limitations: [] }) + '\\n' + fence;
fs.writeFileSync(args[args.indexOf('--output-last-message') + 1], report);
`);
  const { repository } = gitFixture(t);
  const runner = createCodexRunner({ repository, runDirectory: root, command: [process.execPath, fake] });
  const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: runner });
  assert.equal(result.status, 'DELIVERED');
  assert.deepEqual(result.history.map(h => h.stage), ['IMPLEMENT', 'REVIEW', 'VERIFY', 'DELIVER']);
  assert.match(result.history.at(-1)!.report, /CI=PENDING/);
  assert.equal(readdirSync(root).filter(name => /^\d+-[A-Z]+-/.test(name)).length, 4);
});

test('stage runners print each stage summary and execution failures to the console', async t => {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-summary-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const fake = join(root, 'summary.cjs');
  writeFileSync(fake, `const fs = require('node:fs');
const input = fs.readFileSync(0, 'utf8');
const stage = input.match(/Execute only stage ([A-Z_]+)/)[1];
if (stage === 'VERIFY') process.exit(3);
const fence = String.fromCharCode(96).repeat(3);
const args = process.argv.slice(2);
fs.writeFileSync(args[args.indexOf('--output-last-message') + 1], 'FINDINGS\\n' + fence + 'stage-summary\\n' + JSON.stringify({ problems: [{ kind: 'Minor', summary: 'Synthetic finding' }] }) + '\\n' + fence);
`);
  const lines: string[] = [];
  t.mock.method(console, 'log', (line: string) => { lines.push(line); });
  const runner = createCodexRunner({ repository: root, runDirectory: root, command: [process.execPath, fake] });
  const request = { ...BASE, task: 'Synthetic', verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [] };
  await runner({ ...request, stage: 'REVIEW' });
  await assert.rejects(runner({ ...request, stage: 'VERIFY' }));
  assert.deepEqual(lines.filter(line => !/^[A-Z_]+ \(/.test(line)), [
    'REVIEW: FINDINGS; 1 problem (1 Minor)', '  Problems:', '  - [Minor] Synthetic finding',
    'VERIFY: execution failed: Codex execution did not complete successfully',
  ]);
});

test('delivery preserves unrelated dirty work without requiring a clean checkout', async t => {
  const { repository } = gitFixture(t);
  const root = mkdtempSync(join(tmpdir(), 'fractallens-delivery-guard-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const fake = join(root, 'delivery.cjs');
  writeFileSync(fake, "const fs=require('node:fs'); const a=process.argv.slice(2); fs.writeFileSync(a[a.indexOf('--output-last-message')+1], 'DELIVERED');");
  writeFileSync(join(repository, 'uncommitted.txt'), 'Unrelated user notes');
  const runner = createCodexRunner({ repository, runDirectory: root, command: [process.execPath, fake] });
  assert.equal(await runner({ ...BASE, stage: 'DELIVER', task: 'Synthetic', verificationRequired: false, repairRound: 0, maxRepairRounds: 3, handoffs: [], candidate: CANDIDATE }), 'DELIVERED');
  assert.equal(readFileSync(join(repository, 'uncommitted.txt'), 'utf8'), 'Unrelated user notes');
});

test('nonzero exit, missing report, missing executable and timeout stop the workflow', async t => {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-executor-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const fake = join(root, 'failure.cjs');
  for (const body of ['process.exit(7)', '', 'setInterval(() => {}, 1000)']) {
    writeFileSync(fake, body);
    const runner = createCodexRunner({ repository: root, runDirectory: mkdtempSync(join(root, 'run-')), command: [process.execPath, fake], timeoutMs: 500 });
    const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: runner });
    assert.equal(result.status, 'INCOMPLETE');
    assert.equal(result.history.length, 0);
    const timedOut = body.includes('setInterval');
    assert.equal(/Codex stage timed out after 0\.5 seconds \(--stage-timeout-minutes\)/.test(result.reason), timedOut, result.reason);
  }
  const runner = createCodexRunner({ repository: root, runDirectory: root, command: [join(root, 'missing-codex')] });
  assert.equal((await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: runner })).status, 'INCOMPLETE');
});

test('Claude adapter selects requested models/efforts and permission boundaries and consumes only the JSON result', async t => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'fractallens-claude-adapter-test-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const repository = join(root, 'repository');
  const runDirectory = join(root, 'run');
  mkdirSync(repository);
  mkdirSync(runDirectory);
  const fake = join(root, 'fake executor.cjs');
  writeFileSync(fake, `const fs = require('node:fs');
const input = fs.readFileSync(0, 'utf8');
const stage = input.match(/Execute only stage ([A-Z_]+)/)[1];
fs.writeFileSync(require('node:path').join(${JSON.stringify(root)}, stage + '.json'), JSON.stringify({ args: process.argv.slice(2), input, cwd: process.cwd() }));
console.log(JSON.stringify({ type: 'system', subtype: 'init' }));
console.error('FAIL - stderr must never be parsed as the report');
console.log(JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: 'CLEAN\\nSynthetic final report' }));
`);
  const runner = createClaudeRunner({ repository, runDirectory, command: [process.execPath, fake] });
  for (const stage of Object.keys(ROLES) as Stage[]) {
    assert.equal(await runner({ ...BASE, stage, task: 'Task with `literal` $symbols', verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [] }), 'CLEAN\nSynthetic final report');
    const { args, input, cwd } = JSON.parse(readFileSync(join(root, `${stage}.json`), 'utf8'));
    const value = (flag: string) => args[args.indexOf(flag) + 1];
    assert.equal(realpathSync(cwd), repository);
    assert.equal(value('--model'), CLAUDE_MODELS[stage].model);
    assert.equal(value('--effort'), CLAUDE_MODELS[stage].effort);
    assert.equal(value('--output-format'), 'stream-json');
    assert.equal(value('--permission-prompts'), 'none');
    assert.equal(value('--disallowedTools'), 'Agent');
    for (const flag of ['--print', '--verbose', '--no-session-persistence', '--disable-slash-commands']) assert.ok(args.includes(flag), flag);
    const settings = JSON.parse(value('--settings'));
    assert.equal(settings.autoMemoryEnabled, false);
    if (ROLES[stage].escalation === 'never') {
      assert.equal(value('--permission-mode'), 'dontAsk');
      assert.equal(value('--tools'), 'Bash,Read,Grep,Glob');
      assert.equal(value('--allowedTools'), 'Bash,Read,Grep,Glob');
      assert.ok(args.includes('--strict-mcp-config'));
      const { filesystem, ...sandbox } = settings.sandbox;
      assert.deepEqual(sandbox, { enabled: true, failIfUnavailable: true, allowUnsandboxedCommands: false, autoAllowBashIfSandboxed: true });
      assert.deepEqual(filesystem, stage === 'REVIEW'
        ? { denyWrite: [`/${repository}`, `/${runDirectory}`] } : { allowWrite: [`/${runDirectory}`] });
    } else {
      assert.equal(value('--permission-mode'), 'auto');
      assert.ok(!args.includes('--tools'));
      assert.ok(!args.includes('--strict-mcp-config'));
      assert.equal(settings.sandbox, undefined);
    }
    assert.ok(!args.some((arg: string) => /^--(dangerously|allow-dangerously|add-dir)/.test(arg) || arg === 'bypassPermissions'));
    assert.ok(input.startsWith(`Read ${join(repository, '.agents/skills', ROLES[stage].skill, 'SKILL.md')} in full`));
    assert.match(input, /Task with `literal` \$symbols/);
    const stageDirectory = join(runDirectory, readdirSync(runDirectory).find(name => name.includes(`-${stage}-`))!);
    assert.equal(readFileSync(join(stageDirectory, 'report.md'), 'utf8'), 'CLEAN\nSynthetic final report');
    const stream = readFileSync(join(stageDirectory, 'execution.log'), 'utf8').trimEnd().split('\n').map(line => JSON.parse(line));
    assert.deepEqual(stream.map(event => event.type), ['system', 'result']);
    assert.deepEqual(JSON.parse(readFileSync(join(stageDirectory, 'output.json'), 'utf8')), stream[1]);
    assert.match(readFileSync(join(stageDirectory, 'stderr.log'), 'utf8'), /stderr must never be parsed/);
  }
});

test('Claude runner uses the last result event and ignores trailing lifecycle events', async t => {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-claude-result-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const fake = join(root, 'result.cjs');
  const event = (fields: object) => `console.log(${JSON.stringify(JSON.stringify(fields))});`;
  const result = (index: number, report: string) => event({ type: 'result', subtype: 'success', is_error: false, result_index: index, result: report });
  const lifecycle = event({ type: 'command_lifecycle', state: 'completed' });
  // Claude Code wakes an agent that ended its turn while waiting for a background task,
  // so a stage can emit several results; only the last one is the stage's report.
  for (const [body, report, index] of [
    [`${event({ type: 'system', subtype: 'init' })} ${result(0, 'CLEAN\nOnly report')} ${lifecycle}`, 'CLEAN\nOnly report', 0],
    [`${result(0, 'FAIL\nInterim')} ${event({ type: 'assistant' })} ${result(1, 'FAIL\nInterim again')} ${result(2, 'CLEAN\nFinal report')} ${lifecycle} ${lifecycle}`,
      'CLEAN\nFinal report', 2],
  ] as const) {
    writeFileSync(fake, body);
    const runDirectory = mkdtempSync(join(root, 'run-'));
    const runner = createClaudeRunner({ repository: root, runDirectory, command: [process.execPath, fake] });
    assert.equal(await runner({ ...BASE, stage: 'REVIEW', task: 'Synthetic', verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [] }), report, body);
    const stageDirectory = join(runDirectory, readdirSync(runDirectory).find(name => name.includes('-REVIEW-'))!);
    assert.equal(readFileSync(join(stageDirectory, 'report.md'), 'utf8'), report, body);
    assert.equal(JSON.parse(readFileSync(join(stageDirectory, 'output.json'), 'utf8')).result_index, index, body);
    assert.ok(!readdirSync(stageDirectory).includes('error.txt'), body);
  }
});

test('Claude error results, malformed output, nonzero exit, missing executable and timeout stop the workflow', async t => {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-claude-executor-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const fake = join(root, 'failure.cjs');
  const output = (fields: object) => `console.log(${JSON.stringify(JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: 'READY_FOR_REVIEW', ...fields }))});`;
  const lifecycle = `console.log(${JSON.stringify(JSON.stringify({ type: 'command_lifecycle', state: 'completed' }))});`;
  for (const body of [output({ is_error: true }), output({ subtype: 'error_max_turns' }), output({ result: undefined }),
    output({ type: 'assistant' }), 'console.log("READY_FOR_REVIEW")', `${output({})} process.exit(7);`, '', 'setInterval(() => {}, 1000)',
    `${output({})} console.log('{"type":"assistant"}');`, output({}).replace('console.log', 'console.error'),
    `${output({})} setInterval(() => {}, 1000);`,
    // A trailing lifecycle event must not hide a failed final result, a missing result or non-JSON output.
    `${output({})} ${output({ is_error: true })} ${lifecycle}`, `${output({})} ${output({ subtype: 'error_during_execution' })} ${lifecycle}`,
    lifecycle, `${output({ type: 'assistant' })} ${lifecycle}`, `${output({})} ${lifecycle} console.log('READY_FOR_REVIEW');`,
    `${output({})} console.log('READY_FOR_REVIEW'); ${lifecycle}`, `${output({})} console.log('{"type":"assistant"}'); ${lifecycle}`]) {
    writeFileSync(fake, body);
    const runDirectory = mkdtempSync(join(root, 'run-'));
    const runner = createClaudeRunner({ repository: root, runDirectory, command: [process.execPath, fake], timeoutMs: 500 });
    const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: runner });
    assert.equal(result.status, 'INCOMPLETE', body);
    assert.equal(result.history.length, 0, body);
    const stageDirectory = join(runDirectory, readdirSync(runDirectory).find(name => name.includes('-IMPLEMENT-'))!);
    assert.ok(readdirSync(stageDirectory).includes('error.txt'), body);
    assert.ok(!readdirSync(stageDirectory).includes('report.md'), body);
    const timedOut = body.includes('setInterval');
    assert.equal(/Claude Code stage timed out after 0\.5 seconds \(--stage-timeout-minutes\)/.test(result.reason), timedOut, result.reason);
    assert.equal(readFileSync(join(stageDirectory, 'error.txt'), 'utf8').startsWith('Stage timed out'), timedOut, body);
  }
  const runner = createClaudeRunner({ repository: root, runDirectory: root, command: [join(root, 'missing-claude')] });
  assert.equal((await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: runner })).status, 'INCOMPLETE');
});

for (const [name, createRunner] of [['Claude Code', createClaudeRunner], ['Codex', createCodexRunner]] as const) {
  test(`${name} timeout kills tool commands in their own process groups and keeps the partial stream`, async t => {
    if (process.platform === 'win32') { t.skip('Process groups and ps are POSIX-only; Windows uses taskkill /t'); return; }
    const root = mkdtempSync(join(tmpdir(), 'fractallens-executor-timeout-test-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    const pids = join(root, 'tool-pids.json');
    // Mirrors an executor tool command: a separate process group with its own child,
    // both holding the inherited stage stdout.
    const tool = join(root, 'tool.cjs');
    writeFileSync(tool, `const nested = require('node:child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'inherit' });
require('node:fs').writeFileSync(process.argv[2], JSON.stringify([process.pid, nested.pid]));
setInterval(() => {}, 1000);
`);
    const fake = join(root, 'fake-executor.cjs');
    writeFileSync(fake, `console.log(JSON.stringify({ type: 'system', subtype: 'init' }));
require('node:child_process').spawn(process.execPath, [${JSON.stringify(tool)}, ${JSON.stringify(pids)}], { detached: true, stdio: ['ignore', 'inherit', 'inherit'] });
setInterval(() => {}, 1000);
`);
    const runDirectory = mkdtempSync(join(root, 'run-'));
    const runner = createRunner({ repository: root, runDirectory, command: [process.execPath, fake], timeoutMs: 3000 });
    const result = await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: runner });
    assert.equal(result.status, 'INCOMPLETE');
    assert.ok(result.reason.includes(`${name} stage timed out after 3 seconds (--stage-timeout-minutes)`), result.reason);
    const stageDirectory = join(runDirectory, readdirSync(runDirectory).find(item => item.includes('-IMPLEMENT-'))!);
    assert.match(readFileSync(join(stageDirectory, 'error.txt'), 'utf8'), /^Stage timed out after 3 seconds .*process tree, including tool commands, was killed/);
    assert.equal(JSON.parse(readFileSync(join(stageDirectory, 'execution.log'), 'utf8')).type, 'system');
    assert.ok(!readdirSync(stageDirectory).includes('output.json'));
    assert.ok(!readdirSync(stageDirectory).includes('report.md'));
    const live = (pid: number) => !/^(|Z.*)$/.test(spawnSync('ps', ['-o', 'stat=', '-p', String(pid)], { encoding: 'utf8' }).stdout.trim());
    const toolPids: number[] = JSON.parse(readFileSync(pids, 'utf8'));
    t.after(() => toolPids.forEach(pid => { try { process.kill(pid, 'SIGKILL'); } catch { /* Already exited. */ } }));
    // SIGKILL delivery is asynchronous; wait for the concrete state instead of a fixed delay.
    for (const deadline = Date.now() + 5000; toolPids.some(live) && Date.now() < deadline;) await new Promise(resolve => setTimeout(resolve, 20));
    assert.deepEqual(toolPids.filter(live), []);
  });
}

test('CLI dry-run plans roles without executing Codex; invalid flags exit nonzero', async () => {
  const script = join(dirname(fileURLToPath(import.meta.url)), 'task-orchestrator.ts');
  for (const skip of [false, true]) {
    const result = spawnSync(process.execPath, [script, '--task', 'Synthetic', '--dry-run', '--codex', 'must-not-be-invoked', ...(skip ? ['--skip-verification'] : [])], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    const plan = JSON.parse(result.stdout);
    assert.equal(plan.verificationRequired, !skip);
    assert.equal(plan.stages[0].model, 'gpt-6.1-sol');
    assert.equal(plan.stages[1].model, 'gpt-6.1-sol');
    assert.equal(plan.stages.at(-1).model, 'gpt-6-luna');
    assert.deepEqual(plan.stages.map((item: { stage: string }) => item.stage), skip ? ['IMPLEMENT', 'REVIEW', 'DELIVER'] : ['IMPLEMENT', 'REVIEW', 'VERIFY', 'DELIVER']);
    assert.equal(plan.executor, 'codex');
  }
  const claude = spawnSync(process.execPath, [script, '--task', 'Synthetic', '--dry-run', '--executor', 'claude', '--claude', 'must-not-be-invoked'], { encoding: 'utf8' });
  assert.equal(claude.status, 0, claude.stderr);
  const plan = JSON.parse(claude.stdout);
  assert.equal(plan.executor, 'claude');
  for (const item of plan.stages as { stage: Stage; model: string; effort: string; sandbox: string; escalation: string }[]) {
    assert.deepEqual([item.model, item.effort, item.sandbox, item.escalation],
      [CLAUDE_MODELS[item.stage].model, CLAUDE_MODELS[item.stage].effort, ROLES[item.stage].sandbox, ROLES[item.stage].escalation]);
  }
  await assert.rejects(main(['--task', 'Synthetic', '--dry-run', '--executor', 'gemini']), /--executor must be codex or claude/);
  await assert.rejects(main(['--task', 'Synthetic', '--max-repair-rounds', '4']));
  for (const minutes of ['0', '1441', '1.5', 'thirty']) {
    await assert.rejects(main(['--task', 'Synthetic', '--dry-run', '--stage-timeout-minutes', minutes]), /Stage timeout must be an integer from 1 to 1440 minutes/);
  }
  await assert.rejects(main(['--task', 'Synthetic', '--dry-run', '--stage-timeout-seconds', '1800']), /Unknown option '--stage-timeout-seconds'/);
  await assert.rejects(main(['--unknown']));
  const invalid = spawnSync(process.execPath, [script, '--unknown'], { encoding: 'utf8' });
  assert.equal(invalid.status, 1);
  assert.match(invalid.stderr, /Unknown option '--unknown'/);
});


function remoteFixture(t: TestContext) {
  const fixture = gitFixture(t);
  const remote = join(dirname(fixture.repository), 'remote.git');
  fixture.git('init', '--bare', remote);
  fixture.git('branch', 'release-2', 'main');
  fixture.git('remote', 'add', 'origin', remote);
  fixture.git('push', 'origin', 'main', 'release-2');
  const initial = fixture.git('rev-parse', 'origin/release-2');
  const advance = () => {
    fixture.git('commit', '--allow-empty', '-m', 'Synthetic remote advance');
    const revision = fixture.git('rev-parse', 'HEAD');
    fixture.git('push', 'origin', 'HEAD:refs/heads/release-2');
    // Simulate a tracking ref that has not observed a change made by another client.
    fixture.git('update-ref', 'refs/remotes/origin/release-2', initial);
    return revision;
  };
  return { ...fixture, remote, initial, advance };
}

test('remote base is refreshed once at start, normalized and frozen by SHA', t => {
  const { repository, git, initial, advance } = remoteFixture(t);
  const current = advance();
  assert.equal(git('rev-parse', 'origin/release-2'), initial);
  const base = prepareBase(repository, 'origin/release-2');
  assert.equal(base.baseRef, 'origin/release-2');
  assert.equal(base.prBase, 'release-2');
  assert.equal(base.baseRevision, current);
  const candidate = computeCandidateIdentity(repository, base.baseRevision);
  git('update-ref', 'refs/remotes/origin/release-2', initial);
  assert.deepEqual(computeCandidateIdentity(repository, base.baseRevision), candidate);
  base.assertFresh();
  assert.equal(git('rev-parse', 'origin/release-2'), current);
});

test('base branch names survive in every request and delivery prompt', async () => {
  const requests: Request[] = [];
  const reports = ['READY_FOR_REVIEW', withEvidence('CLEAN'), 'DELIVERED'];
  let refreshes = 0;
  const result = await runWorkflow({ ...BASE, baseRef: 'origin/release-2', prBase: 'release-2',
    task: 'Synthetic', verificationRequired: false, computeCandidate: () => CANDIDATE,
    beforeDelivery: () => { refreshes++; }, runStage: async request => {
      requests.push(request);
      assert.equal(refreshes, request.stage === 'DELIVER' ? 1 : 0);
      return reports.shift()!;
    },
  });
  assert.equal(result.status, 'DELIVERED');
  assert.ok(requests.every(request => request.baseRef === 'origin/release-2' && request.prBase === 'release-2'));
  assert.match(stagePrompt(requests.at(-1)!, '/project'), /Intended pull-request base branch: release-2/);
});

test('remote base advance during review or verification stops before delivery', async t => {
  for (const verificationRequired of [false, true]) {
    const { repository, initial, advance } = remoteFixture(t);
    const base = prepareBase(repository, 'refs/remotes/origin/release-2');
    const stages: Stage[] = [];
    const result = await runWorkflow({ ...base, beforeDelivery: base.assertFresh, task: 'Synthetic', verificationRequired,
      computeCandidate: () => computeCandidateIdentity(repository, base.baseRevision), runStage: async request => {
        stages.push(request.stage);
        assert.equal(request.baseRevision, initial);
        assert.equal(request.candidate?.baseRevision ?? initial, initial);
        if (request.stage === (verificationRequired ? 'VERIFY' : 'REVIEW')) advance();
        return request.stage === 'IMPLEMENT' ? 'READY_FOR_REVIEW'
          : withEvidence(request.stage === 'REVIEW' ? 'CLEAN' : 'PASS');
      },
    });
    assert.equal(result.status, 'INCOMPLETE');
    assert.match(result.reason, /Intended PR base changed/);
    assert.ok(!stages.includes('DELIVER'));
  }
});

test('unavailable remote base fails at startup or before delivery, without publication', async t => {
  const { repository, git } = remoteFixture(t);
  const base = prepareBase(repository, 'origin/release-2');
  git('remote', 'set-url', 'origin', join(dirname(repository), 'missing-remote.git'));
  assert.throws(() => prepareBase(repository, 'origin/release-2'), /Cannot resolve or refresh/);
  const stages: Stage[] = [];
  const reports = ['READY_FOR_REVIEW', withEvidence('CLEAN')];
  const result = await runWorkflow({ ...base, beforeDelivery: base.assertFresh, task: 'Synthetic', verificationRequired: false,
    computeCandidate: () => computeCandidateIdentity(repository, base.baseRevision), runStage: async request => {
      stages.push(request.stage);
      return reports.shift()!;
    },
  });
  assert.equal(result.status, 'INCOMPLETE');
  assert.match(result.reason, /Cannot resolve or refresh/);
  assert.deepEqual(stages, ['IMPLEMENT', 'REVIEW']);
});

test('local branches, tags and commit-only bases are rejected', t => {
  const { repository, git } = gitFixture(t);
  git('tag', 'synthetic-base');
  for (const baseRef of ['main', 'refs/heads/main', 'synthetic-base', git('rev-parse', 'HEAD')]) {
    assert.throws(() => prepareBase(repository, baseRef), /must name a remote-tracking branch/);
  }
});

test('CLI rejects a local PR base before invoking any role', async t => {
  const { repository } = gitFixture(t);
  for (const skill of new Set(Object.values(ROLES).map(role => role.skill))) {
    const directory = join(repository, '.agents/skills', skill);
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, 'SKILL.md'), 'Synthetic skill fixture');
  }
  await assert.rejects(main(['--repo', repository, '--task', 'Synthetic', '--base', 'main',
    '--codex', 'must-not-be-invoked']), /must name a remote-tracking branch/);
});

test('candidate checks permit task-only commit while preserving unrelated user work', async t => {
  const { repository, git } = remoteFixture(t);
  writeFileSync(join(repository, 'notes.txt'), 'Unrelated user work');
  const base = prepareBase(repository, 'origin/main');
  const result = await runWorkflow({ ...base, beforeDelivery: base.assertFresh, task: 'Synthetic', verificationRequired: false,
    computeCandidate: () => computeCandidateIdentity(repository, base.baseRevision), runStage: async request => {
      if (request.stage === 'IMPLEMENT') {
        writeFileSync(join(repository, 'tracked.txt'), 'Task implementation');
        return 'READY_FOR_REVIEW';
      }
      if (request.stage === 'REVIEW') return withEvidence('CLEAN');
      git('add', 'tracked.txt');
      git('commit', '-m', 'Synthetic task delivery');
      return 'DELIVERED';
    },
  });
  assert.equal(result.status, 'DELIVERED');
  assert.equal(readFileSync(join(repository, 'notes.txt'), 'utf8'), 'Unrelated user work');
  assert.equal(git('ls-files', 'notes.txt'), '');
  assert.match(git('status', '--porcelain'), /notes.txt/);
});

test('VERIFY writes build artifacts but candidate mutations prevent a resumable checkpoint', async t => {
  for (const mutateSource of [false, true]) {
    const { repository, identity } = gitFixture(t);
    const reports = ['READY_FOR_REVIEW', withEvidence('CLEAN'), 'INCOMPLETE'];
    const result = await runWorkflow({ ...BASE, task: 'Synthetic', computeCandidate: identity, runStage: async request => {
      if (request.stage === 'VERIFY') {
        mkdirSync(join(repository, 'ignored'));
        writeFileSync(join(repository, 'ignored/report.txt'), 'Diagnostic output');
        if (mutateSource) writeFileSync(join(repository, 'tracked.txt'), 'Forbidden mutation');
      }
      return reports.shift()!;
    } });
    assert.equal(result.status, 'INCOMPLETE');
    assert.equal(Boolean(result.checkpoint), !mutateSource);
  }
});

test('resume retains repair history and budget, reassesses human evidence and requires PASS', async () => {
  const paused = await scripted(['READY_FOR_REVIEW', 'FINDINGS', 'READY_FOR_REVIEW', 'CLEAN', 'INCOMPLETE']).result;
  assert.equal(paused.checkpoint?.nextStage, 'VERIFY');
  assert.equal(paused.checkpoint?.repairRounds, 1);
  const requests: Request[] = [];
  const resume = (checkpoint: NonNullable<typeof paused.checkpoint>, reports: string[], humanEvidence?: string) =>
    runWorkflow({ ...BASE, task: 'Synthetic', checkpoint, humanEvidence, computeCandidate: () => CANDIDATE,
      runStage: async request => { requests.push(request); return reports.shift()!; } });
  const again = await resume(paused.checkpoint!, ['INCOMPLETE'], 'Manual macOS UI acceptance passed');
  assert.equal(again.checkpoint?.repairRounds, 1);
  assert.deepEqual(requests.map(r => r.stage), ['VERIFY']);
  assert.match(stagePrompt(requests[0], '/project'), /Manual macOS UI acceptance passed/);
  const delivered = await resume(again.checkpoint!, [withEvidence('PASS'), 'DELIVERED']);
  assert.equal(delivered.status, 'DELIVERED');
  assert.deepEqual(requests.map(r => r.stage), ['VERIFY', 'VERIFY', 'DELIVER']);
  assert.equal(requests[1].humanEvidence, 'Manual macOS UI acceptance passed');
  assert.equal(delivered.checkpoint, undefined);
  const failed = await resume(paused.checkpoint!, ['FAIL', 'READY_FOR_REVIEW', withEvidence('CLEAN'), 'INCOMPLETE']);
  assert.equal(failed.checkpoint?.repairRounds, 2);
  assert.equal(failed.checkpoint?.humanEvidence, undefined, 'Fixes invalidate manual evidence');
  const exhausted = await runWorkflow({ ...BASE, task: 'Synthetic', checkpoint: failed.checkpoint!,
    maxRepairRounds: 2, computeCandidate: () => CANDIDATE, runStage: async () => 'FAIL' });
  assert.equal(exhausted.status, 'BLOCKED');
  assert.equal(exhausted.checkpoint, undefined);
});

test('invalid checkpoint histories and stale candidates never invoke a resumed executor', async () => {
  const paused = await scripted(['READY_FOR_REVIEW', 'CLEAN', 'INCOMPLETE']).result;
  for (const checkpoint of [
    { ...paused.checkpoint!, repairRounds: 1 },
    { ...paused.checkpoint!, history: [] },
    { ...paused.checkpoint!, history: paused.history.slice(0, -1) },
    { ...paused.checkpoint!, nextStage: 'DELIVER' as 'VERIFY' },
    { ...paused.checkpoint!, candidate: { ...CANDIDATE, contentId: 'other' } },
  ]) {
    await assert.rejects(runWorkflow({ ...BASE, task: 'Synthetic', checkpoint,
      computeCandidate: () => CANDIDATE, runStage: async () => assert.fail('Must not invoke') }));
  }
  const stale = await runWorkflow({ ...BASE, task: 'Synthetic', checkpoint: paused.checkpoint,
    computeCandidate: () => ({ ...CANDIDATE, contentId: 'changed' }), runStage: async () => assert.fail('Must not invoke') });
  assert.match(stale.reason, /Candidate changed before VERIFY/);
  assert.equal(stale.checkpoint, undefined);
});

function pausedCli(t: TestContext, executor?: 'codex' | 'claude') {
  const fixture = remoteFixture(t);
  const { repository } = fixture;
  const root = dirname(repository);
  for (const skill of new Set(Object.values(ROLES).map(role => role.skill))) {
    const directory = join(repository, '.agents/skills', skill);
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, 'SKILL.md'), 'Synthetic skill');
  }
  const log = join(root, 'calls.jsonl');
  const fake = join(root, 'fake-codex');
  writeFileSync(fake, `#!/usr/bin/env node
const fs = require('node:fs');
const input = fs.readFileSync(0, 'utf8');
const stage = input.match(/Execute only stage ([A-Z_]+)/)[1];
const log = ${JSON.stringify(log)};
const previous = fs.existsSync(log) ? fs.readFileSync(log, 'utf8') : '';
const args = process.argv.slice(2);
fs.appendFileSync(log, JSON.stringify({stage, input, executor: args.includes('--print') ? 'claude' : 'codex'}) + '\\n');
let status = stage === 'REVIEW' ? (previous.includes('"stage":"REVIEW"') ? 'CLEAN' : 'FINDINGS')
  : stage === 'VERIFY' ? (input.includes('Manual acceptance passed') ? 'PASS' : 'INCOMPLETE')
  : stage === 'DELIVER' ? 'DELIVERED' : 'READY_FOR_REVIEW';
const fence = String.fromCharCode(96).repeat(3);
if (['CLEAN', 'PASS'].includes(status)) status += '\\n' + fence + 'delivery-handoff\\n' + JSON.stringify({checks:['Synthetic checks passed'], limitations:[]}) + '\\n' + fence;
if (args.includes('--print')) console.log(JSON.stringify({type: 'result', subtype: 'success', is_error: false, result: status}));
else fs.writeFileSync(args[args.indexOf('--output-last-message') + 1], status);
`);
  chmodSync(fake, 0o755);
  const script = join(dirname(fileURLToPath(import.meta.url)), 'task-orchestrator.ts');
  const cli = (...args: string[]) => spawnSync(process.execPath, [script, ...args, '--codex', fake, '--claude', fake], { encoding: 'utf8' });
  const initial = cli('--repo', repository, '--task', 'Synthetic resume', ...(executor ? ['--executor', executor] : []));
  assert.equal(initial.status, 1, initial.stderr);
  const runDirectory = initial.stdout.match(/Private run directory: (.+)/)![1];
  t.after(() => rmSync(runDirectory, { recursive: true, force: true }));
  const stateFile = join(runDirectory, 'state.json');
  const state = () => JSON.parse(readFileSync(stateFile, 'utf8'));
  const calls = () => readFileSync(log, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  return { ...fixture, cli, calls, runDirectory, stateFile, state, root };
}

test('CLI resumes a private checkpoint from VERIFY and consumes it after delivery', t => {
  if (process.platform === 'win32') { t.skip('Executable fixture uses a POSIX shebang'); return; }
  const fixture = pausedCli(t);
  const { cli, runDirectory, state, stateFile, calls, root } = fixture;
  assert.equal(state().checkpoint.repairRounds, 1);
  assert.equal(state().checkpoint.nextStage, 'VERIFY');
  assert.equal(state().executor, 'codex');
  // States written before executor selection have no executor field and resume with Codex.
  const { executor: _executor, ...legacy } = state();
  writeFileSync(stateFile, JSON.stringify(legacy, null, 2));
  const snapshot = readFileSync(join(runDirectory, 'state.json'), 'utf8');
  const dryRun = cli('--resume', runDirectory, '--dry-run');
  assert.equal(dryRun.status, 0, dryRun.stderr);
  assert.deepEqual(JSON.parse(dryRun.stdout).stages.map((stage: { stage: Stage }) => stage.stage), ['VERIFY', 'DELIVER']);
  assert.equal(readFileSync(join(runDirectory, 'state.json'), 'utf8'), snapshot);
  assert.equal(calls().length, 5);
  const paused = cli('--resume', runDirectory);
  assert.equal(paused.status, 1, paused.stderr);
  assert.equal(state().checkpoint.history.length, 6);
  const evidence = join(root, 'manual-evidence.md');
  writeFileSync(evidence, 'Manual acceptance passed: macOS; focus, Apply, Cancel and Escape checked.');
  const resumed = cli('--resume', runDirectory, '--human-evidence', evidence);
  assert.equal(resumed.status, 0, resumed.stderr);
  assert.deepEqual(calls().map(call => call.stage), ['IMPLEMENT', 'REVIEW', 'IMPLEMENT_FIXES', 'REVIEW', 'VERIFY', 'VERIFY', 'VERIFY', 'DELIVER']);
  assert.equal(state().checkpoint, null);
  assert.ok(calls().every(call => call.executor === 'codex'));
  const before = calls().length;
  assert.equal(cli('--resume', runDirectory).status, 1);
  assert.equal(calls().length, before, 'Completed delivery must never replay');
});

test('CLI runs and resumes a Claude Code workflow with the executor saved in its checkpoint', t => {
  if (process.platform === 'win32') { t.skip('Executable fixture uses a POSIX shebang'); return; }
  const { cli, runDirectory, state, calls, root } = pausedCli(t, 'claude');
  assert.equal(state().executor, 'claude');
  assert.equal(state().checkpoint.nextStage, 'VERIFY');
  assert.ok(readdirSync(runDirectory).some(name => /^\d+-REVIEW-/.test(name)
    && readFileSync(join(runDirectory, name, 'output.json'), 'utf8').includes('"type":"result"')));
  const dryRun = cli('--resume', runDirectory, '--dry-run');
  assert.equal(dryRun.status, 0, dryRun.stderr);
  const plan = JSON.parse(dryRun.stdout);
  assert.equal(plan.executor, 'claude');
  assert.equal(plan.stages[0].model, CLAUDE_MODELS.VERIFY.model);
  assert.equal(cli('--resume', runDirectory, '--executor', 'codex').status, 1);
  const evidence = join(root, 'manual-evidence.md');
  writeFileSync(evidence, 'Manual acceptance passed: macOS; focus, Apply, Cancel and Escape checked.');
  const resumed = cli('--resume', runDirectory, '--human-evidence', evidence);
  assert.equal(resumed.status, 0, resumed.stderr);
  assert.deepEqual(calls().map(call => call.stage), ['IMPLEMENT', 'REVIEW', 'IMPLEMENT_FIXES', 'REVIEW', 'VERIFY', 'VERIFY', 'DELIVER']);
  assert.ok(calls().every(call => call.executor === 'claude'));
  assert.ok(calls().every(call => call.input.startsWith('Read ')));
  assert.equal(state().executor, 'claude');
  assert.equal(state().checkpoint, null);
});

test('CLI rejects changed source, branch, remote base, locked runs and policy overrides before execution', t => {
  if (process.platform === 'win32') { t.skip('Executable fixture uses a POSIX shebang'); return; }
  const { cli, runDirectory, calls, repository, git, root } = pausedCli(t);
  const before = calls().length;
  for (const args of [['--skip-verification'], ['--task', 'Other'], ['--max-repair-rounds=3'], ['--base', 'origin/main'], ['--repo', repository], ['--executor', 'codex']]) {
    assert.equal(cli('--resume', runDirectory, ...args).status, 1);
  }
  mkdirSync(join(runDirectory, 'active.lock'));
  assert.equal(cli('--resume', runDirectory).status, 1);
  rmSync(join(runDirectory, 'active.lock'), { recursive: true });
  writeFileSync(join(repository, 'tracked.txt'), 'Modified source');
  assert.match(cli('--resume', runDirectory).stderr, /Checkpoint candidate, branch or remote base changed/);
  git('restore', 'tracked.txt');
  git('switch', '-c', 'chore/other');
  assert.equal(cli('--resume', runDirectory).status, 1);
  git('switch', 'chore/synthetic-task');
  git('commit', '--allow-empty', '-m', 'Advance remote main');
  git('push', 'origin', 'HEAD:refs/heads/main');
  assert.equal(cli('--resume', runDirectory).status, 1);
  assert.equal(calls().length, before);
  const evidence = join(root, 'empty.md');
  writeFileSync(evidence, '');
  assert.equal(cli('--resume', runDirectory, '--human-evidence', evidence).status, 1);
});


test('CLI consumes a checkpoint when resumed execution fails, without blind retry', t => {
  if (process.platform === 'win32') { t.skip('Executable fixture uses a POSIX shebang'); return; }
  const { cli, runDirectory, state, root, calls } = pausedCli(t);
  writeFileSync(join(root, 'fake-codex'), '#!/usr/bin/env node\nprocess.exit(7);\n');
  assert.equal(cli('--resume', runDirectory).status, 1);
  assert.equal(state().checkpoint, null);
  assert.equal(cli('--resume', runDirectory).status, 1);
  assert.equal(calls().length, 5);
});
