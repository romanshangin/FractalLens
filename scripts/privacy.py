#!/usr/bin/env python3
"""Redact publication-sensitive strings without changing numerical observations."""
import argparse
import getpass
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import tempfile

PRIVATE_FIELDS = {'username', 'userhome', 'hostname', 'host', 'computername',
                  'serialnumber', 'hardwareserialnumber', 'hardwareuuid',
                  'ioplatformuuid', 'ioplatformserialnumber'}
PUBLIC_EMAIL = 'shangin.ro@gmail.com'
EMAIL = re.compile(r'(?i)(?<![\w.+-])[\w.+%-]+@[\w.-]+\.[a-z]{2,}\b')
HOME = re.compile(r'(?:/(?:Users|home)/[^/\s"\x27:;,]+|[A-Za-z]:[\\/]+Users[\\/]+[^\\/\s"\x27:;,]+)')
TEMP = re.compile(r'/(?:private/)?var/folders/[^/\s]+/[^/\s]+/T|/(?:private/)?tmp(?=/)|[A-Za-z]:[\\/]+(?:Windows[\\/]+Temp)(?=[\\/])')
HOST = re.compile(r'(?i)\b[a-z0-9][a-z0-9_.-]*\.local\b')
IDENTITY = re.compile(r'(?im)((?:user[.]name|user[.]home|host(?:name)?|computer[ _-]*name|serial[ _-]*(?:number|no)|hardware[ _-]*uuid|IOPlatformUUID|IOPlatformSerialNumber)\s*[:=]\s*)([^\r\n]+)')
TOKEN = re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b')
KEY = re.compile(r'-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----.*?(?:-----END (?:[A-Z]+ )?PRIVATE KEY-----|\Z)',re.S)
ASSIGNMENT = re.compile(r'(?im)(\b(?:password|passwd|api[_-]?key|access[_-]?token|secret)\s*[:=]\s*)([^\s,;]+)')
IP = re.compile(r'(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])|(?i:(?<![\w:])(?:[a-f0-9]{0,4}:){2,7}[a-f0-9]{0,4}(?![\w:]))')
MAC = re.compile(r'(?i)(?<![a-f0-9])(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2}(?![a-f0-9])')


def redact(text, *, project=None, environment=None):
    """Best-effort redaction for text output; never a complete secret scanner."""
    environment = os.environ if environment is None else environment
    text = KEY.sub('${PRIVATE_KEY}', text)
    text = TOKEN.sub('${SECRET}', text)
    for key,value in environment.items():
        if len(value) >= 8 and re.search(r'(?:TOKEN|PASSWORD|SECRET|API_KEY|PRIVATE_KEY)',key,re.I):
            text = text.replace(value,'${SECRET}')
    replacements = [(str(project or Path(__file__).resolve().parents[1]),'${PROJECT_DIR}'),
                    (str(Path.home()),'${USER_HOME}'),(tempfile.gettempdir(),'${TEMP_DIR}')]
    for old,new in sorted(replacements,key=lambda x:-len(x[0])):
        if len(old)>3:
            text=text.replace(old,new).replace(old.replace('\\','/'),new)
    text=HOME.sub('${USER_HOME}',text)
    text=TEMP.sub('${TEMP_DIR}',text)
    text=HOST.sub('${HOST_NAME}',text)
    for value,placeholder in [(socket.gethostname(),'${HOST_NAME}'),(getpass.getuser(),'${USER_NAME}')]:
        if value:
            text=re.sub(r'(?<![\w])'+re.escape(value)+r'(?![\w])',lambda _:placeholder,text)
    text=IDENTITY.sub(lambda m:m[1]+'${PRIVATE_VALUE}',text)
    text=EMAIL.sub(lambda m:m[0] if m[0].lower()==PUBLIC_EMAIL else '${EMAIL}',text)
    text=ASSIGNMENT.sub(lambda m:m[1]+'${SECRET}',text)
    text=MAC.sub('${MAC_ADDRESS}',text)
    def ip(m):
        try:ipaddress.ip_address(m[0]);return '${IP_ADDRESS}'
        except ValueError:return m[0]
    return IP.sub(ip,text)


def sanitized(value):
    """Copy a JSON-compatible record, preserving keys, numbers and booleans."""
    if isinstance(value,str):return redact(value)
    if isinstance(value,list):return [sanitized(v) for v in value]
    if isinstance(value,dict):
        return {k:'${PRIVATE_VALUE}' if re.sub(r'[^a-z]', '', k.lower()) in PRIVATE_FIELDS
                else sanitized(v) for k,v in value.items()}
    return value


def safe_json(value, **kwargs):
    return json.dumps(sanitized(value),**kwargs)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    parser.add_argument('destination',type=Path)
    args=parser.parse_args()
    try:
        value=redact(args.source.read_text(encoding='utf-8',errors='replace'))
        args.destination.write_text(value,encoding='utf-8')
    except (OSError,ValueError):
        # Never expose the failing path or fall back to printing unsanitized data.
        parser.exit(2,'Privacy filtering failed; output withheld.\n')
