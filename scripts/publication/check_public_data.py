#!/usr/bin/env python3
"""Reject known private data and raw evidence without printing matched values."""
import argparse
import json
import ipaddress
import os
from pathlib import Path
import re
import subprocess
import sys
from privacy import EMAIL, HOME, HOST, IDENTITY, IP, KEY, MAC, PUBLIC_EMAIL, TOKEN

ROOT=Path(__file__).resolve().parents[2]
FORBIDDEN_SUFFIXES={'.log','.jfr','.hprof','.sarif','.p12','.pfx'}
# Git for Windows checks out link text with the native separator.
NATIVE_SEP=os.sep
# Claude Code finds project skills only in .claude/skills; these link to the shared Codex copies.
SKILL_LINKS={f'.claude/skills/{stage}-task' for stage in ['implement','review','verify','deliver']}
MACHINE_TEMP=re.compile(r'/(?:private/)?var/folders/[^\s"\x27]+')


def issues(path, data):
    result=[]
    # Known raster assets need separate visual/metadata review; bytes are not text.
    if data.startswith((b'\x89PNG\r\n\x1a\n',b'icns')):return result
    try:text=data.decode('utf-8')
    except UnicodeDecodeError:return ['unreviewed-binary']
    for rule,pattern in [('home-path',HOME),('machine-temp',MACHINE_TEMP),('hostname',HOST),('credential',TOKEN),('private-key',KEY),('mac-address',MAC)]:
        for m in pattern.finditer(text):
            if rule=='home-path' and m[0]=='/Users/'+'example' and path.endswith('/FractalDialogsFxTest.java'):continue
            if rule=='hostname' and m[0]=='dataSources'+'.local' and path=='.idea/.gitignore':continue
            result.append(rule);break
    for m in EMAIL.finditer(text):
        if m[0].lower() not in {PUBLIC_EMAIL,'noreply@github.com'} and not m[0].lower().endswith(('@example.com','@example.org','@example.net')):
            result.append('email');break
    ip_text=text
    # JDK 25 vendor patch versions can look like IPv4 addresses. Only the
    # declared version field in runtime provenance has this semantic exception.
    if Path(path).name in {'artifact-provenance.json','build-provenance.json'}:
        try:provenance=json.loads(text)
        except ValueError:provenance=None
        if (isinstance(provenance,dict)
                and provenance.get('schema')=='fractallens-runtime-provenance-v1'
                and isinstance(provenance.get('jdk'),str)
                and re.fullmatch(r'25\.0\.\d+\.\d+',provenance['jdk'])):
            provenance['jdk']='${JDK_VERSION}'
            ip_text=json.dumps(provenance)
    for match in IP.finditer(ip_text):
        try: ipaddress.ip_address(match[0])
        except ValueError: continue
        result.append('ip-address');break
    # Java environment and hardware dumps must use placeholders, never raw values.
    if path.startswith('benchmarks/') and path.endswith('.json'):
        for m in IDENTITY.finditer(text):
            if '${' not in m[2] and '[REDACTED]' not in m[2]:result.append('machine-identity');break
    return sorted(set(result))


def git_link_text(root, name):
    """Return a link's text as Git stores it: forward slashes, independent of the checkout platform.

    A staged link uses its index blob. An unstaged one uses the filesystem text, which Git for
    Windows writes with backslashes and converts back when the link is added.
    """
    entry=subprocess.check_output(['git','ls-files','-s','-z','--',name],cwd=root).decode().split('\0')[0]
    if entry.startswith('120000 '):
        return subprocess.check_output(['git','cat-file','blob',entry.split()[1]],cwd=root).decode()
    text=os.readlink(root/name)
    return text.replace('\\','/') if NATIVE_SEP=='\\' else text


def symlink_issues(root, name, candidates):
    """Accept only the listed Claude Code skill links, each with its exact Git link text.

    Fixed names and targets cannot carry data. The checkout must hold the same link in its native
    form, every directory on the path must be real, so a fresh clone resolves the link identically,
    and the target must hold a committed `SKILL.md`, which the content scans cover.
    """
    skill=name.removeprefix('.claude/skills/')
    expected=f'../../.agents/skills/{skill}'
    if name not in SKILL_LINKS or git_link_text(root,name)!=expected:return ['symlink']
    if os.readlink(root/name)!=expected.replace('/',NATIVE_SEP):return ['symlink']
    target=f'.agents/skills/{skill}'
    if any((root/part).is_symlink() for part in ['.claude','.claude/skills','.agents','.agents/skills',target,target+'/SKILL.md']):return ['symlink']
    if not (root/target/'SKILL.md').is_file() or target+'/SKILL.md' not in candidates:return ['symlink']
    return []


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--files',nargs='+',help='Check generated text reports before export instead of Git files')
    args=parser.parse_args()
    paths=args.files or subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=ROOT).decode().split('\0')
    allowed=set(json.loads((ROOT/'scripts/publication/public_benchmark_files.json').read_text()))
    errors=[]
    candidates=set(filter(None,paths))
    for name in sorted(candidates):
        path=ROOT/name
        if path.is_symlink():
            errors.extend((rule,name) for rule in (['symlink'] if args.files else symlink_issues(ROOT,name,candidates)))
        elif not path.is_file():
            if args.files:errors.append(('missing-report',name))
            continue
        else:
            errors.extend((rule,name) for rule in issues(name,path.read_bytes()))
        if not args.files:
            if path.suffix in FORBIDDEN_SUFFIXES or path.name.startswith('.env'):errors.append(('raw-or-secret-file',name))
            if name.startswith('benchmarks/') and name not in allowed:errors.append(('unreviewed-benchmark-file',name))
        errors.extend((rule,name) for rule in issues(name,name.encode()))
    # Paths themselves may contain secrets; emit only rule and sequential file index.
    for index,(rule,_) in enumerate(errors,1):print(f'Privacy check failed: finding {index}, rule={rule}',file=sys.stderr)
    print(f'Public-data check: {len(errors)} findings across {len(set(filter(None,paths)))} paths.')
    return bool(errors)


if __name__=='__main__':sys.exit(main())
