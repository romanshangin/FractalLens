import tempfile
import unittest
from pathlib import Path

import summarize_julia_explorer_latency as summary

HEADER = "# Java=26; screenProbe={screen}; cold=open\n"
COLUMNS = "size,iterations,gesture,phase,iteration,input,render,stage,nanos,duration_nanos\n"
MS = 1_000_000


def row(gesture, phase, input_number, render, stage, ms, duration_ms=0.0):
    return (f"1200x760,300,{gesture},{phase},0,{input_number},{render},{stage},"
            f"{int(ms * MS)},{int(duration_ms * MS)}\n")


def frame(gesture, phase, input_number, render, start_ms, publish_ms, screen_ms=None):
    rows = [row(gesture, phase, input_number, render, "render_start", start_ms),
            row(gesture, phase, input_number, render, "gpu_worker_start", start_ms + 0.5),
            row(gesture, phase, input_number, render, "gpu_call", publish_ms - 2, 4),
            row(gesture, phase, input_number, render, "gpu_kernel_total", publish_ms - 2, 3.5),
            row(gesture, phase, input_number, render, "bright_filter", publish_ms - 2, 1),
            row(gesture, phase, input_number, render, "gpu_ready", publish_ms - 2),
            row(gesture, phase, input_number, render, "gpu_callback", publish_ms - 1.5),
            row(gesture, phase, input_number, render, "approximate_copy", publish_ms - 1, 0.5),
            row(gesture, phase, input_number, render, "approximate_publish", publish_ms),
            row(gesture, phase, input_number, render, "approximate_publish_post_layout", publish_ms + 3)]
    if screen_ms is not None:
        rows.append(row(gesture, phase, input_number, render, "approximate_publish_screen_observed", screen_ms))
    return rows


def drag_trace(screen=False):
    rows = [row("drag", "sample", 1, 0, "input_map_press", 0),
            row("drag", "sample", 2, 0, "input_map_drag", 10)]
    # The first frame starts before the second drag input and must not answer it.
    rows += frame("drag", "sample", 2, 1, 12, 20, 30 if screen else None)
    rows.append(row("drag", "sample", 3, 0, "input_map_drag", 15))
    rows += frame("drag", "sample", 3, 2, 22, 30, 41 if screen else None)
    rows.append(row("drag", "sample", 4, 0, "input_map_release", 40))
    rows += frame("drag", "sample", 4, 3, 42, 50, 60 if screen else None)
    rows.append(row("drag", "sample", 4, 0, "map_committed", 55))
    return rows


class SummarizeJuliaExplorerLatencyTest(unittest.TestCase):
    def write(self, directory, name, rows, screen=False):
        path = Path(directory) / name
        path.write_text(HEADER.format(screen=str(screen).lower()) + COLUMNS + "".join(rows))
        return path

    def metric(self, rows, gesture, name):
        return next(r for r in rows if r["gesture"] == gesture and r["metric"] == name)

    def test_input_response_uses_first_frame_started_after_input(self):
        with tempfile.TemporaryDirectory() as directory:
            rows, screen = summary.summarize([self.write(directory, "drag.csv", drag_trace())])
        self.assertFalse(screen)
        responses = self.metric(rows, "drag", "drag_input_to_first_publish_ms")
        # Input 2 at 10 ms -> frame 1 at 20 ms; input 3 at 15 ms -> frame 2 at 30 ms.
        self.assertEqual(2, responses["count"])
        self.assertEqual(12.5, responses["median"])
        self.assertEqual(15.0, responses["max"])
        self.assertEqual(10.0, self.metric(rows, "drag", "release_to_first_publish_ms")["median"])
        self.assertEqual(10.0, self.metric(rows, "drag", "release_to_final_publish_ms")["median"])
        self.assertEqual(15.0, self.metric(rows, "drag", "release_to_commit_ms")["median"])
        self.assertEqual(15.0, self.metric(rows, "drag", "frame_interval_publish_ms")["median"])
        self.assertEqual(0.5, self.metric(rows, "drag", "fx_queue_ms")["median"])
        self.assertEqual(0.5, self.metric(rows, "drag", "runtime_overhead_ms")["median"])
        self.assertEqual(3, self.metric(rows, "drag", "frames_per_trial")["median"])

    def test_screen_observations_and_unobserved_frames(self):
        trace = drag_trace(screen=True)
        trace = [line for line in trace if not (",2,approximate_publish_screen_observed," in line)]
        with tempfile.TemporaryDirectory() as directory:
            rows, screen = summary.summarize([self.write(directory, "screen.csv", trace, screen=True)])
        self.assertTrue(screen)
        # Input 3 is first visible through the later frame 3 at 60 ms.
        self.assertEqual(45.0, self.metric(rows, "drag", "drag_input_to_first_screen_ms")["max"])
        self.assertAlmostEqual(100 / 3, self.metric(rows, "drag", "frames_without_screen_pct")["median"], places=2)
        self.assertEqual(20.0, self.metric(rows, "drag", "release_to_final_screen_ms")["median"])

    def test_open_trial_reports_cold_frame(self):
        rows = [row("open", "cold", 0, 0, "map_open", 0)] + frame("open", "cold", 0, 1, 5, 130)
        with tempfile.TemporaryDirectory() as directory:
            result, _ = summary.summarize([self.write(directory, "open.csv", rows)])
        self.assertEqual(130.0, self.metric(result, "open", "open_to_first_publish_ms")["median"])
        self.assertEqual("cold", self.metric(result, "open", "open_to_first_publish_ms")["phase"])

    def test_warmup_is_excluded_by_default(self):
        trace = [line.replace(",sample,", ",warmup,") for line in drag_trace()]
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "warmup.csv", trace)
            with self.assertRaises(ValueError):
                summary.summarize([path])
            rows, _ = summary.summarize([path], include_warmup=True)
        self.assertEqual(1, self.metric(rows, "drag", "frames_per_trial")["trials"])

    def test_rejects_failed_mixed_and_uncommitted_recordings(self):
        with tempfile.TemporaryDirectory() as directory:
            failed = self.write(directory, "failed.csv", drag_trace() + ["# FAILED: boom\n"])
            with self.assertRaisesRegex(ValueError, "failed"):
                summary.summarize([failed])
            control = self.write(directory, "control.csv", drag_trace())
            screen = self.write(directory, "screen.csv", drag_trace(), screen=True)
            with self.assertRaisesRegex(ValueError, "mix"):
                summary.summarize([control, screen])
            uncommitted = self.write(directory, "uncommitted.csv",
                                     [line for line in drag_trace() if "map_committed" not in line])
            with self.assertRaisesRegex(ValueError, "commit"):
                summary.summarize([uncommitted])

    def test_pulse_log_uses_main_window_region(self):
        log = "\n".join([
            "PULSE: 10 [8ms:9ms]",
            "T28 : Dirty Region 0: RectBounds { minX:0.0, minY:0.0, maxX:1200.0, maxY:776.0} (w:1200.0, h:776.0)",
            "T28 (0 +2ms): Painting",
            "T28 (2 +0ms): Presenting",
            "T28 : Dirty Region 0: RectBounds { minX:1.0, minY:1.0, maxX:13.0, maxY:13.0} (w:12.0, h:12.0)",
            "T28 (2 +0ms): Painting",
            "T28 (2 +5ms): Presenting",
            "PULSE: 11 [8ms:7ms]",
            "T28 : Dirty Region 0: RectBounds { minX:0.0, minY:0.0, maxX:1200.0, maxY:776.0} (w:1200.0, h:776.0)",
            "T28 (0 +3ms): Painting",
            "T28 (3 +1ms): Presenting",
        ])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pulse.log"
            path.write_text(log)
            rows = {r["metric"]: r for r in summary.pulse_metrics(path)}
        self.assertEqual(2, rows["prism_paint_ms"]["count"])
        self.assertEqual(2.5, rows["prism_paint_ms"]["mean"])
        self.assertEqual(1, rows["prism_present_ms"]["max"])
        self.assertEqual(8, rows["pulse_interval_ms"]["median"])


if __name__ == "__main__":
    unittest.main()
