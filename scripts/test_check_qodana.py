import copy
import json
from pathlib import Path
import tempfile
import unittest
from check_qodana import reviewed_report


class QodanaReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'log').mkdir()
        (self.root / 'bootstrap.log').write_text('BUILD SUCCESS')
        (self.root / 'log/idea.log').write_text('Import completed')
        self.finding = {'ruleId': 'Example', 'message': {'text': 'Reviewed warning'},
                        'partialFingerprints': {'equalIndicator/v2': 'abcdef'},
                        'locations': [{'physicalLocation': {
                            'artifactLocation': {'uri': 'src/main/java/Example.java'},
                            'region': {'startLine': 2}}}]}
        self.data = {'runs': [{'invocations': [{'executionSuccessful': True, 'exitCode': 0}],
                               'results': [self.finding]}]}
        self.baseline = self.root / 'baseline.json'
        self.baseline.write_text(json.dumps(self.data))

    def check(self):
        (self.root / 'qodana.sarif.json').write_text(json.dumps(self.data))
        return reviewed_report(self.root, self.baseline)

    def test_export_excludes_machine_metadata(self):
        self.data['runs'][0]['properties'] = {'deviceId': 'private-device'}
        self.assertNotIn('private-device', json.dumps(self.check()))

    def test_new_finding_fails(self):
        new = copy.deepcopy(self.finding)
        new['partialFingerprints']['equalIndicator/v2'] = 'new'
        self.data['runs'][0]['results'].append(new)
        with self.assertRaisesRegex(ValueError, 'New or changed'):
            self.check()

    def test_duplicate_finding_fails(self):
        self.data['runs'][0]['results'].append(copy.deepcopy(self.finding))
        with self.assertRaisesRegex(ValueError, 'New or changed'):
            self.check()

    def test_unresolved_dependency_fails(self):
        (self.root / 'log/idea.log').write_text('Roots jar example were not resolved')
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            self.check()

    def test_sanity_failure_is_not_baselined(self):
        self.data['runs'][0]['properties'] = {'qodana.sanity.results': [self.finding]}
        with self.assertRaisesRegex(ValueError, 'sanity'):
            self.check()

    def test_failed_invocation_fails(self):
        self.data['runs'][0]['invocations'][0]['executionSuccessful'] = False
        with self.assertRaisesRegex(ValueError, 'invocation'):
            self.check()

    def test_missing_bootstrap_fails(self):
        (self.root / 'bootstrap.log').unlink()
        with self.assertRaisesRegex(ValueError, 'Maven'):
            self.check()

    def test_removed_finding_is_allowed(self):
        self.data['runs'][0]['results'] = []
        self.assertEqual([], self.check()['findings'])

    def test_absolute_path_fails(self):
        self.finding['locations'][0]['physicalLocation']['artifactLocation']['uri'] = '/data/project/Example.java'
        with self.assertRaisesRegex(ValueError, 'source-relative'):
            self.check()


if __name__ == '__main__':
    unittest.main()
