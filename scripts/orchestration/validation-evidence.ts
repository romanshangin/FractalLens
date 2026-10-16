import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { arch, release, type } from 'node:os';
import { basename, delimiter, isAbsolute, join, relative, resolve } from 'node:path';
import type { CandidateIdentity, Stage } from './task-orchestrator.ts';

export type CheckEnvironment = { os: string; arch: string; node: string; java?: string; maven?: string; fingerprint: string };
export type CheckDefinition = { checkId: string; command: string[]; reusable: boolean };
export type CheckEvidence = {
  checkId: string;
  candidate: CandidateIdentity;
  checkDefinitionHash: string;
  command: string[];
  environment: CheckEnvironment;
  stage: Stage;
  timestamp: string;
  result: 'PASS' | 'FAIL' | 'INCOMPLETE';
  exitCode: number | null;
  reusable: boolean;
  summary: string;
  log: string;
  logHash: string;
};
export type CheckContext = { version: 1; repository: string; runDirectory: string; baseRevision: string; stage: Stage };

const digest = (value: string | Buffer): string => `sha256:${createHash('sha256').update(value).digest('hex')}`;
const sameCandidate = (a: CandidateIdentity, b: CandidateIdentity): boolean => a.baseRevision === b.baseRevision
  && a.mergeBase === b.mergeBase && a.contentId === b.contentId;

function mavenCommand(args: string[]): string[] {
  // Maven's Windows distribution is a command script. Invoke its interpreter
  // explicitly with fixed arguments; arbitrary custom commands still use argv.
  return process.platform === 'win32' ? [process.env.ComSpec ?? 'cmd.exe', '/d', '/c', 'mvn.cmd', ...args] : ['mvn', ...args];
}

// Reserved IDs have fixed definitions. Targeted checks use explicit argument arrays;
// different selectors, profiles or JVM flags therefore cannot share a PASS.
export function checkDefinition(checkId: string, command: string[] = [], reusable = true): CheckDefinition {
  if (!/^[a-z][a-z0-9-]{0,79}$/.test(checkId)) throw new Error('Invalid check ID');
  const builtins: Record<string, string[]> = {
    'portable-suite': mavenCommand(['--batch-mode', '--no-transfer-progress', 'test']),
    'orchestrator-tests': [process.execPath, '--test', 'scripts/orchestration/test_task_orchestrator.ts', 'scripts/orchestration/test_validation_evidence.ts'],
    'git-diff-check': ['git', 'diff', '--check'],
  };
  if (builtins[checkId] && command.length) throw new Error('Reserved check IDs cannot override their command');
  const argv = builtins[checkId] ?? command;
  if (!argv.length || argv.some(arg => typeof arg !== 'string' || arg.includes('\0')) || !argv[0].trim()) {
    throw new Error('A check requires an executable and argument array');
  }
  return { checkId, command: [...argv], reusable };
}

export function definitionHash(definition: CheckDefinition): string {
  return digest(JSON.stringify({ protocol: 1, checkId: definition.checkId, command: definition.command }));
}

// Conservatively bind reuse to the inherited environment and toolchain output.
// Values such as JAVA_HOME and JVM flags enter only a private digest, never prompts.
export function checkEnvironment(): CheckEnvironment {
  const probe = (executable: string, args: string[]): string => {
    const result = spawnSync(executable, args, { encoding: 'utf8', timeout: 15_000, shell: false });
    return JSON.stringify([result.status, (result.error as NodeJS.ErrnoException | undefined)?.code, result.stdout, result.stderr]);
  };
  const java = probe('java', ['-version']);
  const javaOutput = JSON.parse(java).slice(2).join('\n');
  const [mavenExecutable, ...mavenArgs] = mavenCommand(['--version']);
  const maven = probe(mavenExecutable, mavenArgs);
  return { os: `${type()} ${release()}`, arch: arch(), node: process.version,
    java: maven.match(/Java version: ([\d.]+)/)?.[1] ?? javaOutput.match(/(?:openjdk|java) version "([^"]+)"/i)?.[1],
    maven: maven.match(/Apache Maven ([\d.]+)/)?.[1],
    fingerprint: digest(JSON.stringify([process.execPath, process.version, type(), release(), arch(), java, maven,
      Object.entries(process.env).filter(([key]) => /^(PATH|PATHEXT|JAVA.*|JDK.*|MAVEN.*|MVN.*|CLASSPATH|NODE_.*|LD_.*|DYLD_.*|LANG|LC_.*|TZ|DISPLAY|WAYLAND_DISPLAY)$/.test(key))
        .sort(([a], [b]) => a.localeCompare(b))])),
  };
}

function executableHash(command: string, repository: string): string {
  const paths = isAbsolute(command) || command.includes('/') || command.includes('\\')
    ? [resolve(repository, command)] : (process.env.PATH ?? '').split(delimiter).map(path => join(path, command));
  const extensions = process.platform === 'win32' ? ['', ...(process.env.PATHEXT ?? '.EXE;.CMD;.BAT;.COM').split(';')] : [''];
  for (const path of paths.flatMap(path => extensions.map(extension => path + extension))) {
    try { return digest(readFileSync(path)); } catch { /* Try the next PATH entry. */ }
  }
  return 'unavailable';
}

function executionEnvironment(definition: CheckDefinition, repository: string): CheckEnvironment {
  const environment = checkEnvironment();
  return { ...environment, fingerprint: digest(JSON.stringify([environment.fingerprint, executableHash(definition.command[0], repository)])) };
}

function loadEvidence(runDirectory: string): CheckEvidence[] {
  const file = join(runDirectory, 'evidence', 'checks.json');
  if (!existsSync(file)) return [];
  const entries: unknown = JSON.parse(readFileSync(file, 'utf8'));
  if (!Array.isArray(entries) || entries.some(entry => !entry || typeof entry.checkId !== 'string'
      || !entry.candidate || typeof entry.candidate.baseRevision !== 'string'
      || typeof entry.candidate.mergeBase !== 'string' || typeof entry.candidate.contentId !== 'string'
      || typeof entry.checkDefinitionHash !== 'string' || !Array.isArray(entry.command)
      || !entry.command.length || entry.command.some((arg: unknown) => typeof arg !== 'string')
      || typeof entry.environment?.fingerprint !== 'string' || typeof entry.environment.os !== 'string'
      || typeof entry.environment.arch !== 'string' || typeof entry.environment.node !== 'string'
      || !['IMPLEMENT', 'IMPLEMENT_FIXES', 'REVIEW', 'VERIFY', 'DELIVER'].includes(entry.stage)
      || typeof entry.timestamp !== 'string' || typeof entry.reusable !== 'boolean'
      || !['PASS', 'FAIL', 'INCOMPLETE'].includes(entry.result)
      || !(entry.exitCode === null || Number.isInteger(entry.exitCode))
      || (entry.result === 'PASS' && entry.exitCode !== 0)
      || typeof entry.summary !== 'string' || entry.summary.length > 512
      || typeof entry.log !== 'string' || !/^check-[^/\\]+\/output\.log$/.test(entry.log)
      || typeof entry.logHash !== 'string')) throw new Error('Invalid automated check evidence registry');
  return entries as CheckEvidence[];
}

function validLog(runDirectory: string, entry: CheckEvidence): boolean {
  try { return digest(readFileSync(join(runDirectory, 'evidence', entry.log))) === entry.logHash; }
  catch { return false; }
}

// Select the latest result per exact check key, including failures. A later failure
// must never expose an older PASS as reusable evidence.
export function currentEvidence(runDirectory: string, candidate: CandidateIdentity, repository: string): CheckEvidence[] {
  if (existsSync(join(runDirectory, 'evidence', 'active.lock'))) throw new Error('Automated check execution is still active; do not advance stages');
  const latest = new Map<string, CheckEvidence>();
  for (const entry of loadEvidence(runDirectory)) {
    if (sameCandidate(entry.candidate, candidate)) latest.set(JSON.stringify([entry.checkId, entry.checkDefinitionHash, entry.environment.fingerprint]), entry);
  }
  if (!latest.size) return [];
  const environment = checkEnvironment();
  return [...latest.values()].filter(entry => {
    const definition = checkDefinition(entry.checkId, ['portable-suite', 'orchestrator-tests', 'git-diff-check'].includes(entry.checkId) ? [] : entry.command, entry.reusable);
    const expectedFingerprint = digest(JSON.stringify([environment.fingerprint, executableHash(definition.command[0], repository)]));
    return definitionHash(definition) === entry.checkDefinitionHash
      && expectedFingerprint === entry.environment.fingerprint && validLog(runDirectory, entry);
  });
}

export function evidenceSummary(entries: CheckEvidence[]): string {
  if (!entries.length) return 'No current automated execution evidence. Missing evidence is not a PASS.';
  // Bounded prompt size; the complete registry and logs remain available on demand.
  return entries.slice(-16).map(entry => `${entry.result} ${entry.checkId}${entry.reusable ? '' : ' (not reusable)'} | ${JSON.stringify(entry.command)} | ${entry.stage} | ${entry.environment.os} ${entry.environment.arch} Java ${entry.environment.java ?? 'unknown'} | ${entry.summary}`.slice(0, 1024)).join('\n');
}

export function runCheck(options: {
  context: CheckContext;
  definition: CheckDefinition;
  computeCandidate: () => CandidateIdentity;
  fresh?: boolean;
  timeoutMs?: number;
}): { evidence: CheckEvidence; cached: boolean } {
  const { context, definition, computeCandidate, fresh = false, timeoutMs = 30 * 60 * 1000 } = options;
  if (context.stage === 'REVIEW' || context.stage === 'DELIVER') throw new Error('This stage may read evidence but cannot write automated check evidence');
  const candidate = computeCandidate();
  const environment = executionEnvironment(definition, context.repository);
  const hash = definitionHash(definition);
  const directory = join(context.runDirectory, 'evidence');
  const location = relative(resolve(context.repository), resolve(context.runDirectory));
  if (!location || (!isAbsolute(location) && location !== '..' && !location.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`))) {
    throw new Error('Check evidence must be stored outside the candidate repository');
  }
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const lock = join(directory, 'active.lock');
  mkdirSync(lock, { mode: 0o700 }); // Concurrent calls fail closed rather than duplicating work.
  try {
    const entries = loadEvidence(context.runDirectory);
    const previous = entries.findLast(entry => entry.checkId === definition.checkId && sameCandidate(entry.candidate, candidate)
      && entry.checkDefinitionHash === hash && entry.environment.fingerprint === environment.fingerprint);
    if (!fresh && definition.reusable && previous?.reusable && previous.result === 'PASS' && validLog(context.runDirectory, previous)) {
      if (!sameCandidate(candidate, computeCandidate())) throw new Error('Candidate changed during evidence lookup');
      return { evidence: previous, cached: true };
    }
    const checkDirectory = mkdtempSync(join(directory, 'check-'));
    const log = join(checkDirectory, 'output.log');
    const descriptor = openSync(log, 'wx', 0o600);
    let result;
    try {
      result = spawnSync(definition.command[0], definition.command.slice(1), { cwd: context.repository,
        stdio: ['ignore', descriptor, descriptor], shell: false, timeout: timeoutMs, killSignal: 'SIGKILL' });
    } finally { closeSync(descriptor); }
    let unchanged = false;
    try { unchanged = sameCandidate(candidate, computeCandidate()); } catch { /* Identity failure is incomplete evidence. */ }
    const status = !unchanged || result.error || result.signal ? 'INCOMPLETE' : result.status === 0 ? 'PASS' : 'FAIL';
    const output = readFileSync(log);
    const text = output.toString('utf8');
    const nodeTestCommand = (definition.command[0] === process.execPath || /^(node|node\.exe)$/.test(basename(definition.command[0]))) && definition.command.includes('--test');
    const nodeCount = (name: string) => [...text.matchAll(new RegExp(`^[ℹ#] ${name} (\\d+)\\s*$`, 'gm'))].at(-1)?.[1];
    const nodeSummary = nodeTestCommand && ['tests', 'pass', 'fail', 'skipped'].every(name => nodeCount(name) !== undefined)
      ? `${nodeCount('tests')} tests, ${nodeCount('pass')} passed, ${nodeCount('fail')} failed, ${nodeCount('skipped')} skipped` : undefined;
    const testSummary = !nodeTestCommand ? [...text.matchAll(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/g)].at(-1) : undefined;
    const summary = !unchanged ? 'Candidate changed or could not be established during execution'
      : result.error ? `Execution unavailable: ${(result.error as NodeJS.ErrnoException).code ?? 'unknown error'}`
      : result.signal ? `Execution terminated: ${result.signal}`
      : nodeSummary ?? testSummary?.[0] ?? `Command exited with ${result.status}; inspect private log for coverage and skips`;
    const evidence: CheckEvidence = { checkId: definition.checkId, candidate, checkDefinitionHash: hash,
      command: definition.command, environment, stage: context.stage, timestamp: new Date().toISOString(),
      result: status, exitCode: result.status, reusable: definition.reusable, summary,
      log: `${basename(checkDirectory)}/output.log`, logHash: digest(output) };
    entries.push(evidence);
    const temporary = join(directory, 'checks.json.tmp');
    writeFileSync(temporary, JSON.stringify(entries, null, 2), { mode: 0o600 });
    renameSync(temporary, join(directory, 'checks.json'));
    return { evidence, cached: false };
  } finally { rmSync(lock, { recursive: true }); }
}
