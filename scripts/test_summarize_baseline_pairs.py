import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from summarize_baseline_pairs import validate_policy, validate_campaign, paired_statistics, summarize
from prepare_baseline_build import verify_build


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def write_rows(path, rows):
    with path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0], lineterminator="\n")
        writer.writeheader(); writer.writerows(rows)


class PairValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.policy = dict(version="9.1-pairs-v1", purpose="calibration", runner="headless", sizes="480x270", fixtures="case",
                           process_pairs=3, samples=10, warmups=1, timeout_seconds=100,
                           metrics=[dict(fixture="case", step="render", metric="backend_ms", role="target", max_ratio=.9),
                                    dict(fixture="case", step="render", metric="returned_argb_ms", role="control", max_ratio=1.05)])
        write_json(self.root / "policy.json", self.policy)
        self.policy_hash = hashlib.sha256((self.root / "policy.json").read_bytes()).hexdigest()
        for label in "AB":
            write_json(self.root / ("build-" + label + ".json"), dict(identity="same", revision="revision"))
        self.launches = []
        for pair in range(3):
            order = "AB" if pair % 2 == 0 else "BA"
            for label in order:
                name = f"pair-{pair:02d}-{label}"
                d = self.root / name; d.mkdir()
                self.launches.append(dict(process_pair=pair, order=order, label=label, directory=name, policy_sha256=self.policy_hash,
                                          command=["java"], result=dict(returncode=0)))
                write_rows(d / "manifest.csv", [dict(matrix="9.1-v1", fixture="case", step="render", width="480", height="270", actual_cap="300", cancel_trigger="none")])
                (d / "environment.txt").write_text("matrix=9.1-v1\njava=26\nvm=HotSpot\nos=macOS\narch=aarch64\nprocessors=12\nworkers_per_pool=11\nmax_heap_bytes=4294967296\naa_profile=false\nvm_arguments=[-Xmx4g]\nsamples=10\nwarmups=1\nrevision=revision\nlabel=" + label + "\n")
                rows = []
                for phase, run in [("cold_fixture", -2), ("warmup", -1)] + [("sample", i) for i in range(10)]:
                    rows.append(dict(fixture="case", step="render", width="480", height="270", phase=phase, run=str(run), backend="CPU", cap="300",
                                     reused_pixels="0", reuse_source="none", complete="true", sample_hash="42", argb_hash="43", backend_ms="10", returned_argb_ms="20"))
                write_rows(d / "samples.csv", rows)
        write_json(self.root / "launches.json", self.launches)
        (self.root / "CAMPAIGN_COMPLETE").write_text("complete\n")

    def change_rows(self, directory, change):
        p = self.root / directory / "samples.csv"
        with p.open() as f:
            rows = list(csv.DictReader(f))
        change(rows)
        write_rows(p, rows)

    def test_complete_calibration_never_claims_speedup(self):
        result = summarize(self.root)
        self.assertEqual("calibration_only", result["timing_verdict"])
        self.assertEqual("not_evaluated", result["production_promotion"])
        self.assertEqual(30, result["metrics"][0]["pairs"])
        self.assertEqual(3, result["metrics"][0]["process_pairs"])
        with self.assertRaisesRegex(ValueError, "already exists"):
            summarize(self.root)

    def test_rejects_changed_order(self):
        self.launches[0], self.launches[1] = self.launches[1], self.launches[0]
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "reordered"):
            validate_campaign(self.root)

    def test_rejects_missing_cold_row(self):
        self.change_rows("pair-00-A", lambda rows: rows.pop(0))
        with self.assertRaisesRegex(ValueError, "Missing cold"):
            validate_campaign(self.root)

    def test_rejects_missing_measured_pair(self):
        self.change_rows("pair-00-B", lambda rows: rows.pop())
        with self.assertRaisesRegex(ValueError, "Missing cold"):
            validate_campaign(self.root)

    def test_rejects_instrumentation_in_actual_jvm_arguments(self):
        p = self.root / "pair-00-A/environment.txt"
        p.write_text(p.read_text().replace("[-Xmx4g]", "[-Xmx4g, -XX:StartFlightRecording=settings=profile]"))
        with self.assertRaisesRegex(ValueError, "Instrumented"):
            validate_campaign(self.root)

    def test_rejects_changed_heap(self):
        p = self.root / "pair-00-A/environment.txt"
        p.write_text(p.read_text().replace("4294967296", "2147483648"))
        with self.assertRaisesRegex(ValueError, "heap mismatch"):
            validate_campaign(self.root)

    def test_rejects_changed_runtime(self):
        p = self.root / "pair-01-B/environment.txt"
        p.write_text(p.read_text().replace("java=26", "java=27"))
        with self.assertRaisesRegex(ValueError, "Runtime/hardware/heap mismatch"):
            validate_campaign(self.root)

    def test_rejects_unsuccessful_process_even_with_complete_rows(self):
        self.launches[-1]["result"]["returncode"] = 1
        write_json(self.root / "launches.json", self.launches)
        with self.assertRaisesRegex(ValueError, "Unsuccessful JVM"):
            validate_campaign(self.root)

    def test_rejects_nan_metric(self):
        self.change_rows("pair-02-B", lambda rows: rows[-1].update(backend_ms="NaN"))
        with self.assertRaisesRegex(ValueError, "Invalid timing"):
            validate_campaign(self.root)

    def test_rejects_stable_but_different_candidate_fingerprint(self):
        for pair in range(3):
            self.change_rows(f"pair-{pair:02d}-B", lambda rows: [r.update(sample_hash="99") for r in rows])
        with self.assertRaisesRegex(ValueError, "fingerprint mismatch"):
            validate_campaign(self.root)

    def test_rejects_retroactive_policy_change(self):
        self.policy["metrics"][0]["max_ratio"] = .8
        write_json(self.root / "policy.json", self.policy)
        with self.assertRaisesRegex(ValueError, "Policy changed"):
            validate_campaign(self.root)

    def test_rejects_missing_metric(self):
        self.change_rows("pair-00-B", lambda rows: rows[-1].update(backend_ms="-1"))
        with self.assertRaisesRegex(ValueError, "metric missing"):
            validate_campaign(self.root)

    def test_rejects_short_confirmation(self):
        self.policy["samples"] = 9
        with self.assertRaisesRegex(ValueError, "30 measured pairs"):
            validate_policy(self.policy)

    def test_candidate_timing_pass_still_requires_production_validation(self):
        self.policy["purpose"] = "candidate"
        write_json(self.root / "policy.json", self.policy)
        digest = hashlib.sha256((self.root / "policy.json").read_bytes()).hexdigest()
        for r in self.launches:
            r["policy_sha256"] = digest
        write_json(self.root / "launches.json", self.launches)
        write_json(self.root / "build-B.json", dict(identity="candidate", revision="revision"))
        for pair in range(3):
            self.change_rows(f"pair-{pair:02d}-B", lambda rows: [r.update(backend_ms="8", returned_argb_ms="18") for r in rows])
        result = summarize(self.root)
        self.assertEqual("pass", result["timing_verdict"])
        self.assertEqual("not_evaluated", result["production_promotion"])

    def test_process_clusters_contribute_to_uncertainty(self):
        rows = [dict(process_pair=p, a_ms=10, b_ms=10 * ratio, ratio=ratio) for p, ratio in enumerate((.5, 1, 2)) for _ in range(10)]
        result = paired_statistics(rows, resamples=1000)
        self.assertLessEqual(result["ratio_ci95_low"], .5)
        self.assertGreaterEqual(result["ratio_ci95_high"], 2)


class SnapshotTest(unittest.TestCase):
    def test_changed_compiled_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp)
            for folder in ("classes", "test-classes", "lib"):
                (p / folder).mkdir()
            (p / "classes/test.class").write_bytes(b"before")
            files = {"classes/test.class": hashlib.sha256(b"before").hexdigest()}
            write_json(p / "build.json", dict(version="9.1-build-v1", files=files, dependencies=[], identity=hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()))
            verify_build(p)
            (p / "classes/test.class").write_bytes(b"after")
            with self.assertRaisesRegex(ValueError, "snapshot changed"):
                verify_build(p)


if __name__ == "__main__":
    unittest.main()
