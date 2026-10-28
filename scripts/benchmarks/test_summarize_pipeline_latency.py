import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess

from summarize_pipeline_latency import pulse_phases, summarize, validate_campaign, validate_policy

MODES = ("REFINED", "FAST", "BASE_ONLY")


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def recording(pipeline, complete_ms, screen=True, scale="2.0x2.0"):
    lines = [f"# Java=26; JavaFX=26; os=Mac OS X 26; requestedPipeline={pipeline}; screenProbe={str(screen).lower()}; time=now",
             f"# logical=1200.0x760.0; outputScale={scale}",
             "mode,gesture,phase,iteration,input,render,stage,nanos,duration_nanos"]
    for mode in MODES:
        for iteration in range(-1, 10):
            prefix = f"{mode},wheel,{'warmup' if iteration < 0 else 'sample'},{iteration},"
            complete = 1_000_000 + int(complete_ms * 1e6)
            for stage, nanos in (("input_scroll", 1_000_000), ("render_start", 2_000_000), ("complete", complete),
                                 ("complete_screen_observed", complete + 4_000_000)):
                lines.append(prefix + f"1,1,{stage},{nanos},0")
    return "\n".join(lines) + "\n"


class PipelineLatencyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.policy = dict(version="pipeline-latency-v1", process_pairs=3, samples=10, warmups=1, gestures="wheel",
                           timeout_seconds=100, jfr_diagnostic_runs=0,
                           jvm_options=dict(A=["-Dprism.order=es2"], B=["-Dprism.order=mtl"]),
                           expected_pipeline=dict(A="es2", B="mtl"),
                           metrics=[dict(metric="complete_from_last_input_ms", max_ratio=1.05),
                                    dict(metric="complete_publish_to_screen_ms", max_ratio=1.05)])
        write_json(self.root / "policy.json", self.policy)
        digest = hashlib.sha256((self.root / "policy.json").read_bytes()).hexdigest()
        self.launches = []
        for pair in range(3):
            order = "AB" if pair % 2 == 0 else "BA"
            for label in order:
                name = f"pair-{pair:02d}-{label}"
                self.launches.append(dict(process_pair=pair, order=order, label=label, directory=name, policy_sha256=digest,
                                          command=["java"] + self.policy["jvm_options"][label], result=dict(returncode=0)))
                self.write_run(name, label, complete_ms=20 if label == "A" else 18)
        write_json(self.root / "launches.json", self.launches)
        write_json(self.root / "diagnostics.json", [])
        (self.root / "CAMPAIGN_COMPLETE").write_text("complete\n")

    def write_run(self, name, label, complete_ms, pipeline=None, **options):
        pipeline = pipeline or self.policy["expected_pipeline"][label]
        prefix = "com.sun.prism." + pipeline + "." + pipeline.upper()
        (self.root / (name + ".log")).write_text("Initialized prism pipeline: " + prefix + "Pipeline\n")
        (self.root / (name + ".csv")).write_text(recording(self.policy["expected_pipeline"][label], complete_ms, **options))

    def test_faster_metal_passes_non_inferiority_without_promotion(self):
        result = summarize(self.root)
        self.assertEqual("pass", result["timing_verdict"])
        self.assertEqual("not_evaluated", result["production_promotion"])
        complete = next(m for m in result["metrics"] if m["metric"] == "complete_from_last_input_ms" and m["mode"] == "FAST")
        self.assertEqual(30, complete["pairs"])
        self.assertAlmostEqual(18 / 20, complete["median_paired_ratio"])
        with self.assertRaisesRegex(ValueError, "already exists"):
            summarize(self.root)

    def test_slower_metal_fails(self):
        for pair in range(3):
            self.write_run(f"pair-{pair:02d}-B", "B", complete_ms=30)
        self.assertEqual("fail", summarize(self.root)["timing_verdict"])

    def test_rejects_fallback_pipeline(self):
        self.write_run("pair-01-B", "B", complete_ms=18, pipeline="sw")
        with self.assertRaisesRegex(ValueError, "Actual Prism pipeline"):
            validate_campaign(self.root)

    def test_rejects_recording_without_screen_probe(self):
        self.write_run("pair-02-A", "A", complete_ms=20, screen=False)
        with self.assertRaisesRegex(ValueError, "screen probe"):
            validate_campaign(self.root)

    def test_rejects_changed_output_scale(self):
        self.write_run("pair-02-A", "A", complete_ms=20, scale="1.0x1.0")
        with self.assertRaisesRegex(ValueError, "output scale changed"):
            validate_campaign(self.root)

    def test_rejects_failed_recording(self):
        p = self.root / "pair-00-B.csv"
        p.write_text(p.read_text() + "# FAILED: java.lang.IllegalStateException\n")
        with self.assertRaisesRegex(ValueError, "Failed recording"):
            validate_campaign(self.root)

    def test_rejects_reordered_or_instrumented_launches(self):
        self.launches[0]["command"] = self.launches[0]["command"] + ["-XX:StartFlightRecording=settings=profile"]
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "Profiled"):
            validate_campaign(self.root)
        self.launches[0], self.launches[1] = self.launches[1], self.launches[0]
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "reordered"):
            validate_campaign(self.root)

    def test_absent_screen_marker_is_missing_not_zero(self):
        p = self.root / "pair-00-B.csv"
        p.write_text("\n".join(line for line in p.read_text().splitlines()
                               if not line.startswith("FAST,wheel,sample,3,")
                               or not line.endswith(("complete_screen_observed," + str(1_000_000 + 18_000_000 + 4_000_000) + ",0"))) + "\n")
        result = summarize(self.root)
        screen = next(m for m in result["metrics"] if m["metric"] == "complete_publish_to_screen_ms" and m["mode"] == "FAST")
        self.assertEqual(1, screen["missing_pairs"])
        self.assertEqual(29, screen["pairs"])
        self.assertEqual("inconclusive", screen["timing_verdict"])

    def test_metric_absent_from_every_trial_is_reported_inconclusive(self):
        for pair in range(3):
            for label in "AB":
                p = self.root / f"pair-{pair:02d}-{label}.csv"
                p.write_text("\n".join(line for line in p.read_text().splitlines() if ",complete_screen_observed," not in line) + "\n")
        result = summarize(self.root)
        self.assertEqual("inconclusive", result["timing_verdict"])
        screen = [m for m in result["metrics"] if m["metric"] == "complete_publish_to_screen_ms"]
        self.assertEqual(len(MODES), len(screen))
        for m in screen:
            self.assertEqual((0, 30, "inconclusive"), (m["pairs"], m["missing_pairs"], m["timing_verdict"]))

    def test_policy_rejects_relaxed_threshold_and_unknown_metric(self):
        self.policy["metrics"][0]["max_ratio"] = 1.2
        with self.assertRaisesRegex(ValueError, "relaxed"):
            validate_policy(self.policy)
        self.policy["metrics"][0] = dict(metric="backend_ms", max_ratio=1.05)
        with self.assertRaisesRegex(ValueError, "Unknown or duplicate metric"):
            validate_policy(self.policy)

    def test_pulse_phases_sum_render_thread_per_pulse(self):
        events = [dict(values=dict(eventThread=dict(javaName=thread), phaseName=phase, pulseId=pulse, duration=duration))
                  for thread, phase, pulse, duration in (
                      ("QuantumRenderer-0", "Painting", 1, "PT0.002S"), ("QuantumRenderer-0", "Presenting", 1, "PT0.001S"),
                      ("QuantumRenderer-0", "Painting", 2, "PT0.004S"), ("Thread-2", "CSS Pass", 1, "PT0.0005S"))]
        printed = subprocess.CompletedProcess([], 0, stdout=json.dumps(dict(recording=dict(events=events))))
        with patch("summarize_pipeline_latency.subprocess.run", return_value=printed):
            rows = pulse_phases(self.root, [dict(label="B", directory="diagnostic-00-B")], Path("jfr"))
        by_phase = {(r["thread"], r["phase"]): r for r in rows}
        self.assertEqual(2, by_phase["render", "total per pulse"]["count"])
        self.assertEqual("7.0", by_phase["render", "total per pulse"]["total_ms"])
        self.assertEqual("0.5000", by_phase["fx", "CSS Pass"]["median_ms"])
        empty = subprocess.CompletedProcess([], 0, stdout=json.dumps(dict(recording=dict(events=[]))))
        with patch("summarize_pipeline_latency.subprocess.run", return_value=empty):
            with self.assertRaisesRegex(ValueError, "No javafx.PulsePhase events"):
                pulse_phases(self.root, [dict(label="B", directory="diagnostic-00-B")], Path("jfr"))


if __name__ == "__main__":
    unittest.main()
