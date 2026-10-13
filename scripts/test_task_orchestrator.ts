import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test, { type TestContext } from 'node:test';
import { computeCandidateIdentity, createCodexRunner, main, parseStatus, prepareBase, ROLES, runWorkflow, stagePrompt } from './task-orchestrator.ts';
import type { Request, Stage } from './task-orchestrator.ts';

const BASE = { baseRef: 'origin/main', prBase: 'main', baseRevision: 'synthetic-base', beforeDelivery: () => {} };
const CANDIDATE = { baseRevision: 'synthetic-base', mergeBase: 'synthetic-merge-base', contentId: 'synthetic-content' };

function gitFixture(t: TestContext) {
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
    assert.equal(args[args.indexOf('--sandbox') + 1], ROLES[stage].sandbox);
    assert.equal(args.includes('--approve-for-me'), ROLES[stage].sandbox === 'workspace-write');
    if (ROLES[stage].sandbox === 'read-only') assert.ok(args.includes('approval_policy="never"'));
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
  }
  const runner = createCodexRunner({ repository: root, runDirectory: root, command: [join(root, 'missing-codex')] });
  assert.equal((await runWorkflow({ ...BASE, computeCandidate: () => CANDIDATE, task: 'Synthetic', runStage: runner })).status, 'INCOMPLETE');
});

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
  }
  await assert.rejects(main(['--task', 'Synthetic', '--max-repair-rounds', '4']));
  await assert.rejects(main(['--task', 'Synthetic', '--stage-timeout-seconds', '0']));
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
