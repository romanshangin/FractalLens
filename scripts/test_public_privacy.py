import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

from check_public_data import issues
from check_secrets import file_hash_finding
from privacy import PUBLIC_EMAIL, redact, sanitized

ROOT=Path(__file__).resolve().parents[1]


class PrivacyTest(unittest.TestCase):
    def test_record_redaction_preserves_numbers_hashes_and_structure(self):
        home='/Users/'+'private-fixture'
        source={'command':[home+'/jdk/bin/java','/private/'+'tmp/build/classes'],
                'host':'fixture-machine'+'.local','samples':120,'ratio':.93,'valid':True,
                'sha256':'a'*64,'author':PUBLIC_EMAIL}
        result=sanitized(source)
        self.assertNotIn(home,json.dumps(result))
        self.assertNotIn(source['host'],json.dumps(result))
        self.assertEqual(source['samples'],result['samples'])
        self.assertEqual(source['ratio'],result['ratio'])
        self.assertEqual(source['valid'],result['valid'])
        self.assertEqual(source['sha256'],result['sha256'])
        self.assertEqual(PUBLIC_EMAIL,result['author'])
        self.assertEqual(list(source),list(result))

    def test_windows_and_machine_temp_paths(self):
        for value in ['C:\\Users\\'+'private-fixture\\project','/var/'+'folders/aa/private-id/T/cache']:
            with self.subTest(value=value):self.assertNotIn(value,redact(value))

    def test_identity_and_environment_secrets(self):
        token='fixture-credential-'+str(123456789)
        result=redact('password='+token+'\nHardware UUID: fixture-id\n',environment={'ACCESS_TOKEN':token})
        self.assertNotIn(token,result);self.assertNotIn('fixture-id',result)

    def test_guard_rejects_private_data_without_broad_fixture_allowance(self):
        home='/Users/'+'private-fixture'
        self.assertIn('home-path',issues('benchmarks/a.json',home.encode()))
        self.assertIn('hostname',issues('benchmarks/a.json',('private-fixture'+'.local').encode()))
        self.assertIn('email',issues('README.md',('person'+'@private-fixture.invalid').encode()))
        self.assertEqual([],issues('README.md',PUBLIC_EMAIL.encode()))
        self.assertIn('home-path',issues('README.md',('/Users/'+'example').encode()))

    def test_jdk_version_exception_is_limited_to_runtime_provenance_field(self):
        version='25.0.'+'4.1'
        record={'schema':'fractallens-runtime-provenance-v1','jdk':version}
        name='target/runtime-package/artifact-provenance.json'
        self.assertEqual([],issues(name,json.dumps(record).encode()))
        self.assertIn('ip-address',issues('README.md',json.dumps(record).encode()))
        record['host_address']=version
        self.assertIn('ip-address',issues(name,json.dumps(record).encode()))
        del record['host_address']
        record['schema']='unknown'
        self.assertIn('ip-address',issues(name,json.dumps(record).encode()))

    def test_hash_triage_does_not_allow_arbitrary_credential_fields(self):
        digest='a'*64
        valid=json.dumps({'files':{'Renderer.class':digest}},indent=2)
        self.assertTrue(file_hash_finding(valid,3,'benchmarks/final/build-A.json'))
        invalid=json.dumps({'api_key':digest},indent=2)
        self.assertFalse(file_hash_finding(invalid,2,'benchmarks/final/build-A.json'))
        self.assertFalse(file_hash_finding(valid,3,'src/credentials.json'))

    def test_bash_wrapper_redacts_both_console_and_saved_output_and_keeps_exit(self):
        if os.name=='nt':self.skipTest('Bash wrapper is covered separately in the hosted shell lane')
        home='/Users/'+'private-fixture'
        with tempfile.TemporaryDirectory() as d:
            command=['bash',str(ROOT/'scripts/run-ci-tests'),sys.executable,'-c','import sys; print(sys.argv[1]); sys.exit(37)',home]
            result=subprocess.run(command,cwd=d,text=True,capture_output=True)
            self.assertEqual(37,result.returncode)
            self.assertNotIn(home,result.stdout+result.stderr)
            self.assertNotIn(home,(Path(d)/'target/ci-test.log').read_text())
            self.assertIn('${USER_HOME}',result.stdout)

    def test_structured_machine_identifiers_are_not_published(self):
        result=sanitized({'Hardware UUID':'fixture-id','user.name':'private-fixture','samples':42})
        self.assertEqual('${PRIVATE_VALUE}',result['Hardware UUID'])
        self.assertEqual('${PRIVATE_VALUE}',result['user.name'])
        self.assertEqual(42,result['samples'])

    def test_powershell_wrapper_redacts_and_preserves_failure(self):
        executable=shutil.which('pwsh')
        if executable is None:self.skipTest('PowerShell unavailable on this platform')
        home='/Users/'+'private-fixture'
        with tempfile.TemporaryDirectory() as d:
            fixture=Path(d)/'fixture.ps1'
            fixture.write_text("[Console]::WriteLine('"+home+"'); exit 37")
            result=subprocess.run([executable,'-NoProfile','-File',str(ROOT/'scripts/run-ci-tests.ps1'),executable,'-NoProfile','-File',str(fixture)],cwd=d,text=True,capture_output=True)
            self.assertEqual(37,result.returncode)
            self.assertNotIn(home,result.stdout+result.stderr)
            self.assertNotIn(home,(Path(d)/'target/ci-test.log').read_text())

    def test_guard_cli_rejects_missing_export_report(self):
        result=subprocess.run([sys.executable,str(ROOT/'scripts/check_public_data.py'),'--files','target/missing-export-report'],capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertNotIn('target/missing-export-report',result.stderr)

    def test_metadata_serialization_is_redacted_but_execution_command_is_not(self):
        from privacy import safe_json
        command=['/Users/'+'private-fixture/java','-Xmx4g']
        record={'command':command,'wall_seconds':1.25}
        result=json.loads(safe_json(record))
        self.assertNotEqual(command[0],result['command'][0])
        self.assertEqual('/Users/'+'private-fixture/java',command[0])
        self.assertEqual(1.25,result['wall_seconds'])


if __name__=='__main__':unittest.main()
