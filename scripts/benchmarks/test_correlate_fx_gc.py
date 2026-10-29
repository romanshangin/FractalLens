import copy
import unittest

from correlate_fx_gc import SAMPLE, PAUSE, correlate, duration_ns, instant_ns, validate_samples
from summarize_baseline_pairs import FORBIDDEN


def sample():
    return dict(type=SAMPLE, values=dict(startTime="2026-10-29T00:00:01.000000000Z",
                fixture="overview", step="render", width=1512, height=982,
                requestedMode="REFINED", effectiveMode="FAST", phase="sample", run=0, request=1,
                anchorBeforeNanos=1000, anchorAfterNanos=1010,
                requestNanos=100, fxStartNanos=200, plannedNanos=400, firstBackendRegionNanos=500,
                firstVisibleNanos=600, fullPostLayoutNanos=700, snapshotStartNanos=800, snapshotEndNanos=900))


def pause(start, duration=20, gc_id=1, event_type=PAUSE):
    # AnchorBefore maps to exactly 1 second; true offset is uncertain by 10 ns.
    instant = f"2026-10-29T00:00:00.{10**9 + start - 1000:09d}Z"
    return dict(type=event_type, values=dict(startTime=instant, duration=f"PT0.{duration:09d}S", gcId=gc_id))


class CorrelateFxGcTest(unittest.TestCase):
    def rows(self, *pauses):
        return {r["interval"]: r for r in correlate([sample(), *pauses])}

    def test_nanoseconds_and_offsets_are_preserved(self):
        self.assertEqual(instant_ns("2026-10-29T00:00:00.123456789Z"),
                         instant_ns("2026-10-28T18:00:00.123456789-06:00"))
        self.assertEqual(1, instant_ns("2026-10-29T00:00:00.000000001Z") - instant_ns("2026-10-29T00:00:00Z"))
        self.assertEqual(3600000000001, duration_ns("PT1H0.000000001S"))

    def test_planning_pause_is_attributed_to_the_actual_sample_interval(self):
        rows = self.rows(pause(250, 50), pause(820, 30, 2))
        plan = rows["planning"]
        self.assertEqual(1, plan["definite_pause_count"])
        self.assertEqual(50, plan["gc_overlap_lower_ns"])
        self.assertEqual(50, plan["gc_overlap_upper_ns"])
        self.assertEqual("1", plan["possible_gc_ids"])
        self.assertEqual("1", rows["request_to_first_visible"]["possible_gc_ids"])
        self.assertEqual("2", rows["snapshot"]["possible_gc_ids"])
        self.assertEqual(10, plan["clock_uncertainty_ns"])

    def test_snapshot_pause_does_not_explain_first_visible_latency(self):
        rows = self.rows(pause(820, 30))
        self.assertEqual(0, rows["request_to_first_visible"]["possible_pause_count"])
        self.assertEqual(1, rows["snapshot"]["definite_pause_count"])

    def test_boundary_uncertainty_is_not_a_confirmed_overlap(self):
        row = self.rows(pause(395, 20))["planning"]
        self.assertEqual(1, row["possible_pause_count"])
        self.assertEqual(0, row["definite_pause_count"])
        self.assertEqual(0, row["gc_overlap_lower_ns"])
        self.assertEqual(5, row["gc_overlap_upper_ns"])

    def test_nested_gc_phases_are_not_double_counted(self):
        row = self.rows(pause(250, 50), pause(255, 20, event_type="jdk.GCPhasePauseLevel1"))["planning"]
        self.assertEqual(1, row["definite_pause_count"])
        self.assertEqual(50, row["gc_overlap_upper_ns"])

    def test_missing_milestones_and_early_reuse_publication_are_omitted(self):
        event = sample()
        event["values"]["firstVisibleNanos"] = 450
        event["values"]["snapshotStartNanos"] = -1
        event["values"]["snapshotEndNanos"] = -1
        rows = correlate([event, pause(250)])
        self.assertNotIn("first_backend_to_first_visible", [r["interval"] for r in rows])
        self.assertNotIn("snapshot", [r["interval"] for r in rows])

    def test_missing_events_duplicates_and_reversed_anchor_fail(self):
        for events in ([sample()], [pause(250)], [sample(), sample(), pause(250)]):
            with self.assertRaises(ValueError):
                correlate(events)
        event = copy.deepcopy(sample())
        event["values"]["anchorAfterNanos"] = 999
        with self.assertRaisesRegex(ValueError, "Reversed clock anchor"):
            correlate([event, pause(250)])

    def test_diagnostic_runs_are_ineligible_for_timing_verdicts(self):
        self.assertIsNotNone(FORBIDDEN.search("-Dbaseline.fx.jfr=true"))
        self.assertIsNotNone(FORBIDDEN.search("-Dbaseline.fx.jfr=TRUE"))
        self.assertIsNone(FORBIDDEN.search("-Dbaseline.fx.jfr=false"))

    def test_csv_join_requires_exact_request_coverage_identity_and_timing(self):
        row = dict(fixture="overview", step="render", width="1512", height="982", requested_mode="REFINED",
                   effective_mode="FAST", phase="sample", run="0", request="1", plan_ms="0.000200",
                   first_backend_region_ms="0.000400", first_visible_publish_ms="0.000500", full_post_layout_ms="0.000600")
        validate_samples([sample()], [row])
        for change in (dict(request="2"), dict(requested_mode="FAST"), dict(plan_ms="0.001000")):
            with self.assertRaises(ValueError):
                validate_samples([sample()], [row | change])
        with self.assertRaisesRegex(ValueError, "coverage"):
            validate_samples([sample()], [])

    def test_zero_duration_interval_has_no_overlap(self):
        event = sample()
        event["values"]["plannedNanos"] = event["values"]["fxStartNanos"]
        rows = {row["interval"]: row for row in correlate([event, pause(190, 40)])}
        self.assertEqual(0, rows["planning"]["possible_pause_count"])
        self.assertEqual(0, rows["planning"]["gc_overlap_upper_ns"])

    def planning_sample(self):
        event = sample()
        event["values"].update(planningVersion=1, gridStartNanos=210, gridEndNanos=250,
                               frameStartNanos=260, frameEndNanos=390, gridCpuNanos=30, frameCpuNanos=100)
        return event

    def test_new_planning_intervals_keep_cpu_and_gc_separate(self):
        event = self.planning_sample()
        rows = {r["interval"]: r for r in correlate([event, pause(290, 30)])}
        self.assertEqual(40, rows["grid_creation"]["duration_ns"])
        self.assertEqual(30, rows["grid_creation"]["thread_cpu_ns"])
        self.assertEqual(0, rows["grid_creation"]["possible_pause_count"])
        self.assertEqual(130, rows["frame_storage_creation"]["duration_ns"])
        self.assertEqual(100, rows["frame_storage_creation"]["thread_cpu_ns"])
        self.assertEqual(30, rows["frame_storage_creation"]["gc_overlap_lower_ns"])
        self.assertEqual(-1, rows["planning"]["thread_cpu_ns"])

    def test_reuse_or_unsupported_cpu_stays_unmeasured(self):
        event = self.planning_sample()
        event["values"].update(gridCpuNanos=-1, frameCpuNanos=-1)
        rows = {r["interval"]: r for r in correlate([event, pause(290)])}
        self.assertEqual(-1, rows["grid_creation"]["thread_cpu_ns"])
        event["values"].update(gridStartNanos=-1, gridEndNanos=-1, frameStartNanos=-1, frameEndNanos=-1)
        rows = {r["interval"]: r for r in correlate([event, pause(290)])}
        self.assertNotIn("grid_creation", rows)
        self.assertNotIn("frame_storage_creation", rows)

    def test_incomplete_reversed_or_unknown_planning_schema_is_rejected(self):
        for change in (dict(planningVersion=2), dict(gridEndNanos=-1), dict(frameEndNanos=401),
                       dict(gridStartNanos=199), dict(gridCpuNanos=-2)):
            event = self.planning_sample()
            event["values"].update(change)
            with self.assertRaises(ValueError):
                correlate([event, pause(290)])


if __name__ == "__main__":
    unittest.main()
