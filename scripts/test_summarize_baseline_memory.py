import json
import csv
from datetime import datetime, timedelta, timezone
import unittest

import test_summarize_baseline_input as input_tests
from summarize_baseline_memory import summarize, validate, timestamp, allocation_groups


class MemorySummaryTest(unittest.TestCase):
    def test_jfr_nanosecond_offset_matches_utc_scope(self):
        self.assertEqual(timestamp("2026-09-08T19:24:32.787412333-06:00"), timestamp("2026-09-09T01:24:32.787412Z"))

    def test_allocation_weights_respect_scope_boundaries_and_missing_stacks(self):
        scopes = [dict(start="2026-09-08T00:00:01Z", end="2026-09-08T00:00:02Z", scope="navigation", fixture="deep", mode="FAST"),
                  dict(start="2026-09-08T00:00:02Z", end="2026-09-08T00:00:03Z", scope="control", fixture="deep", mode="FAST")]
        events = [dict(values=dict(startTime=t, objectClass=dict(name="[I"), weight=w, stackTrace=None))
                  for t, w in (("2026-09-08T00:00:01.500000123Z", 100), ("2026-09-08T00:00:02Z", 200), ("2026-09-08T00:00:03Z", 300))]
        result = {r["scope"]: r for r in allocation_groups(events, scopes)}
        self.assertEqual(100, result["navigation"]["estimated_bytes"])
        self.assertEqual(200, result["control"]["estimated_bytes"])
        self.assertEqual("none", result["outside_scopes"]["fixture"])
        self.assertEqual("no_application_frame", result["navigation"]["application_frame"])

    def setUp(self):
        fixture = input_tests.InputSummaryTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.path = fixture.path
        self.work = self.path / "workload"
        self.work.mkdir()
        (self.path / "MONITOR_SUCCESS").write_text("ok")
        (self.work / "SUCCESS").write_text("rounds=2\ntrials=4\n")
        (self.work / "environment.txt").write_text("memory_matrix=9.1-memory-v1\ncycles=1\nseconds=1\nmodes=FAST\nrender_diagnostics=true\n")
        (self.work / "manifest.csv").write_text((self.path / "manifest.csv").read_text())
        rows, events, actual = [], [], []
        self.scopes = []
        origin = datetime(2026, 9, 8, tzinfo=timezone.utc)
        def scope(round_id, trial, name):
            start = origin + timedelta(seconds=len(self.scopes))
            self.scopes.append(dict(round=str(round_id), fixture="all" if name == "detached_gc_checkpoint" else "example",
                                    mode="FAST", cycle="0" if trial else "-1", trial=str(trial),
                                    scope=name, start=start.isoformat(), end=(start + timedelta(seconds=1)).isoformat(),
                                    elapsed_ms="1000", heap_before="100", heap_after="100", nonheap_after="20",
                                    direct_bytes="0", mapped_bytes="0", gc_count_delta="0", gc_ms_delta="0"))
        for round_id in range(2):
            scope(round_id, 0, "seed_and_control")
            for gesture in ("pinch", "sequence"):
                trial = str(len(rows) + 1)
                row = dict(fixture.rows[0], trial=trial, gesture=gesture, phase="sample", run=str(round_id))
                rows.append(row)
                events.extend(dict(e, trial=trial) for e in fixture.events if e["trial"] == "1")
                for stage, nanos in (("render_submit", "2000000"), ("diagnostic_request_submitted", "2000000"),
                                     ("diagnostic_coordinator_exit", "3000000"), ("diagnostic_request_drained", "3000000"),
                                     ("diagnostic_worker_tasks_terminal", "3000000")):
                    events.append(dict(trial=trial, epoch="1", input="1", render="1", stage=stage, nanos=nanos, duration_nanos="0"))
                actual.extend(dict(e, trial=trial) for e in fixture.actual if e["trial"] == "1")
                scope(round_id, trial, "navigation"); scope(round_id, trial, "control")
            scope(round_id, 0, "detached_gc_checkpoint")
        for name, data in (("samples", rows), ("events", events), ("actual-manifest", actual), ("scopes", self.scopes)):
            input_tests.write_csv(self.work / (name + ".csv"), data)
        input_tests.write_csv(self.path / "process.csv", [dict(time=origin.isoformat(), elapsed_s="0", rss_bytes="1024", cpu_percent="0", thermal_state="nominal"),
                                                         dict(time=(origin + timedelta(seconds=20)).isoformat(), elapsed_s="20", rss_bytes="2048", cpu_percent="10", thermal_state="nominal")])
        for name, text in (("baseline", "Baseline taken"), ("final", "Total: reserved=1KB, committed=1KB")):
            (self.path / ("nmt-" + name + ".json")).write_text(json.dumps(dict(returncode=0, stdout=text)))

    def save_scopes(self):
        input_tests.write_csv(self.work / "scopes.csv", self.scopes)

    def test_complete_scopes_keep_control_cost_separate(self):
        result = summarize(self.path)
        self.assertEqual(4, result["trials"])
        self.assertEqual(4000, result["scope_totals_ms"]["navigation"])
        self.assertEqual([100, 100], result["detached_heap_bytes"])

    def test_rejects_missing_native_snapshot(self):
        (self.path / "nmt-final.json").write_text(json.dumps(dict(returncode=1, stdout="")))
        with self.assertRaisesRegex(ValueError, "native memory"):
            validate(self.path)

    def test_rejects_scope_overlap(self):
        self.scopes[1]["start"] = self.scopes[0]["start"]
        self.save_scopes()
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            validate(self.path)

    def test_rejects_missing_control(self):
        self.scopes = [s for s in self.scopes if not (s["scope"] == "control" and s["trial"] == "2")]
        self.save_scopes()
        with self.assertRaisesRegex(ValueError, "control scope"):
            validate(self.path)

    def test_rejects_incomplete_round(self):
        (self.work / "SUCCESS").write_text("rounds=3\ntrials=4\n")
        with self.assertRaisesRegex(ValueError, "coverage"):
            validate(self.path)

    def test_rejects_too_short_run(self):
        env = self.work / "environment.txt"
        env.write_text(env.read_text().replace("seconds=1", "seconds=100"))
        with self.assertRaisesRegex(ValueError, "shorter"):
            validate(self.path)

    def test_rejects_missing_checkpoint(self):
        self.scopes = [s for s in self.scopes if not (s["scope"] == "detached_gc_checkpoint" and s["round"] == "0")]
        self.save_scopes()
        with self.assertRaisesRegex(ValueError, "checkpoint"):
            validate(self.path)

    def test_rejects_seed_from_wrong_mode(self):
        self.scopes[0]["mode"] = "REFINED"
        self.save_scopes()
        with self.assertRaisesRegex(ValueError, "scope identity"):
            validate(self.path)

    def test_rejects_missing_worker_drain(self):
        path = self.work / "events.csv"
        with path.open() as stream:
            events = [e for e in csv.DictReader(stream) if not (e["trial"] == "1" and e["stage"] == "diagnostic_request_drained")]
        input_tests.write_csv(path, events)
        with self.assertRaisesRegex(ValueError, "request_drained"):
            validate(self.path)


if __name__ == "__main__":
    unittest.main()
