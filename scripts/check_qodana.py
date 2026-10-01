#!/usr/bin/env python3
"""Reject incomplete analysis and export findings without diagnostic metadata."""
import argparse
from collections import Counter
import json
from pathlib import Path, PurePosixPath
import re

BASELINE = Path(__file__).resolve().parents[1] / '.qodana/baseline.sarif.json'


def fingerprint(result):
    values = result.get('partialFingerprints', {})
    value = values.get('equalIndicator/v2') or values.get('equalIndicator/v1')
    if not value:
        raise ValueError('Finding fingerprint is missing.')
    return result['ruleId'], value


def check_sanity(value):
    if isinstance(value, dict):
        if value.get('qodana.sanity.results'):
            raise ValueError('Qodana sanity findings require review.')
        for child in value.values():
            check_sanity(child)
    elif isinstance(value, list):
        for child in value:
            check_sanity(child)


def reviewed_report(directory, baseline=BASELINE):
    bootstrap = directory / 'bootstrap.log'
    if not bootstrap.is_file() or 'BUILD SUCCESS' not in bootstrap.read_text():
        raise ValueError('Successful Maven preparation is not evidenced.')
    for log in [directory / 'log/idea.log']:
        if not log.is_file():
            raise ValueError('Qodana import diagnostics are missing.')
        if re.search(r'Roots jar[^\n]*were not resolved', log.read_text()):
            raise ValueError('Qodana dependency roots are unresolved.')
    data = json.loads((directory / 'qodana.sarif.json').read_text())
    check_sanity(data)
    runs = data.get('runs', [])
    if len(runs) != 1:
        raise ValueError('Expected one complete Qodana analysis.')
    run = runs[0]
    invocations = run.get('invocations', [])
    if not invocations or any(i.get('executionSuccessful') is not True
                              or i.get('exitCode') != 0 for i in invocations):
        raise ValueError('Qodana invocation did not complete successfully.')
    accepted = Counter(fingerprint(r) for r in
                       json.loads(baseline.read_text())['runs'][0]['results'])
    results = [r for r in run.get('results', []) if r.get('baselineState') != 'absent']
    if Counter(fingerprint(r) for r in results) - accepted:
        raise ValueError('New or changed Qodana findings require review.')
    findings = []
    for result in results:
        location = result['locations'][0]['physicalLocation']
        uri = location['artifactLocation']['uri']
        path = PurePosixPath(uri)
        if path.is_absolute() or '..' in path.parts or not uri.startswith('src/'):
            raise ValueError('Finding location is not a source-relative path.')
        findings.append({'rule': result['ruleId'], 'path': uri,
                         'line': location['region']['startLine'],
                         'message': result['message']['text']})
    return {'schema': 'fractallens-qodana-reviewed-v1', 'findings': findings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        report = reviewed_report(args.results)
    except (ValueError, KeyError, IndexError, TypeError, OSError):
        # Never print raw parser/IO messages: they may include diagnostic data.
        print('Qodana report validation failed; inspect private diagnostics.')
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(f"Qodana analysis validated: {len(report['findings'])} reviewed findings.")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
