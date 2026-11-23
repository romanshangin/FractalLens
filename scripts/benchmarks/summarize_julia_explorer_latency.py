#!/usr/bin/env python3
"""Summarize Explore Julia Parameters latency traces.

Input CSVs come from JuliaExplorerLatencyBenchmark. Several inputs are pooled
(for example repeated cold JVM starts); they must share the screen-probe setting.
Each published frame keeps the input count at its render start, so the response
to input k is the first frame whose render started at or after input k.
"""
import argparse
import csv
import math
import re
import statistics
from collections import defaultdict
from pathlib import Path

FRAME = "approximate_publish"
DURATIONS = ("gpu_call", "gpu_setup", "gpu_upload", "gpu_dispatch", "gpu_readback",
             "gpu_kernel_total", "bright_filter", "approximate_copy")


def nearest_rank(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def read_trials(paths):
    """Returns ({trial key: [events]}, screen probe flag)."""
    trials = defaultdict(list)
    screen = None
    for index, path in enumerate(paths):
        lines = Path(path).read_text().splitlines()
        if any(line.startswith("# FAILED") for line in lines):
            raise ValueError(f"{path}: failed recording; do not use an incomplete run")
        header = next((line for line in lines if line.startswith("# Java=")), None)
        if header is None:
            raise ValueError(f"{path}: missing environment header")
        flag = "screenProbe=true" in header
        if screen is not None and flag != screen:
            raise ValueError("Inputs mix screen-probe and control runs")
        screen = flag
        for row in csv.DictReader(line for line in lines if not line.startswith("#")):
            for key in ("input", "render", "nanos", "duration_nanos"):
                row[key] = int(row[key])
            trials[index, row["size"], row["iterations"], row["gesture"], row["phase"], row["iteration"]].append(row)
    if not trials:
        raise ValueError("No trials")
    return trials, screen


def frames(events):
    """Returns per-render frame records in publication order."""
    by_render = defaultdict(dict)
    for event in events:
        if event["render"] > 0:
            stage_times = by_render[event["render"]]
            if event["stage"] in DURATIONS:
                stage_times[event["stage"] + "#"] = event["duration_nanos"]
            stage_times.setdefault(event["stage"], event["nanos"])
            stage_times.setdefault("input", event["input"])
    published = [dict(times, render=render) for render, times in by_render.items() if FRAME in times]
    return sorted(published, key=lambda frame: frame[FRAME])


def first_response(frame_list, input_number, stage):
    """Earliest time at which a frame started at or after the input reached the stage."""
    times = [frame[stage] for frame in frame_list if frame["input"] >= input_number and stage in frame]
    return min(times) if times else None


def trial_metrics(events):
    metrics = defaultdict(list)
    frame_list = frames(events)
    if not frame_list:
        raise ValueError("Trial published no frame")
    for frame in frame_list:
        for name in DURATIONS:
            if name + "#" in frame:
                metrics[name + "_ms"].append(frame[name + "#"] / 1e6)
        pairs = (("render_start", "gpu_worker_start", "submit_to_worker_ms"),
                 ("gpu_ready", "gpu_callback", "fx_queue_ms"),
                 ("gpu_callback", FRAME, "callback_to_publish_ms"),
                 ("render_start", FRAME, "render_start_to_publish_ms"),
                 (FRAME, FRAME + "_post_layout", "publish_to_post_layout_ms"),
                 (FRAME, FRAME + "_screen_observed", "publish_to_screen_ms"),
                 ("render_start", FRAME + "_screen_observed", "render_start_to_screen_ms"))
        for start, end, name in pairs:
            if start in frame and end in frame:
                metrics[name].append((frame[end] - frame[start]) / 1e6)
        if "gpu_call#" in frame and "gpu_kernel_total#" in frame:
            metrics["runtime_overhead_ms"].append((frame["gpu_call#"] - frame["gpu_kernel_total#"]) / 1e6)
    for previous, current in zip(frame_list, frame_list[1:]):
        if "render_start" in current:
            metrics["publish_to_next_render_ms"].append((current["render_start"] - previous[FRAME]) / 1e6)
    for stage, name in ((FRAME, "frame_interval_publish_ms"),
                        (FRAME + "_post_layout", "frame_interval_post_layout_ms"),
                        (FRAME + "_screen_observed", "frame_interval_screen_ms")):
        times = [frame[stage] for frame in frame_list if stage in frame]
        metrics[name].extend((b - a) / 1e6 for a, b in zip(times, times[1:]))
    metrics["frames_per_trial"].append(len(frame_list))
    for stage, name in ((FRAME + "_post_layout", "frames_without_post_layout_pct"),
                        (FRAME + "_screen_observed", "frames_without_screen_pct")):
        if any(stage in frame for frame in frame_list):
            missing = sum(stage not in frame for frame in frame_list)
            metrics[name].append(100 * missing / len(frame_list))
    for event in events:
        if event["stage"] in ("screen_probe_max_gap", "screen_probe_total_work") and event["duration_nanos"]:
            metrics[event["stage"] + "_ms"].append(event["duration_nanos"] / 1e6)

    def response(input_event, label):
        number = input_event["input"]
        for stage, suffix in (("render_start", "render_start"), (FRAME, "publish"),
                              (FRAME + "_post_layout", "post_layout"), (FRAME + "_screen_observed", "screen")):
            time = first_response(frame_list, number, stage)
            if time is not None:
                metrics[f"{label}_to_first_{suffix}_ms"].append((time - input_event["nanos"]) / 1e6)

    for event in events:
        if event["stage"] == "input_map_drag":
            response(event, "drag_input")
    opened = [e for e in events if e["stage"] == "map_open"]
    if opened:
        start = opened[0]["nanos"]
        first = frame_list[0]
        for stage, suffix in (("render_start", "render_start"), (FRAME, "publish"),
                              (FRAME + "_post_layout", "post_layout"), (FRAME + "_screen_observed", "screen")):
            if stage in first:
                metrics[f"open_to_first_{suffix}_ms"].append((first[stage] - start) / 1e6)
        for name in ("gpu_call", "gpu_setup", "gpu_dispatch", "bright_filter"):
            if name + "#" in first:
                metrics[f"open_frame_{name}_ms"].append(first[name + "#"] / 1e6)
        if "gpu_call#" in first and "gpu_kernel_total#" in first:
            metrics["open_frame_runtime_overhead_ms"].append((first["gpu_call#"] - first["gpu_kernel_total#"]) / 1e6)
    releases = [e for e in events if e["stage"] == "input_map_release"]
    committed = [e for e in events if e["stage"] == "map_committed"]
    if releases:
        release = releases[-1]
        response(release, "release")
        if not committed:
            raise ValueError("Released gesture did not commit")
        commit = committed[-1]["nanos"]
        final = [frame for frame in frame_list if frame[FRAME] <= commit][-1]
        metrics["release_to_commit_ms"].append((commit - release["nanos"]) / 1e6)
        metrics["release_to_final_publish_ms"].append((final[FRAME] - release["nanos"]) / 1e6)
        if FRAME + "_screen_observed" in final:
            metrics["release_to_final_screen_ms"].append((final[FRAME + "_screen_observed"] - release["nanos"]) / 1e6)
        metrics["frames_after_release"].append(sum(frame["input"] >= release["input"] for frame in frame_list))
        presses = [e for e in events if e["stage"] == "input_map_press"]
        if presses:
            metrics["press_to_commit_ms"].append((commit - presses[0]["nanos"]) / 1e6)
    return metrics


PULSE_HEADER = re.compile(r"^PULSE: \d+ \[(\d+)ms:(\d+)ms\]")
DIRTY_REGION = re.compile(r"^T\d+ : Dirty Region 0: .*\(w:([\d.]+), h:([\d.]+)\)")
PHASE = re.compile(r"^T\d+ \(-?\d+ \+(\d+)ms\): (Painting|Presenting)$")


def pulse_metrics(path):
    """Prism painting/presenting of the largest (main-window) dirty region from a JavaFX pulse log.

    The logger reports whole milliseconds, so the values have a 1 ms resolution.
    """
    segments = []
    region = None
    pulse = None
    for line in Path(path).read_text().splitlines():
        header = PULSE_HEADER.match(line)
        if header:
            pulse = (int(header.group(1)), int(header.group(2)))
            continue
        dirty = DIRTY_REGION.match(line)
        if dirty:
            region = {"area": float(dirty.group(1)) * float(dirty.group(2)), "pulse": pulse}
            segments.append(region)
            continue
        phase = PHASE.match(line)
        if phase and region is not None:
            region.setdefault(phase.group(2), int(phase.group(1)))
    if not segments:
        raise ValueError(f"{path}: no rendered pulses; was -Djavafx.pulseLogger=true set?")
    largest = max(segment["area"] for segment in segments)
    main = [segment for segment in segments if segment["area"] == largest and "Painting" in segment]
    metrics = {
        "prism_paint_ms": [segment["Painting"] for segment in main],
        "prism_present_ms": [segment["Presenting"] for segment in main if "Presenting" in segment],
        "pulse_interval_ms": [segment["pulse"][0] for segment in main if segment["pulse"]],
        "pulse_duration_ms": [segment["pulse"][1] for segment in main if segment["pulse"]],
    }
    return [{"metric": name, "count": len(values), "median": statistics.median(values),
             "mean": round(statistics.fmean(values), 3), "p95": nearest_rank(values, 0.95), "max": max(values)}
            for name, values in metrics.items() if values]


def summarize(paths, include_warmup=False):
    trials, screen = read_trials(paths)
    pooled = defaultdict(lambda: defaultdict(list))
    trial_counts = defaultdict(int)
    for (index, size, iterations, gesture, phase, iteration), events in trials.items():
        if phase == "warmup" and not include_warmup:
            continue
        group = "sample" if phase in ("sample", "warmup") else phase
        key = (size, iterations, gesture, group)
        trial_counts[key] += 1
        for name, values in trial_metrics(events).items():
            pooled[key][name].extend(values)
    rows = []
    for key in sorted(pooled):
        for name in sorted(pooled[key]):
            values = pooled[key][name]
            if not values:
                continue
            rows.append({
                "size": key[0], "iterations": key[1], "gesture": key[2], "phase": key[3],
                "trials": trial_counts[key], "metric": name, "count": len(values),
                "median": round(statistics.median(values), 3),
                "p95": round(nearest_rank(values, 0.95), 3),
                "max": round(max(values), 3),
            })
    if not rows:
        raise ValueError("No measured trials; warmups are excluded unless --include-warmup is given")
    return rows, screen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="*", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-warmup", action="store_true")
    parser.add_argument("--pulse-log", type=Path, help="summarize a JavaFX pulse log instead of traces")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.pulse_log:
        if args.inputs:
            parser.error("--pulse-log takes no trace inputs")
        rows = pulse_metrics(args.pulse_log)
        with args.output.open("w", newline="") as output:
            output.write("# JavaFX pulse logger, main-window dirty region, 1 ms resolution\n")
            writer = csv.DictWriter(output, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
        return
    if not args.inputs:
        parser.error("trace inputs are required")
    rows, screen = summarize(args.inputs, args.include_warmup)
    with args.output.open("w", newline="") as output:
        output.write(f"# screenProbe={str(screen).lower()}; inputs={len(args.inputs)}\n")
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
