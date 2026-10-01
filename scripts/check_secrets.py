#!/usr/bin/env python3
"""Run Gitleaks and narrowly classify structured benchmark file-hash findings."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]


def file_hash_finding(text,line_number,path):
    if not path.startswith('benchmarks/') or not path.endswith('.json'):return False
    lines=text.splitlines()
    if line_number<1 or line_number>len(lines):return False
    match=re.fullmatch(r'\s*"([^"\n]+)":\s*"([a-f0-9]{64})",?\s*',lines[line_number-1])
    if not match or not match[1].endswith(('.class','.java','.jar','.comp','.xml','.m','.c')):return False
    try:data=json.loads(text)
    except ValueError:return False
    def walk(value,trail=()):
        if isinstance(value,dict):
            if value.get(match[1])==match[2]:
                if trail and trail[-1] in ('files','source_sha256','sha256'):return True
                if not trail and all(isinstance(v,str) and re.fullmatch('[a-f0-9]{64}',v) for v in value.values()):return True
            return any(walk(v,trail+(k,)) for k,v in value.items())
        if isinstance(value,list):return any(walk(v,trail) for v in value)
        return False
    return walk(data)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gitleaks',default='gitleaks')
    parser.add_argument('--tree',action='store_true',help='Scan the working directory instead of full Git history')
    args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='fractallens-secret-check-') as directory:
        temp=Path(directory);report=temp/'report.json';config=temp/'config.toml';ignore=temp/'empty.ignore'
        config.write_text('[extend]\nuseDefault = true\n');ignore.write_text('')
        command=[args.gitleaks,'dir' if args.tree else 'git',str(ROOT),'--config',str(config),'--gitleaks-ignore-path',str(ignore),'--ignore-gitleaks-allow','--redact=100','--max-archive-depth','5','--max-decode-depth','5','--max-target-megabytes','0','--report-format','json','--report-path',str(report),'--no-banner','--no-color']
        if not args.tree:command+=['--log-opts','--full-history --all --diff-merges=separate']
        result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if result.returncode not in (0,1) or not report.exists():
            print('Secret scanner failed; publication check did not pass.',file=sys.stderr);return 2
        findings=json.loads(report.read_text());unresolved=0;classified=0
        for item in findings:
            name=item['File'];commit=item.get('Commit')
            if commit:
                data=subprocess.check_output(['git','show',commit+':'+name],cwd=ROOT).decode(errors='replace')
            else:
                file=Path(name);file=file if file.is_absolute() else ROOT/file
                name=str(file.relative_to(ROOT));data=file.read_text(errors='replace')
            if item['RuleID']=='generic-api-key' and file_hash_finding(data,item['StartLine'],name):classified+=1
            else:unresolved+=1
        print(f'Secret check: {unresolved} unresolved findings; {classified} verified file-checksum matches.')
        return 1 if unresolved else 0


if __name__=='__main__':sys.exit(main())
