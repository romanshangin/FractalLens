#!/usr/bin/env python3
"""Reject known private data and raw evidence without printing matched values."""
import argparse
import json
import ipaddress
from pathlib import Path
import re
import subprocess
import sys
from privacy import EMAIL, HOME, HOST, IDENTITY, IP, KEY, MAC, PUBLIC_EMAIL, TOKEN

ROOT=Path(__file__).resolve().parents[1]
FORBIDDEN_SUFFIXES={'.log','.jfr','.hprof','.sarif','.p12','.pfx'}
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


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--files',nargs='+',help='Check generated text reports before export instead of Git files')
    args=parser.parse_args()
    paths=args.files or subprocess.check_output(['git','ls-files','-z','--cached','--others','--exclude-standard'],cwd=ROOT).decode().split('\0')
    allowed=set(json.loads((ROOT/'scripts/public_benchmark_files.json').read_text()))
    errors=[]
    for name in sorted(set(filter(None,paths))):
        path=ROOT/name
        if path.is_symlink():errors.append(('symlink',name));continue
        if not path.is_file():
            if args.files:errors.append(('missing-report',name))
            continue
        if not args.files:
            if path.suffix in FORBIDDEN_SUFFIXES or path.name.startswith('.env'):errors.append(('raw-or-secret-file',name))
            if name.startswith('benchmarks/') and name not in allowed:errors.append(('unreviewed-benchmark-file',name))
        errors.extend((rule,name) for rule in issues(name,path.read_bytes()))
        errors.extend((rule,name) for rule in issues(name,name.encode()))
    # Paths themselves may contain secrets; emit only rule and sequential file index.
    for index,(rule,_) in enumerate(errors,1):print(f'Privacy check failed: finding {index}, rule={rule}',file=sys.stderr)
    print(f'Public-data check: {len(errors)} findings across {len(set(filter(None,paths)))} paths.')
    return bool(errors)


if __name__=='__main__':sys.exit(main())
