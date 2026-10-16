import { readFileSync, realpathSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { computeCandidateIdentity, ROLES } from './task-orchestrator.ts';
import { checkDefinition, runCheck, type CheckContext } from './validation-evidence.ts';

export function main(args: string[]): number {
  const { values, positionals } = parseArgs({ args, allowPositionals: true, options: {
    context: { type: 'string' }, check: { type: 'string' }, fresh: { type: 'boolean' },
    'no-reuse': { type: 'boolean' }, 'timeout-seconds': { type: 'string', default: '1800' }, help: { type: 'boolean' },
  } });
  if (values.help) {
    console.log('Usage: node scripts/orchestration/check.ts --context <check-context.json> --check <id> [--fresh] [--no-reuse] [--timeout-seconds <1..86400>] [-- <executable> <arguments>]\nBuilt-ins: portable-suite, orchestrator-tests, git-diff-check. Other IDs require an explicit command.');
    return 0;
  }
  if (!values.context || !values.check) throw new Error('--context and --check are required');
  const context: CheckContext = JSON.parse(readFileSync(resolve(values.context), 'utf8'));
  if (context.version !== 1 || typeof context.repository !== 'string' || typeof context.runDirectory !== 'string'
      || typeof context.baseRevision !== 'string' || !Object.hasOwn(ROLES, context.stage)) throw new Error('Invalid check context');
  context.repository = realpathSync(context.repository);
  context.runDirectory = realpathSync(context.runDirectory);
  const timeout = Number(values['timeout-seconds']);
  if (!Number.isSafeInteger(timeout) || timeout < 1 || timeout > 86400) throw new Error('Timeout must be 1 to 86400 seconds');
  const result = runCheck({ context, definition: checkDefinition(values.check, positionals, !values['no-reuse']),
    computeCandidate: () => computeCandidateIdentity(context.repository, context.baseRevision), fresh: values.fresh, timeoutMs: timeout * 1000 });
  console.log(`${result.cached ? 'CACHED ' : ''}${result.evidence.result} ${result.evidence.checkId}\n${result.evidence.summary}`);
  return result.evidence.result === 'PASS' ? 0 : 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { process.exitCode = main(process.argv.slice(2)); }
  catch (error) { console.error(`INCOMPLETE\n${error instanceof Error ? error.message : String(error)}`); process.exitCode = 1; }
}
