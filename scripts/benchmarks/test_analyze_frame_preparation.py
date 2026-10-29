import csv
import hashlib
import json
from pathlib import Path
import statistics
import tempfile
import unittest
from unittest.mock import patch

from analyze_frame_preparation import summarize, validate_campaign, validate_policy, validate_manifest
from summarize_baseline_fx import SCOPES


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def write_rows(path, rows):
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0], lineterminator="\n")
        writer.writeheader(); writer.writerows(rows)


def point_statistics(rows):
    ratio = statistics.median(row["ratio"] for row in rows)
    return dict(pairs=len(rows), process_pairs=len({row["process_pair"] for row in rows}),
                median_paired_ratio=ratio, ratio_ci95_low=ratio, ratio_ci95_high=ratio)


class PreparationAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.policy = dict(version="p17-fresh-frame-preparation-v1", purpose="benchmark_experiment", pipeline="mtl",
                           variants={"A": "fx", "B": "worker"}, sizes="96x66", fixtures="mandelbrot-overview", modes="FAST",
                           process_pairs=6, samples=5, warmups=3, timeout_seconds=100, rss_max_pair_ratio=1.05,
                           metrics=[dict(metric="fx_setup_ms", action="FRESH", role="target", max_ratio=.9),
                                    dict(metric="first_visible_publish_ms", role="control", max_ratio=1.05)])
        write_json(self.root / "policy.json", self.policy)
        policy_hash = hashlib.sha256((self.root / "policy.json").read_bytes()).hexdigest()
        write_json(self.root / "build-before.json", {"compiled": "synthetic-test-checksum"})
        write_json(self.root / "build-after.json", {"compiled": "synthetic-test-checksum"})
        self.launches = []
        for pair in range(6):
            order = "AB" if pair % 2 == 0 else "BA"
            for label in order:
                name = f"pair-{pair:02d}-{label}"
                variant = self.policy["variants"][label]
                run = self.root / name; run.mkdir()
                observation = dict(power=dict(returncode=0, stdout="Now drawing from 'AC Power'"),
                                   thermal=dict(returncode=0, stdout="nominal"))
                self.launches.append(dict(process_pair=pair, order=order, label=label, directory=name,
                                          command=["java", "-Xmx4g", "-Dprism.order=mtl", "-Dbaseline.fx.preparation=" + variant],
                                          policy_sha256=policy_hash, before=observation, after=observation,
                                          result=dict(returncode=0, max_rss_bytes=1000000)))
                (self.root / (name + ".log")).write_text("Initialized prism pipeline: com.sun.prism.mtl.MTLPipeline\n")
                (run / "SUCCESS").write_text("Synthetic validator fixture, not real render evidence.\n")
                env = dict(matrix="9.1-v2", java="26", javafx="26", os="macOS", arch="aarch64", processors="12",
                           max_heap_bytes=str(4 * 1024**3), output_scale="2x2", modes="FAST", samples="5", warmups="3",
                           frame_preparation=variant, aa_profile="false", interval_jfr="false", vm_arguments="[-Xmx4g]",
                           gpu_calculation="false", requested_pipeline="mtl")
                (run / "environment.txt").write_text("".join(k + "=" + v + "\n" for k, v in env.items()))
                write_rows(run / "manifest.csv", [dict(fixture="mandelbrot-overview", step="render", width="96", height="66",
                                                       action="FRESH", actual_cap="300", cancel_trigger="none")])
                rows = []
                for request, (phase, sample) in enumerate([("cold_fixture", -4)] + [("warmup", r) for r in (-3, -2, -1)]
                                                         + [("sample", r) for r in range(5)]):
                    row = dict(fixture="mandelbrot-overview", step="render", width="96", height="66", requested_mode="FAST",
                               effective_mode="FAST", phase=phase, run=str(sample), request=str(request), backend="CPU", cap="300",
                               complete="true", sample_hash="42", argb_hash="43", **{scope: "-1" for scope in SCOPES})
                    row.update(first_visible_publish_ms="2", full_publish_ms="4", first_post_layout_ms="2.5", full_post_layout_ms="4.5",
                               fx_setup_ms="1" if label == "A" else "0.5", preparation_ms="-1" if label == "A" else "0.25")
                    rows.append(row)
                write_rows(run / "samples.csv", rows)
        write_json(self.root / "launches.json", self.launches)
        (self.root / "CAMPAIGN_COMPLETE").write_text("Synthetic complete fixture.\n")

    def change_samples(self, name, change):
        path = self.root / name / "samples.csv"
        with path.open(newline="") as stream:
            rows = list(csv.DictReader(stream))
        change(rows)
        write_rows(path, rows)

    @patch("analyze_frame_preparation.paired_statistics", side_effect=point_statistics)
    def test_retain_means_experiment_only_and_requires_full_paired_budget(self, stats):
        result = summarize(self.root)
        self.assertEqual("retain", result["decision"])
        self.assertEqual("not_evaluated", result["production_promotion"])
        self.assertEqual(30, result["metrics"][0]["pairs"])
        self.assertEqual(6, len(result["memory"]))
        with self.assertRaisesRegex(ValueError, "already exists"):
            summarize(self.root)

    @patch("analyze_frame_preparation.paired_statistics", side_effect=point_statistics)
    def test_latency_regression_rejects_an_otherwise_faster_fx_path(self, stats):
        for launch in self.launches:
            if launch["label"] == "B":
                self.change_samples(launch["directory"], lambda rows: [row.update(first_visible_publish_ms="2.4") for row in rows])
        self.assertEqual("reject", summarize(self.root)["decision"])

    @patch("analyze_frame_preparation.paired_statistics", side_effect=point_statistics)
    def test_any_observed_rss_budget_failure_rejects(self, stats):
        next(row for row in self.launches if row["label"] == "B")["result"]["max_rss_bytes"] = 1050001
        write_json(self.root / "launches.json", self.launches)
        result = summarize(self.root)
        self.assertEqual("pass", result["timing_verdict"])
        self.assertEqual("fail", result["memory_verdict"])
        self.assertEqual("reject", result["decision"])

    def test_uncertain_target_is_not_promoted(self):
        def uncertain(rows):
            result = point_statistics(rows)
            if rows[0]["role"] == "target":
                result.update(ratio_ci95_low=.89, ratio_ci95_high=.91)
            return result
        with patch("analyze_frame_preparation.paired_statistics", side_effect=uncertain):
            self.assertEqual("inconclusive", summarize(self.root)["decision"])

    def test_missing_samples_or_changed_fingerprints_are_rejected(self):
        self.change_samples("pair-00-B", lambda rows: rows[4].update(argb_hash="different"))
        with self.assertRaises(ValueError):
            validate_campaign(self.root)

    def test_profiled_or_wrong_variant_launch_is_rejected(self):
        self.launches[0]["command"].append("-XX:StartFlightRecording=settings=profile")
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "Profiled"):
            validate_campaign(self.root)
        self.launches[0]["command"].pop()
        self.launches[0]["command"][-1] = "-Dbaseline.fx.preparation=worker"
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "variant"):
            validate_campaign(self.root)

    def test_incomplete_or_reordered_jvms_are_rejected(self):
        write_json(self.root / "launches.json", self.launches[:-1])
        with self.assertRaisesRegex(ValueError, "JVMs"):
            validate_campaign(self.root)

    def test_changed_build_or_policy_identity_is_rejected(self):
        write_json(self.root / "build-after.json", {"compiled": "changed"})
        with self.assertRaisesRegex(ValueError, "build changed"):
            validate_campaign(self.root)
        write_json(self.root / "build-after.json", {"compiled": "synthetic-test-checksum"})
        self.launches[0]["policy_sha256"] = "changed"
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "Policy changed"):
            validate_campaign(self.root)

    def test_preparation_scope_and_power_conditions_are_required(self):
        self.change_samples("pair-00-B", lambda rows: [row.update(preparation_ms="-1") for row in rows])
        with self.assertRaisesRegex(ValueError, "preparation timing"):
            validate_campaign(self.root)
        self.change_samples("pair-00-B", lambda rows: [row.update(preparation_ms="0.25") for row in rows])
        self.launches[0]["before"]["thermal"]["stdout"] = "fair"
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "thermal"):
            validate_campaign(self.root)

    def test_actual_pipeline_and_manifest_selection_are_required(self):
        (self.root / "pair-00-A.log").write_text("Initialized prism pipeline: com.sun.prism.es2.ES2Pipeline\n")
        with self.assertRaisesRegex(ValueError, "actual pipeline"):
            validate_campaign(self.root)

    def test_removing_one_fixture_size_from_all_jvms_cannot_shrink_the_matrix(self):
        self.policy.update(fixtures="mandelbrot-overview,mandelbrot-aa-regular", sizes="96x66,128x96")
        write_json(self.root / "policy.json", self.policy)
        digest = hashlib.sha256((self.root / "policy.json").read_bytes()).hexdigest()
        for launch in self.launches:
            launch["policy_sha256"] = digest
            run = self.root / launch["directory"]
            with (run / "manifest.csv").open(newline="") as stream:
                original_manifest = list(csv.DictReader(stream))[0]
            with (run / "samples.csv").open(newline="") as stream:
                original_samples = list(csv.DictReader(stream))
            manifest, samples = [], []
            for fixture in self.policy["fixtures"].split(","):
                for size in self.policy["sizes"].split(","):
                    width, height = size.split("x")
                    manifest.append(original_manifest | dict(fixture=fixture, width=width, height=height))
                    for row in original_samples:
                        samples.append(row | dict(fixture=fixture, width=width, height=height, request=str(len(samples))))
            write_rows(run / "manifest.csv", manifest)
            write_rows(run / "samples.csv", samples)
        write_json(self.root / "launches.json", self.launches)
        _, complete, _ = validate_campaign(self.root)
        self.assertEqual(8 * 30, len(complete))
        for launch in self.launches:
            run = self.root / launch["directory"]
            for filename in ("manifest.csv", "samples.csv"):
                with (run / filename).open(newline="") as stream:
                    rows = list(csv.DictReader(stream))
                write_rows(run / filename, [row for row in rows
                           if not (row["fixture"] == "mandelbrot-overview" and row["width"] == "128")])
        with self.assertRaisesRegex(ValueError, "fixture/size/step matrix"):
            validate_campaign(self.root)

    def test_every_canonical_pan_step_and_action_is_required(self):
        policy = self.policy | dict(fixtures="pan-25")
        rows = [dict(fixture="pan-25", step=step, width="96", height="66", action=action)
                for step, action in (("source", "FRESH"), ("pan", "REUSE"))]
        validate_manifest(policy, rows)
        for invalid in (rows[:1], rows + [rows[0]], [rows[0], rows[1] | dict(action="FRESH")]):
            with self.assertRaisesRegex(ValueError, "matrix"):
                validate_manifest(policy, invalid)

    def test_limits_and_minimum_budget_cannot_be_relaxed(self):
        for change in (dict(process_pairs=2), dict(samples=1), dict(rss_max_pair_ratio=1.1)):
            with self.assertRaises(ValueError):
                validate_policy(self.policy | change)
        self.policy["metrics"][0]["max_ratio"] = .95
        with self.assertRaisesRegex(ValueError, "limit"):
            validate_policy(self.policy)


if __name__ == "__main__":
    unittest.main()
