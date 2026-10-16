#!/usr/bin/env python3
"""Summarize latency traces without mixing gestures, render generations or scopes."""
import argparse
import csv
import math
import statistics
from collections import defaultdict
from pathlib import Path


def summarize(path):
    trials = defaultdict(list)
    lines = path.read_text().splitlines()
    if any(line.startswith("# FAILED") for line in lines):
        raise ValueError("Failed recording")
    for row in csv.DictReader(line for line in lines if not line.startswith("#")):
        if row["phase"] == "failed":
            raise ValueError("Failed recording: do not use an incomplete run for decisions")
        if row["phase"] != "sample":
            continue
        for key in ("input", "render", "nanos", "duration_nanos"):
            row[key] = int(row[key])
        trials[row["mode"], row["gesture"], row["iteration"]].append(row)
    groups = defaultdict(list)
    for (mode, gesture, _), events in trials.items():
        inputs = [e for e in events if e["stage"].startswith("input_")]
        if not inputs:
            raise ValueError("Trial has no input")
        first = min(e["nanos"] for e in inputs)
        last = max(e["nanos"] for e in inputs)
        renders = [e for e in events if e["stage"] == "render_start"]
        if not renders:
            raise ValueError("Trial has no render")
        final_id = max(e["render"] for e in renders)
        final_start = next(e for e in renders if e["render"] == final_id)
        if final_start["input"] != max(e["input"] for e in inputs):
            raise ValueError("Final render does not belong to the last input")
        final = [e for e in events if e["render"] == final_id]
        times = {}
        for event in final:
            times.setdefault(event["stage"], event["nanos"])
        if "complete" not in times:
            raise ValueError("Final render did not complete")
        detail = "aa_publish" if mode == "REFINED" else "base_publish"
        metrics = {"gesture_span_ms": (last - first) / 1e6, "render_count": len(renders)}
        for stage in ("render_start", "reuse_planned", "aa_reuse_prepared", "surface_prepared", "render_submit", "base_first_ready", "base_complete", "aa_first_ready",
                      "base_publish", "aa_publish", "complete", detail + "_screen_observed",
                      "complete_screen_observed"):
            if stage in times:
                metrics[stage + "_from_last_input_ms"] = (times[stage] - last) / 1e6
        for start, end, name in ((detail, detail + "_screen_observed", "details_publish_to_screen_ms"),
                                 ("complete", "complete_screen_observed", "complete_publish_to_screen_ms"),
                                 ("base_complete", "aa_first_ready", "base_complete_to_aa_ready_ms"),
                                 ("render_start", "reuse_planned", "reuse_planning_ms"),
                                 ("reuse_planned", "aa_reuse_prepared", "aa_reuse_preparation_ms"),
                                 ("aa_reuse_prepared", "surface_prepared", "surface_preparation_ms"),
                                 ("render_start", "render_submit", "fx_render_preparation_ms"),
                                 ("render_submit", "base_first_ready", "submit_to_base_callback_ready_ms")):
            if start in times and end in times:
                metrics[name] = (times[end] - times[start]) / 1e6
        for stage in ("preview_publish", "preview_publish_screen_observed"):
            matches = [e["nanos"] for e in events if e["stage"] == stage]
            if matches:
                metrics[stage + "_from_first_input_ms"] = (min(matches) - first) / 1e6
        for event in final:
            if event["stage"].endswith(("_queue_max", "_fx_work_sum", "_fx_work_max")):
                metrics[event["stage"] + "_ms"] = event["duration_nanos"] / 1e6
        for stage in ("screen_probe_max_gap", "screen_probe_total_work"):
            matches = [e["duration_nanos"] for e in events if e["stage"] == stage]
            if matches:
                metrics[stage + "_ms"] = max(matches) / 1e6
        groups[mode, gesture].append(metrics)
    result = []
    for (mode, gesture), observations in sorted(groups.items()):
        for metric in sorted(set().union(*(o.keys() for o in observations))):
            values = sorted(o[metric] for o in observations if metric in o)
            result.append(dict(mode=mode, gesture=gesture, metric=metric, valid=len(values),
                               trials=len(observations), median=f"{statistics.median(values):.3f}",
                               p95=f"{values[math.ceil(.95 * len(values)) - 1]:.3f}"))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rows = summarize(args.input)
    if not rows:
        raise SystemExit("No measured trials")
    with args.output.open("w") as target:
        writer = csv.DictWriter(target, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Saved {args.output}")
