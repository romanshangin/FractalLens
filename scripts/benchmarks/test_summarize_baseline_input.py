import csv
import tempfile
import unittest
from pathlib import Path

from summarize_baseline_input import validate, summarize


def write_csv(path, rows):
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)


class InputSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        (self.path / "SUCCESS").write_text("verified\n")
        (self.path / "environment.txt").write_text("input_matrix=9.1-input-v1\ngestures=wheel\nmodes=FAST\nwarmups=0\nsamples=1\n")
        manifest = dict(fixture="example", step="render", width="480", height="270", actual_cap="300", aa="EMPTY",
                        center_real="0", center_imaginary="0", scale="1")
        write_csv(self.path / "manifest.csv", [manifest])
        self.rows, self.events, self.actual = [], [], []
        for trial, phase, run in [("1", "first_sequence", "-1"), ("2", "sample", "0")]:
            self.rows.append(dict(trial=trial, fixture="example", step="render", seed_width="480", seed_height="270",
                                 gesture="wheel", requested_mode="FAST", phase=phase, run=run, status="complete", width="480", height="270",
                                 cap="300", aa="EMPTY", sample_hash="42", argb_hash="43", render_count="1",
                                 first_input_to_preview_ms="-1", first_input_to_preview_post_layout_ms="-1",
                                 last_input_to_render_ms="1", last_input_to_base_ms="2", last_input_to_aa_ms="-1",
                                 last_input_to_complete_ms="3", last_input_to_post_layout_ms="4",
                                 last_input_to_first_target_ms="2", last_input_to_first_target_post_layout_ms="4"))
            for stage, nanos, render in [("input_scroll", 1_000_000, "0"), ("render_start", 2_000_000, "1"),
                                         ("base_publish", 3_000_000, "1"), ("complete", 4_000_000, "1"),
                                         ("base_publish_post_layout", 5_000_000, "1"), ("complete_post_layout", 5_000_000, "1")]:
                self.events.append(dict(trial=trial, epoch="1", input="1", render=render, stage=stage, nanos=str(nanos), duration_nanos="0"))
            for role in ("source", "target"):
                self.actual.append(dict(trial=trial, role=role, **manifest))
        self.save()

    def save(self):
        write_csv(self.path / "samples.csv", self.rows)
        write_csv(self.path / "events.csv", self.events)
        write_csv(self.path / "actual-manifest.csv", self.actual)

    def test_complete_data_and_absent_scopes(self):
        self.assertEqual(2, summarize(self.path, self.path / "summary.csv"))
        with (self.path / "summary.csv").open() as stream:
            rows = list(csv.DictReader(stream))
        absent = next(r for r in rows if r["scope"] == "last_input_to_aa_ms")
        self.assertEqual("0", absent["present"])
        self.assertEqual("-1", absent["median_ms"])
        with self.assertRaises(FileExistsError):
            summarize(self.path, self.path / "summary.csv")

    def test_rejects_missing_success(self):
        (self.path / "SUCCESS").unlink()
        with self.assertRaisesRegex(ValueError, "SUCCESS"):
            validate(self.path)

    def test_rejects_duplicate_trial(self):
        self.rows.append(self.rows[0])
        self.save()
        with self.assertRaisesRegex(ValueError, "Duplicate trial"):
            validate(self.path)

    def test_rejects_missing_repetition(self):
        self.rows.pop()
        self.events = [e for e in self.events if e["trial"] == "1"]
        self.actual = [e for e in self.actual if e["trial"] == "1"]
        self.save()
        with self.assertRaisesRegex(ValueError, "repetition"):
            validate(self.path)

    def test_rejects_pulse_from_another_generation(self):
        self.events[-1]["input"] = "2"
        self.save()
        with self.assertRaisesRegex(ValueError, "generation"):
            validate(self.path)

    def test_rejects_timing_disagreeing_with_raw_events(self):
        self.rows[-1]["last_input_to_complete_ms"] = "1"
        self.save()
        with self.assertRaisesRegex(ValueError, "raw events"):
            validate(self.path)

    def test_rejects_unstable_endpoint(self):
        self.actual[-1]["scale"] = ".99"
        self.save()
        with self.assertRaisesRegex(ValueError, "Non-repeatable"):
            validate(self.path)


if __name__ == "__main__":
    unittest.main()
