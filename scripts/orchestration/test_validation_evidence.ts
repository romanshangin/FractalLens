import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test, { type TestContext } from 'node:test';
import { computeCandidateIdentity, createCodexRunner, runWorkflow, stagePrompt } from './task-orchestrator.ts';
import { main as checkMain } from './check.ts';
import { checkDefinition, checkEnvironment, currentEvidence, definitionHash, evidenceSummary, runCheck } from './validation-evidence.ts';
import type { CheckContext, CheckDefinition } from './validation-evidence.ts';
import type { CandidateIdentity } from './task-orchestrator.ts';

function fixture(t: TestContext) {
  const root = mkdtempSync(join(tmpdir(), 'fractallens-evidence-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const repository = join(root, 'repository');
  const runDirectory = join(root, 'run');
  const hooks = join(root, 'hooks');
  for (const path of [repository, runDirectory, hooks]) mkdirSync(path);
  const git = (...args: string[]) => {
    const result = spawnSync('git', ['-c', `core.hooksPath=${hooks}`, '-c', 'commit.gpgsign=false', ...args], { cwd: repository, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git('init', '-b', 'main');
  git('config', 'user.name', 'Synthetic Author');
  git('config', 'user.email', 'noreply@example.org');
  writeFileSync(join(repository, '.gitignore'), 'ignored/\n');
  mkdirSync(join(repository, 'ignored'));
  writeFileSync(join(repository, 'check.cjs'), `const fs = require('node:fs');
const path = 'ignored/count';
fs.writeFileSync(path, String(Number(fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : 0) + 1));
console.log('Tests run: 7, Failures: 0, Errors: 0, Skipped: 2');
process.exit(Number(process.argv[2] || 0));
`);
  git('add', '--all');
  git('commit', '-m', 'Synthetic base');
  const baseRevision = git('rev-parse', 'HEAD');
  git('switch', '-c', 'chore/evidence');
  const context: CheckContext = { version: 1, repository, runDirectory, baseRevision, stage: 'IMPLEMENT' };
  const computeCandidate = () => computeCandidateIdentity(repository, baseRevision);
  const definition = checkDefinition('synthetic-targeted', [process.execPath, 'check.cjs']);
  const run = (overrides: { context?: CheckContext; definition?: CheckDefinition; computeCandidate?: () => CandidateIdentity; fresh?: boolean; timeoutMs?: number } = {}) => runCheck({ context, definition, computeCandidate, ...overrides });
  const count = () => Number(readFileSync(join(repository, 'ignored/count'), 'utf8'));
  return { root, repository, runDirectory, context, computeCandidate, definition, run, count, git };
}

test('exact candidate evidence survives stages, fresh store loads and staging without executing twice', t => {
  const f = fixture(t);
  const first = f.run();
  assert.equal(first.cached, false);
  assert.equal(first.evidence.result, 'PASS');
  assert.equal(first.evidence.summary, 'Tests run: 7, Failures: 0, Errors: 0, Skipped: 2');
  assert.equal(first.evidence.stage, 'IMPLEMENT');
  assert.ok(first.evidence.timestamp);
  assert.deepEqual(first.evidence.candidate, f.computeCandidate());
  assert.match(first.evidence.logHash, /^sha256:/);
  f.git('add', '--all');
  assert.equal(f.run({ context: { ...f.context, stage: 'VERIFY' } }).cached, true);
  const loaded = currentEvidence(f.runDirectory, f.computeCandidate(), f.repository);
  assert.equal(loaded.length, 1);
  assert.equal(loaded[0].result, 'PASS');
  assert.equal(f.count(), 1);
  assert.match(evidenceSummary(loaded), /PASS synthetic-targeted.*IMPLEMENT/);
});

test('edits, base and merge-base changes invalidate successful checks', t => {
  const f = fixture(t);
  f.run();
  writeFileSync(join(f.repository, 'new-source.txt'), 'Candidate fix\n');
  assert.deepEqual(currentEvidence(f.runDirectory, f.computeCandidate(), f.repository), []);
  assert.equal(f.run({ context: { ...f.context, stage: 'IMPLEMENT_FIXES' } }).cached, false);
  for (const key of ['baseRevision', 'mergeBase'] as const) {
    const candidate = { ...f.computeCandidate(), [key]: 'different-revision' };
    assert.equal(f.run({ computeCandidate: () => candidate }).cached, false);
  }
  assert.equal(f.count(), 4);
});

test('command selectors, runtime environment and definition changes prevent cache hits', t => {
  const f = fixture(t);
  f.run();
  assert.equal(f.run({ definition: { ...f.definition, command: [...f.definition.command, '0'] } }).cached, false);
  const original = process.env.JDK_JAVA_OPTIONS;
  t.after(() => { if (original === undefined) delete process.env.JDK_JAVA_OPTIONS; else process.env.JDK_JAVA_OPTIONS = original; });
  process.env.JDK_JAVA_OPTIONS = '-Dsynthetic.evidence=true';
  assert.equal(f.run().cached, false);
  assert.equal(f.count(), 3);
  const registry = join(f.runDirectory, 'evidence/checks.json');
  const entries = JSON.parse(readFileSync(registry, 'utf8'));
  entries.at(-1).checkDefinitionHash = 'obsolete-definition';
  writeFileSync(registry, JSON.stringify(entries));
  assert.equal(currentEvidence(f.runDirectory, f.computeCandidate(), f.repository).length, 0);
});

test('forced diagnostics execute and later failures supersede previous PASS on the same check key', t => {
  const f = fixture(t);
  f.run();
  // Ignored output can control a synthetic failure without changing the candidate.
  const source = join(f.repository, 'check.cjs');
  writeFileSync(source, readFileSync(source, 'utf8').replace("process.exit(Number(process.argv[2] || 0));", "process.exit(fs.existsSync('ignored/fail') ? 1 : 0);"));
  f.run();
  writeFileSync(join(f.repository, 'ignored/fail'), 'fail');
  const failure = f.run({ fresh: true });
  assert.equal(failure.evidence.result, 'FAIL');
  assert.equal(currentEvidence(f.runDirectory, f.computeCandidate(), f.repository)[0].result, 'FAIL');
  assert.equal(f.run().cached, false);
  rmSync(join(f.repository, 'ignored/fail'));
  assert.equal(f.run().evidence.result, 'PASS');
  assert.equal(f.run().cached, true);
  assert.equal(f.count(), 5);
});

test('non-reusable checks never supply a cached PASS, and their failure invalidates an earlier PASS', t => {
  const f = fixture(t);
  f.run();
  const definition = { ...f.definition, reusable: false };
  assert.equal(definitionHash(definition), definitionHash(f.definition));
  assert.equal(f.run({ definition }).cached, false);
  assert.equal(f.run({ definition }).cached, false);
  assert.equal(f.run().cached, false);
  assert.equal(f.count(), 4);
});

test('distinct selectors retain their own evidence while a fresh failure replaces only its exact key', t => {
  const f = fixture(t);
  f.run();
  const anotherSelector = { ...f.definition, command: [...f.definition.command, '0'] };
  f.run({ definition: anotherSelector });
  const entries = currentEvidence(f.runDirectory, f.computeCandidate(), f.repository);
  assert.equal(entries.length, 2);
  assert.ok(entries.every(entry => entry.result === 'PASS'));
  assert.equal(f.run().cached, true);
  assert.equal(f.run({ definition: anotherSelector }).cached, true);
  assert.equal(f.count(), 2);
});

test('candidate mutations during a successful command are recorded INCOMPLETE', t => {
  const f = fixture(t);
  const definition = checkDefinition('mutating-check', [process.execPath, '-e', "require('node:fs').writeFileSync('mutation.txt', 'changed')"]);
  const result = f.run({ definition });
  assert.equal(result.evidence.exitCode, 0);
  assert.equal(result.evidence.result, 'INCOMPLETE');
  assert.match(result.evidence.summary, /Candidate changed/);
  assert.deepEqual(currentEvidence(f.runDirectory, f.computeCandidate(), f.repository), []);
});

test('missing executable, timeout and identity errors cannot produce PASS evidence', t => {
  const f = fixture(t);
  const unavailable = f.run({ definition: checkDefinition('unavailable', [join(f.root, 'missing')]) });
  assert.equal(unavailable.evidence.result, 'INCOMPLETE');
  assert.match(unavailable.evidence.summary, /ENOENT/);
  const timed = f.run({ definition: checkDefinition('timed', [process.execPath, '-e', 'setInterval(() => {}, 1000)']), timeoutMs: 100 });
  assert.equal(timed.evidence.result, 'INCOMPLETE');
  let reads = 0;
  assert.equal(f.run({ computeCandidate: () => { if (++reads > 1) throw new Error('Identity unavailable'); return f.computeCandidate(); } }).evidence.result, 'INCOMPLETE');
});

test('missing or corrupted execution logs are never reused', t => {
  const f = fixture(t);
  const first = f.run();
  writeFileSync(join(f.runDirectory, 'evidence', first.evidence.log), 'Corrupt output');
  assert.equal(currentEvidence(f.runDirectory, f.computeCandidate(), f.repository).length, 0);
  assert.equal(f.run().cached, false);
  const latest = f.run();
  rmSync(join(f.runDirectory, 'evidence', latest.evidence.log));
  assert.equal(f.run().cached, false);
  assert.equal(f.count(), 3);
});

test('malformed registries, path traversal and concurrent writers fail closed', t => {
  const f = fixture(t);
  f.run();
  const path = join(f.runDirectory, 'evidence/checks.json');
  const entries = JSON.parse(readFileSync(path, 'utf8'));
  entries[0].log = '../outside.log';
  writeFileSync(path, JSON.stringify(entries));
  assert.throws(() => f.run(), /Invalid automated check evidence/);
  writeFileSync(path, '{');
  assert.throws(() => f.run());
  writeFileSync(path, '[]');
  mkdirSync(join(f.runDirectory, 'evidence/active.lock'));
  assert.throws(() => f.run(), /EEXIST/);
  assert.throws(() => currentEvidence(f.runDirectory, f.computeCandidate(), f.repository), /still active/);
  assert.equal(f.count(), 1);
});

test('Node test summary uses the outer totals instead of Maven-looking diagnostic output', t => {
  const f = fixture(t);
  // The parent runner's internal binary reporter is not a normal CLI test run.
  const testContext = process.env.NODE_TEST_CONTEXT;
  delete process.env.NODE_TEST_CONTEXT;
  t.after(() => { if (testContext !== undefined) process.env.NODE_TEST_CONTEXT = testContext; });
  writeFileSync(join(f.repository, 'nested-output.cjs'), "console.log('Tests run: 999, Failures: 0, Errors: 0, Skipped: 123');\n");
  const result = f.run({ definition: checkDefinition('nested-node-tests', [process.execPath, '--test', 'nested-output.cjs']) });
  assert.equal(result.evidence.result, 'PASS');
  assert.equal(result.evidence.summary, '1 tests, 1 passed, 0 failed, 0 skipped', readFileSync(join(f.runDirectory, 'evidence', result.evidence.log), 'utf8'));
});

test('REVIEW and DELIVER do not write check artifacts and evidence cannot be stored in the candidate', t => {
  const f = fixture(t);
  for (const stage of ['REVIEW', 'DELIVER'] as const) assert.throws(() => f.run({ context: { ...f.context, stage } }), /cannot write/);
  assert.equal(existsSync(join(f.runDirectory, 'evidence')), false);
  assert.throws(() => f.run({ context: { ...f.context, runDirectory: f.repository } }), /outside the candidate/);
});

test('check CLI fixes reserved command definitions and supports explicit targeted checks', t => {
  const f = fixture(t);
  const path = join(f.runDirectory, 'context.json');
  writeFileSync(path, JSON.stringify(f.context));
  assert.throws(() => checkDefinition('portable-suite', ['echo', 'fake-pass']), /Reserved/);
  const portable = checkDefinition('portable-suite').command;
  assert.ok(portable.includes(process.platform === 'win32' ? 'mvnw.cmd' : './mvnw'), 'portable-suite must use the Maven Wrapper');
  assert.ok(!portable.includes('mvn') && !portable.includes('mvn.cmd'), 'portable-suite must not use a system Maven');
  assert.deepEqual(portable.slice(-3), ['--batch-mode', '--no-transfer-progress', 'test']);
  assert.throws(() => checkDefinition('../escape', ['echo']), /Invalid check/);
  assert.throws(() => checkDefinition('unknown'), /requires an executable/);
  const args = ['--context', path, '--check', 'cli-targeted', '--', process.execPath, 'check.cjs'];
  assert.equal(checkMain(args), 0);
  assert.equal(checkMain(args), 0);
  assert.equal(f.count(), 1);
  assert.throws(() => checkMain(['--context', path, '--check', 'portable-suite', '--timeout-seconds', '0']), /Timeout/);
});

test('environment records tool versions without publishing environment variable values', () => {
  const environment = checkEnvironment();
  assert.ok(environment.os);
  assert.ok(environment.arch);
  assert.match(environment.fingerprint, /^sha256:/);
  assert.ok(!JSON.stringify(environment).includes(process.env.PATH!));
});

test('real check CLI evidence passes through stage prompts and resumed VERIFY without another command execution', async t => {
  const f = fixture(t);
  const executor = join(f.root, 'executor.cjs');
  const actualCheck = fileURLToPath(new URL('./check.ts', import.meta.url));
  writeFileSync(executor, `const fs = require('node:fs');
const cp = require('node:child_process');
const input = fs.readFileSync(0, 'utf8');
const stage = input.match(/Execute only stage ([A-Z_]+)/)[1];
const args = process.argv.slice(2);
const output = args[args.indexOf('--output-last-message') + 1];
if (stage === 'IMPLEMENT' || stage === 'VERIFY') {
  const context = JSON.parse(input.match(/--context ("[^"\\n]+") --check/)[1]);
  const result = cp.spawnSync(process.execPath, [${JSON.stringify(actualCheck)}, '--context', context, '--check', 'workflow-targeted', '--', process.execPath, 'check.cjs'], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr);
  if (stage === 'VERIFY' && !result.stdout.includes('CACHED PASS')) throw new Error('Missing cache hit');
}
if (stage === 'REVIEW' || stage === 'VERIFY') {
  if (!input.includes('PASS workflow-targeted')) throw new Error('Missing shared execution evidence');
}
const fence = String.fromCharCode(96).repeat(3);
let status = { IMPLEMENT: 'READY_FOR_REVIEW', REVIEW: 'CLEAN', VERIFY: 'INCOMPLETE', DELIVER: 'DELIVERED' }[stage];
if (stage === 'VERIFY' && fs.existsSync(${JSON.stringify(join(f.root, 'resume'))})) status = 'PASS';
let report = status;
if (status === 'CLEAN' || status === 'PASS') report += '\\n' + fence + 'delivery-handoff\\n{"checks":["Synthetic automated execution evidence assessed"],"limitations":[]}\\n' + fence;
fs.writeFileSync(output, report);
`);
  const options = { task: 'Synthetic task', baseRef: 'origin/main', prBase: 'main', baseRevision: f.context.baseRevision,
    beforeDelivery: () => {}, computeCandidate: f.computeCandidate,
    runStage: createCodexRunner({ repository: f.repository, runDirectory: f.runDirectory, command: [process.execPath, executor] }) };
  const paused = await runWorkflow(options);
  assert.equal(paused.status, 'INCOMPLETE', paused.reason);
  assert.ok(paused.checkpoint);
  writeFileSync(join(f.root, 'resume'), 'resume');
  const result = await runWorkflow({ ...options, checkpoint: paused.checkpoint });
  assert.equal(result.status, 'DELIVERED', result.reason);
  assert.equal(f.count(), 1);
  assert.equal(JSON.parse(readFileSync(join(f.runDirectory, 'evidence/checks.json'), 'utf8')).length, 1);
});

test('compact evidence prompts retain role boundaries and independent acceptance requirements', () => {
  const prompt = stagePrompt({ stage: 'VERIFY', task: 'Task', baseRef: 'origin/main', prBase: 'main', baseRevision: 'base',
    verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [], evidence: [], checkContext: '/synthetic/run/stage/check-context.json' }, '/synthetic/repository');
  assert.match(prompt, /No current automated execution evidence/);
  assert.match(prompt, /Do not rerun a valid PASS/);
  assert.match(prompt, /missing manual\/platform acceptance remains INCOMPLETE/);
  assert.match(prompt, /never write evidence JSON by hand/);
  const review = stagePrompt({ stage: 'REVIEW', task: 'Task', baseRef: 'origin/main', prBase: 'main', baseRevision: 'base',
    verificationRequired: true, repairRound: 0, maxRepairRounds: 3, handoffs: [], evidence: [], checkContext: '/synthetic/run/stage/check-context.json' }, '/synthetic/repository');
  assert.match(review, /REVIEW must not write/);
  assert.ok(!review.includes('Automated validation runner:'));
  assert.match(prompt, /list required acceptance checks/);
});
