import hashlib
import json
from pathlib import Path
import shutil
import unittest
from unittest.mock import patch

import run_frame_preparation as runner
import test_analyze_frame_preparation as fixtures
from analyze_frame_preparation import summarize
from prepare_baseline_build import files_digest, sha


class PreparationRunnerTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PreparationAnalysisTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.output = self.root / "new-campaign"
        self.build = self.root / "frozen-build"
        self.build.mkdir()
        for name in ("classes/Sentinel.class", "test-classes/com/shangin/fractal/render/FreshFramePreparationExperiment.class",
                     "lib/0-dependency.jar"):
            path = self.build / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"Synthetic frozen-build fixture; never executed")
        self.java = self.root / "jdk/bin/java"
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b"Synthetic JDK executable; never executed")
        files = {name: digest for folder in ("classes", "test-classes", "lib")
                 for name, digest in ((str(Path(folder) / relative), digest)
                                      for relative, digest in files_digest(self.build / folder).items())}
        manifest = dict(version="9.1-build-v1", files=files, dependencies=["lib/0-dependency.jar"],
                        identity=hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
                        java_sha256=sha(self.java), revision="synthetic-source-revision")
        runner.write_json(self.build / "build.json", manifest)
        self.capture_patch = patch.object(runner, "capture", side_effect=self.capture)
        self.process_patch = patch.object(runner, "run_process", side_effect=self.child)
        self.platform_patch = patch.object(runner.sys, "platform", "darwin")
        self.observation_patch = patch.object(runner, "observations", return_value=dict(
            power=dict(returncode=0, stdout="Now drawing from 'AC Power'"),
            thermal=dict(returncode=0, stdout="nominal")))
        for item in (self.capture_patch, self.process_patch, self.platform_patch, self.observation_patch):
            self.addCleanup(item.stop)
        self.captures = self.capture_patch.start()
        self.processes = self.process_patch.start()
        self.platform_patch.start()
        self.conditions = self.observation_patch.start()

    def capture(self, command, env=None):
        if "-XshowSettings:properties" in command:
            return dict(returncode=0, stdout="", stderr="java.io.tmpdir = " + str(self.root) + "\n")
        output = Path(next(arg.split("=", 1)[1] for arg in command if arg.startswith("-Dbaseline.output=")))
        output.mkdir()
        shutil.copyfile(self.root / "pair-00-A/manifest.csv", output / "manifest.csv")
        return dict(returncode=0, stdout="Headless synthetic preflight", stderr="")

    def child(self, command, cwd, env, log, timeout):
        output = Path(next(arg.split("=", 1)[1] for arg in command if arg.startswith("-Dbaseline.output=")))
        shutil.copytree(self.root / output.name, output)
        log.write("Initialized prism pipeline: com.sun.prism.mtl.MTLPipeline\n")
        self.assertEqual(self.build.resolve(), cwd)
        self.assertNotIn("JAVA_TOOL_OPTIONS", env)
        return dict(returncode=0, max_rss_bytes=1000000)

    def campaign(self, **options):
        runner.run(self.root / "policy.json", self.output, self.build, self.java, "synthetic-thermal-helper", **options)

    def test_full_runner_outputs_are_consumed_by_the_real_analyzer(self):
        with patch.dict(runner.os.environ, {"JAVA_TOOL_OPTIONS": "synthetic-injected-option"}):
            self.campaign()
        self.assertEqual(12, self.processes.call_count)
        self.assertTrue((self.output / "CAMPAIGN_COMPLETE").is_file())
        self.assertEqual(json.loads((self.output / "build-before.json").read_text()),
                         json.loads((self.output / "build-after.json").read_text()))
        launches = json.loads((self.output / "launches.json").read_text())
        self.assertEqual([(row["process_pair"], row["order"], row["label"]) for row in self.fixture.launches],
                         [(row["process_pair"], row["order"], row["label"]) for row in launches])
        self.assertTrue(all(row["result"]["max_rss_bytes"] > 0 for row in launches))
        with patch("analyze_frame_preparation.paired_statistics", side_effect=fixtures.point_statistics):
            self.assertEqual("retain", summarize(self.output)["decision"])

    def test_preflight_only_never_starts_javafx_or_marks_a_campaign_complete(self):
        self.campaign(preflight_only=True)
        self.processes.assert_not_called()
        self.assertTrue((self.output / "PREFLIGHT_COMPLETE").is_file())
        self.assertFalse((self.output / "CAMPAIGN_COMPLETE").exists())
        plan = json.loads((self.output / "plan.json").read_text())
        self.assertEqual(12, len(plan))
        self.assertTrue(all("-Dbaseline.fx.jfr=false" in record["command"] for record in plan))
        self.assertTrue(all("--module-path" in record["command"] for record in plan))
        self.assertTrue(all("-Dbaseline.manifestOnly=true" in call.args[0]
                            for call in self.captures.call_args_list if "-XshowSettings:properties" not in call.args[0]))

    def test_failed_child_preserves_evidence_without_a_completion_marker(self):
        original = self.child
        self.processes.side_effect = lambda *args: original(*args) | dict(returncode=1)
        with self.assertRaisesRegex(ValueError, "Unsuccessful JVM"):
            self.campaign()
        launches = json.loads((self.output / "launches.json").read_text())
        self.assertEqual(1, len(launches))
        self.assertEqual(1, launches[0]["result"]["returncode"])
        self.assertTrue((self.output / "build-after.json").is_file())
        self.assertFalse((self.output / "CAMPAIGN_COMPLETE").exists())

    def test_invalid_thermal_state_aborts_before_starting_a_child(self):
        self.conditions.return_value = dict(power=dict(returncode=0, stdout="Now drawing from 'AC Power'"),
                                            thermal=dict(returncode=0, stdout="fair"))
        with self.assertRaisesRegex(ValueError, "thermal"):
            self.campaign()
        self.processes.assert_not_called()
        self.assertTrue((self.output / "pair-00-A-launch.json").is_file())
        self.assertFalse((self.output / "CAMPAIGN_COMPLETE").exists())

    def test_frozen_build_change_cannot_produce_a_valid_campaign(self):
        original = self.child
        def mutate(*args):
            result = original(*args)
            (self.build / "classes/Sentinel.class").write_bytes(b"changed")
            return result
        self.processes.side_effect = mutate
        with self.assertRaisesRegex(ValueError, "snapshot changed"):
            self.campaign()
        self.assertFalse((self.output / "CAMPAIGN_COMPLETE").exists())

    def test_wrong_jdk_is_rejected_before_preflight(self):
        self.java.write_bytes(b"different executable")
        with self.assertRaisesRegex(ValueError, "JDK"):
            self.campaign()
        self.captures.assert_not_called()
        self.processes.assert_not_called()

    def test_existing_output_is_not_overwritten(self):
        self.output.mkdir()
        (self.output / "owner-data").write_text("Preserve this")
        with self.assertRaises(FileExistsError):
            self.campaign()
        self.assertEqual("Preserve this", (self.output / "owner-data").read_text())
        self.processes.assert_not_called()


if __name__ == "__main__":
    unittest.main()
