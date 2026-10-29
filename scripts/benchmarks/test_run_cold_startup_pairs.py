import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import run_cold_startup_pairs as runner
from prepare_baseline_build import sha


class ColdStartupProbeIntegrityTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.probe = self.root / "probe"
        self.main_class = self.probe / runner.PROBE_CLASS
        self.main_class.parent.mkdir(parents=True)
        self.main_class.write_bytes(b"Synthetic main class; never executed")
        self.nested_class = self.main_class.with_name("ColdStartupBenchmark$StartupApplication.class")
        self.nested_class.write_bytes(b"Synthetic lifecycle class; never executed")
        self.java = self.root / "java"
        self.java.write_bytes(b"Synthetic JDK; never executed")
        for label in "AB":
            build = self.root / label
            (build / "classes").mkdir(parents=True)
            candidate = build / "classes/Sentinel.class"
            candidate.write_bytes(label.encode())
            files = {"classes/Sentinel.class": sha(candidate)}
            manifest = dict(version="9.1-build-v1", files=files, dependencies=[],
                            identity=hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
                            java_sha256=sha(self.java))
            (build / "build.json").write_text(json.dumps(manifest))
        self.output = self.root / "campaign"
        contexts = contextlib.ExitStack()
        self.addCleanup(contexts.close)
        self.processes = contexts.enter_context(patch.object(runner.subprocess, "run", side_effect=self.child))
        contexts.enter_context(patch.object(runner, "power_observation", return_value={}))
        contexts.enter_context(patch.object(runner.time, "time_ns", return_value=1_000_000_000))
        contexts.enter_context(patch.object(runner, "bound", return_value=(1.0, 1.0)))
        self.mutation = None

    def child(self, command, **options):
        if self.processes.call_count == 1 and self.mutation is not None:
            self.mutation()
        return subprocess.CompletedProcess(command, 0,
                                           "FRACTALLENS_STAGE_SHOWN_EPOCH_MS=2000\n"
                                           "FRACTALLENS_FIRST_LAYOUT_PULSE_EPOCH_MS=3000\n", "")

    def campaign(self):
        command = ["run_cold_startup_pairs.py", str(self.output),
                   "--a", str(self.root / "A"), "--b", str(self.root / "B"),
                   "--java", str(self.java), "--probe", str(self.probe)]
        with patch("sys.argv", command), contextlib.redirect_stdout(io.StringIO()):
            runner.main()

    def test_definition_records_all_probe_classes_and_their_combined_identity(self):
        anonymous = self.main_class.with_name("ColdStartupBenchmark$StartupApplication$1.class")
        anonymous.write_bytes(b"Synthetic anonymous class")
        unrelated = self.main_class.with_name("ColdStartupBenchmarkOther.class")
        unrelated.write_bytes(b"Unrelated class")
        self.campaign()
        definition = json.loads((self.output / "definition.json").read_text())
        expected = {path.relative_to(self.probe).as_posix(): sha(path)
                    for path in (self.main_class, self.nested_class, anonymous)}
        self.assertEqual("class-family-v1", definition["probe_hash_scope"])
        self.assertEqual(expected, definition["probe_files"])
        self.assertEqual(hashlib.sha256(json.dumps(expected, sort_keys=True).encode()).hexdigest(),
                         definition["probe_sha256"])
        self.assertTrue((self.output / "summary.json").is_file())
        self.assertEqual(60, self.processes.call_count)

    def test_nested_class_changes_between_campaigns_change_the_recorded_identity(self):
        self.campaign()
        before = json.loads((self.output / "definition.json").read_text())
        self.nested_class.write_bytes(b"Changed lifecycle class")
        self.output = self.root / "next-campaign"
        self.campaign()
        after = json.loads((self.output / "definition.json").read_text())
        self.assertEqual(before["probe_files"][runner.PROBE_CLASS.as_posix()],
                         after["probe_files"][runner.PROBE_CLASS.as_posix()])
        self.assertNotEqual(before["probe_sha256"], after["probe_sha256"])

    def assert_campaign_rejects_mutation(self, mutation):
        self.mutation = mutation
        with self.assertRaisesRegex(ValueError, "Probe changed during campaign"):
            self.campaign()
        self.assertTrue((self.output / "definition.json").is_file())
        self.assertFalse((self.output / "summary.json").exists())

    def test_modified_nested_class_during_campaign_is_rejected(self):
        self.assert_campaign_rejects_mutation(lambda: self.nested_class.write_bytes(b"Changed lifecycle"))

    def test_added_nested_class_during_campaign_is_rejected(self):
        added = self.main_class.with_name("ColdStartupBenchmark$Extra.class")
        self.assert_campaign_rejects_mutation(lambda: added.write_bytes(b"New nested class"))

    def test_deleted_nested_class_during_campaign_is_rejected(self):
        self.assert_campaign_rejects_mutation(self.nested_class.unlink)

    def test_deleted_main_class_during_campaign_is_rejected(self):
        self.assert_campaign_rejects_mutation(self.main_class.unlink)

    def test_missing_main_class_prevents_launch(self):
        self.main_class.unlink()
        with self.assertRaisesRegex(ValueError, "Missing shared probe class"):
            self.campaign()
        self.processes.assert_not_called()
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
