#!/usr/bin/env python3
"""Run Gitleaks and narrowly classify structured benchmark file-hash findings."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from check_public_data import issues

ROOT=Path(__file__).resolve().parents[2]
DETAIL_LIMIT=10


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


def candidate_files(root):
    """Tracked files plus untracked files Git does not ignore: everything a commit can include.

    Ignored output, nested worktrees and embedded repositories are not candidates.
    Symlinks are skipped like a plain Gitleaks directory scan; the public-data guard rejects them.
    """
    output=subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=root)
    names=sorted(set(filter(None,output.decode().split('\0'))))
    return [name for name in names if (root/name).is_file() and not (root/name).is_symlink()]


def stage_candidates(root,target):
    for name in candidate_files(root):
        destination=target/name;destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(root/name,destination)


def display_path(name):
    # A path may itself carry private data; never print one the public-data guard would reject.
    return '${PRIVATE_PATH}' if issues(name,name.encode()) else name


def report_unresolved(unresolved,stream=None):
    """Print unresolved findings grouped by top-level directory and by file; matched values are never printed."""
    if not unresolved:return
    stream=stream or sys.stderr
    directories={};files={}
    for name,rule,line,commit in unresolved:
        shown=display_path(name);top=shown.split('/')[0]+('/' if '/' in shown else '')
        directories[top]=directories.get(top,0)+1
        files.setdefault((shown,rule),[]).append(f'{line}@{commit[:12]}' if commit else str(line))
    print('Unresolved findings by top-level path:',file=stream)
    for top,count in sorted(directories.items()):print(f'  {top}: {count}',file=stream)
    print('Unresolved findings by file (rule: line[@commit]):',file=stream)
    for (shown,rule),locations in sorted(files.items()):
        more=len(locations)-DETAIL_LIMIT
        suffix=f', ... {more} more' if more>0 else ''
        print(f'  {shown} [{rule}]: {", ".join(locations[:DETAIL_LIMIT])}{suffix}',file=stream)


def main(argv=None,root=ROOT):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gitleaks',default='gitleaks')
    parser.add_argument('--tree',action='store_true',help='Scan tracked and non-ignored untracked files instead of full Git history')
    args=parser.parse_args(argv)
    with tempfile.TemporaryDirectory(prefix='fractallens-secret-check-') as directory:
        temp=Path(directory);report=temp/'report.json';config=temp/'config.toml';ignore=temp/'empty.ignore'
        config.write_text('[extend]\nuseDefault = true\n');ignore.write_text('')
        target=root
        if args.tree:
            # Resolve so report paths match even when the temporary directory is behind a symlink.
            target=(temp/'tree').resolve();target.mkdir();stage_candidates(root,target)
        command=[args.gitleaks,'dir' if args.tree else 'git',str(target),'--config',str(config),'--gitleaks-ignore-path',str(ignore),'--ignore-gitleaks-allow','--redact=100','--max-archive-depth','5','--max-decode-depth','5','--max-target-megabytes','0','--report-format','json','--report-path',str(report),'--no-banner','--no-color']
        if not args.tree:command+=['--log-opts','--full-history --all --diff-merges=separate']
        result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        if result.returncode not in (0,1) or not report.exists():
            print('Secret scanner failed; publication check did not pass.',file=sys.stderr);return 2
        findings=json.loads(report.read_text());unresolved=[];classified=0
        for item in findings:
            name=item['File'];commit=item.get('Commit')
            if commit:
                data=subprocess.check_output(['git','show',commit+':'+name],cwd=root).decode(errors='replace')
            else:
                file=Path(name);file=file if file.is_absolute() else target/file
                name=file.relative_to(target).as_posix();data=file.read_text(errors='replace')
            if item['RuleID']=='generic-api-key' and file_hash_finding(data,item['StartLine'],name):classified+=1
            else:unresolved.append((name,item['RuleID'],item['StartLine'],commit))
        report_unresolved(unresolved)
        print(f'Secret check: {len(unresolved)} unresolved findings; {classified} verified file-checksum matches.')
        return 1 if unresolved else 0


if __name__=='__main__':sys.exit(main())
