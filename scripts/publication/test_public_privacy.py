import io
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

import check_public_data
from check_public_data import issues
import check_secrets
from check_secrets import candidate_files, file_hash_finding, report_unresolved
from privacy import PUBLIC_EMAIL, redact, sanitized

ROOT=Path(__file__).resolve().parents[2]
SECRET_MARKER='FIXTURE-'+'SCANNER-MATCH'
# Reports every file under the scanned directory that contains the marker, like `gitleaks dir`.
FAKE_GITLEAKS='''import json,os,sys
args=sys.argv[1:];target=args[1];report=args[args.index('--report-path')+1];findings=[]
for base,_,names in os.walk(target):
    for name in names:
        path=os.path.join(base,name);text=open(path,errors='replace').read()
        if %r in text:findings.append({'File':path,'RuleID':'generic-api-key','StartLine':text[:text.index(%r)].count('\\n')+1})
json.dump(findings,open(report,'w'));sys.exit(1 if findings else 0)
'''%(SECRET_MARKER,SECRET_MARKER)


def git(cwd,*args):subprocess.run(['git',*args],cwd=cwd,check=True,capture_output=True)


def make_symlink(target,link,directory=False):
    """Create a symlink, skipping where the platform or account cannot (Windows without the privilege)."""
    try:os.symlink(target,link,target_is_directory=directory)
    except (OSError,NotImplementedError):raise unittest.SkipTest('symlink creation is unavailable')


def secret_fixture_repository(root):
    """Repository with the marker in tracked, untracked, ignored, excluded and nested-repository files."""
    git(root,'init','-q')
    for name in ['tracked.txt','new/untracked.txt','target/ignored.txt','.claude/worktrees/copy/excluded.txt','nested/inner.txt']:
        (root/name).parent.mkdir(parents=True,exist_ok=True);(root/name).write_text('line\n'+SECRET_MARKER+'\n')
    (root/'.gitignore').write_text('target/\n')
    (root/'.git/info/exclude').write_text('.claude/worktrees/\n')
    git(root/'nested','init','-q')
    git(root,'add','tracked.txt','.gitignore')


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

    def test_tree_candidates_exclude_ignored_files_and_nested_repositories(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);secret_fixture_repository(root)
            self.assertEqual(['.gitignore','new/untracked.txt','tracked.txt'],candidate_files(root))

    def test_tree_scan_reports_only_commit_candidates_grouped_by_file(self):
        if os.name=='nt':self.skipTest('Fake scanner is a POSIX executable script')
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'repo';root.mkdir();secret_fixture_repository(root)
            scanner=Path(d)/'fake-gitleaks';scanner.write_text('#!'+sys.executable+'\n'+FAKE_GITLEAKS);scanner.chmod(0o755)
            stdout,stderr=io.StringIO(),io.StringIO()
            with patch('sys.stdout',stdout),patch('sys.stderr',stderr):
                status=check_secrets.main(['--tree','--gitleaks',str(scanner)],root=root)
            self.assertEqual(1,status)
            self.assertIn('Secret check: 2 unresolved findings; 0 verified file-checksum matches.',stdout.getvalue())
            self.assertIn('  new/untracked.txt [generic-api-key]: 2',stderr.getvalue())
            self.assertIn('  tracked.txt [generic-api-key]: 2',stderr.getvalue())
            for hidden in ['ignored.txt','excluded.txt','inner.txt',SECRET_MARKER]:self.assertNotIn(hidden,stdout.getvalue()+stderr.getvalue())

    def test_unresolved_report_groups_findings_and_withholds_private_paths(self):
        private='benchmarks/'+'private-fixture'+'.local/a.json'
        findings=[('benchmarks/run/a.json','generic-api-key',line,None) for line in range(1,13)]
        findings+=[('src/Main.java','aws-access-token',4,'0123456789abcdef'),(private,'generic-api-key',2,None)]
        stream=io.StringIO();report_unresolved(findings,stream)
        output=stream.getvalue()
        self.assertIn('  benchmarks/: 12',output)
        self.assertIn('  src/: 1',output)
        self.assertIn('  benchmarks/run/a.json [generic-api-key]: 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, ... 2 more',output)
        self.assertIn('  src/Main.java [aws-access-token]: 4@0123456789ab',output)
        self.assertIn('  ${PRIVATE_PATH} [generic-api-key]: 2',output)
        self.assertNotIn('private-fixture',output)

    def test_bash_wrapper_redacts_both_console_and_saved_output_and_keeps_exit(self):
        if os.name=='nt':self.skipTest('Bash wrapper is covered separately in the hosted shell lane')
        home='/Users/'+'private-fixture'
        with tempfile.TemporaryDirectory() as d:
            command=['bash',str(ROOT/'scripts/ci/run-ci-tests'),sys.executable,'-c','import sys; print(sys.argv[1]); sys.exit(37)',home]
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
            result=subprocess.run([executable,'-NoProfile','-File',str(ROOT/'scripts/ci/run-ci-tests.ps1'),executable,'-NoProfile','-File',str(fixture)],cwd=d,text=True,capture_output=True)
            self.assertEqual(37,result.returncode)
            self.assertNotIn(home,result.stdout+result.stderr)
            self.assertNotIn(home,(Path(d)/'target/ci-test.log').read_text())

    def test_only_listed_skill_links_with_exact_targets_are_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);skills=root/'.agents/skills';git(root,'init','-q')
            for skill in ['implement-task','verify-task','deliver-task',SECRET_MARKER.lower()]:(skills/skill).mkdir(parents=True)
            for skill in ['implement-task','deliver-task',SECRET_MARKER.lower()]:(skills/skill/'SKILL.md').write_text('body\n')
            (skills/'verify-task/README.md').write_text('no entry point\n')
            (root/'.claude/skills').mkdir(parents=True)
            # A local alias inside the target tree would resolve here but not in a fresh clone.
            make_symlink('implement-task',skills/'review-task',directory=True)
            links={
                '.claude/skills/implement-task':'../../.agents/skills/implement-task',
                '.claude/skills/review-task':'../../.agents/skills/review-task',
                '.claude/skills/verify-task':'../../.agents/skills/verify-task',
                '.claude/skills/deliver-task':'..\\..\\.agents\\skills\\deliver-task',
                '.claude/skills/'+SECRET_MARKER.lower():'../../.agents/skills/'+SECRET_MARKER.lower(),
            }
            for name,target in links.items():make_symlink(target,root/name,directory=True)
            candidates={*links,'.agents/skills/review-task','.agents/skills/verify-task/README.md',
                        *(f'.agents/skills/{skill}/SKILL.md' for skill in ['implement-task','deliver-task',SECRET_MARKER.lower()])}
            # Links stay unstaged; on POSIX their filesystem text is the text Git would store.
            with patch.object(check_public_data,'NATIVE_SEP','/'):
                self.assertEqual([],check_public_data.symlink_issues(root,'.claude/skills/implement-task',candidates))
                for name in links.keys()-{'.claude/skills/implement-task'}:
                    self.assertEqual(['symlink'],check_public_data.symlink_issues(root,name,candidates),name)

    def test_skill_link_uses_git_text_and_accepts_the_native_checkout_form(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);git(root,'init','-q')
            (root/'.agents/skills/review-task').mkdir(parents=True);(root/'.claude/skills').mkdir(parents=True)
            (root/'.agents/skills/review-task/SKILL.md').write_text('body\n')
            name='.claude/skills/review-task';target='../../.agents/skills/review-task'
            make_symlink(target,root/name,directory=True)
            git(root,'add','.')
            candidates={name,'.agents/skills/review-task/SKILL.md'}
            posix=lambda path:target
            windows=lambda path:target.replace('/','\\')
            def check(separator,readlink):
                with patch.object(check_public_data,'NATIVE_SEP',separator),patch.object(check_public_data.os,'readlink',readlink):
                    return check_public_data.symlink_issues(root,name,candidates)
            self.assertEqual([],check('/',posix))
            # Git for Windows writes the staged '/' text with backslashes into the checkout.
            self.assertEqual([],check('\\',windows))
            self.assertEqual(['symlink'],check('/',windows))
            self.assertEqual(['symlink'],check('\\',posix))
            # A staged blob with backslashes is not the canonical text, whatever the checkout shows.
            blob=subprocess.run(['git','hash-object','-w','--stdin'],cwd=root,input=target.replace('/','\\'),
                                capture_output=True,text=True,check=True).stdout.strip()
            git(root,'update-index','--cacheinfo',f'120000,{blob},{name}')
            self.assertEqual(['symlink'],check('\\',windows))
            self.assertEqual(['symlink'],check('/',posix))

    def test_symlink_paths_get_name_and_benchmark_rules(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);git(root,'init','-q')
            (root/'scripts/publication').mkdir(parents=True)
            (root/'scripts/publication/public_benchmark_files.json').write_text('[]')
            (root/'benchmarks').mkdir();(root/'notes.txt').write_text('text\n')
            make_symlink('../notes.txt',root/'benchmarks/new.log')
            stdout,stderr=io.StringIO(),io.StringIO()
            with patch.object(check_public_data,'ROOT',root),patch.object(sys,'argv',['check_public_data.py']),\
                    patch('sys.stdout',stdout),patch('sys.stderr',stderr):
                self.assertTrue(check_public_data.main())
            for rule in ['symlink','raw-or-secret-file','unreviewed-benchmark-file']:self.assertIn('rule='+rule,stderr.getvalue())

    def test_guard_cli_rejects_missing_export_report(self):
        result=subprocess.run([sys.executable,str(ROOT/'scripts/publication/check_public_data.py'),'--files','target/missing-export-report'],capture_output=True,text=True)
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
