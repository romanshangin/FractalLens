#!/usr/bin/env python3
"""Validate request drain accounting and summarize base scheduling diagnostics."""
import csv
import sys
from collections import defaultdict
from pathlib import Path


def require(value, message):
    if not value:
        raise ValueError(message)


def read(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def validate_request(v):
    for key in ("request_submitted", "request_drained", "worker_tasks_terminal"):
        require(key in v, f"Missing {key}")
    submitted, drained = v["request_submitted"], v["request_drained"]
    end = v.get("coordinator_exit", v.get("coordinator_cancelled_before_start"))
    require(end is not None and submitted <= end <= drained, "Coordinator did not drain")
    metrics = lambda k: k.endswith("_ns") or k.startswith("worker_tasks_") or k in ("planned_tasks", "candidate_tiles", "generation")
    for key, value in v.items():
        require(value >= 0 if metrics(key) else submitted <= value <= drained, f"Invalid {key}")
    registered = v.get("worker_tasks_registered", 0)
    started = v.get("worker_tasks_started", 0)
    require(registered == v["worker_tasks_terminal"], "Workers did not drain")
    if "cancel_requested" not in v:
        require(registered == v.get("planned_tasks", 0), "Untracked planned tasks")
    require(registered == started + v.get("worker_tasks_cancelled_before_start", 0), "Unaccounted tasks")
    require(started <= v.get("worker_tasks_submitted", 0) <= registered, "Invalid submitted tasks")
    require(bool(started) == ("last_worker_exit" in v), "Missing or spurious worker exit")
    for a, b in (("coordinator_start", "backend_start"), ("backend_start", "backend_exit"),
                 ("backend_exit", "coordinator_exit"), ("planning_start", "planning_end"),
                 ("reference_start", "reference_end"), ("coordinates_start", "coordinates_end"),
                 ("bla_start", "bla_end"), ("first_task_submitted", "first_worker_start"),
                 ("first_worker_start", "last_worker_exit"), ("cancel_requested", "cancel_observed")):
        if a in v and b in v:
            require(v[a] <= v[b], f"Reversed {a}/{b}")
    for metric in ("worker_queue", "worker_run"):
        require(v.get(metric + "_sum_ns", 0) >= v.get(metric + "_max_ns", 0), "Invalid worker aggregate")


def validate(directory):
    require((directory / "SUCCESS").is_file(), "Missing conformance SUCCESS")
    env = dict(line.split("=", 1) for line in (directory / "environment.txt").read_text().splitlines() if "=" in line)
    rows = read(directory / "samples.csv")
    require(rows, "No trials")
    trials = {r["trial"]: r for r in rows}
    require(len(trials) == len(rows), "Duplicate trial")
    require(set(map(int, trials)) == set(range(1, len(rows) + 1)), "Missing trial")
    groups = defaultdict(dict)
    if "input_matrix" in env:
        require(env.get("render_diagnostics") == "true", "Input diagnostics were disabled")
        # Also validate full matrix coverage, actual manifests and gesture/publication identity.
        from summarize_baseline_input import validate as validate_input
        validate_input(directory)
        events = read(directory / "events.csv")
        expected = {(e["trial"], e["render"]) for e in events if e["stage"] == "render_submit"}
        for e in events:
            if not e["stage"].startswith("diagnostic_"):
                continue
            key = e["stage"].removeprefix("diagnostic_")
            group = groups[e["trial"], e["render"]]
            require(key not in group, "Duplicate diagnostic event")
            is_metric = key.endswith("_ns") or key.startswith("worker_tasks_") or key in ("planned_tasks", "candidate_tiles", "generation")
            group[key] = int(e["duration_nanos"] if is_metric else e["nanos"])
        require(set(groups) == expected, "Missing or unexpected submitted render diagnostics")
    else:
        require(env.get("scheduling") == "9.1-scheduling-v1", "Unknown scheduling matrix")
        manifest = read(directory / "manifest.csv")
        expected = set()
        for row in manifest:
            for run in range(-int(env["warmups"]) - 1, int(env["runs"])):
                phase = "first_sequence" if run == -int(env["warmups"]) - 1 else "warmup" if run < 0 else "sample"
                expected.add(tuple(row[k] for k in ("fixture", "step", "width", "height")) + (phase, str(run)))
        normal = [r for r in rows if r["trigger"] != "planning"]
        actual = [tuple(r[k] for k in ("fixture", "step", "width", "height", "phase", "run")) for r in normal]
        require(len(set(actual)) == len(actual) and set(actual) == expected, "Matrix coverage mismatch")
        planning = [r for r in rows if r["trigger"] == "planning"]
        planning_keys = [tuple(r[k] for k in ("fixture", "step", "width", "height", "phase", "run")) for r in planning]
        expected_planning = {tuple(r[k] for k in ("fixture", "step", "width", "height", "phase", "run")) for r in normal
                             if int(r["reused_pixels"]) == int(r["width"]) * int(r["height"])} if env.get("cancel_planning") == "true" else set()
        require(len(set(planning_keys)) == len(planning_keys) and set(planning_keys) == expected_planning, "Planning cancellation coverage mismatch")
        for e in read(directory / "diagnostics.csv"):
            require(e["trial"] in trials, "Unknown diagnostic trial")
            group = groups[e["trial"], trials[e["trial"]]["generation"]]
            require(e["key"] not in group, "Duplicate diagnostic event")
            group[e["key"]] = int(e["value"])
        require({key[0] for key in groups} == set(trials), "Missing request diagnostics")
        for (trial, _), v in groups.items():
            r = trials[trial]
            require(r["trigger"] in ("none", "first_region", "planning"), "Unknown trigger")
            require((r["trigger"] != "none") == ("cancel_requested" in v), "Cancellation identity mismatch")
            if r["trigger"] == "none":
                require(r["complete"] == "true", "Incomplete successful request")
            if r["trigger"] == "planning":
                require(v["planning_start"] <= v["cancel_requested"] <= v["planning_end"], "Missed planning trigger")
    require(groups, "No diagnostic requests")
    for v in groups.values():
        validate_request(v)
    return trials, groups


def summarize(directory):
    trials, groups = validate(directory)
    intervals = {"coordinator_queue_ms": ("request_submitted", "coordinator_start"),
                 "planning_ms": ("planning_start", "planning_end"), "backend_ms": ("backend_start", "backend_exit"),
                 "reference_ms": ("reference_start", "reference_end"), "coordinates_ms": ("coordinates_start", "coordinates_end"),
                 "bla_ms": ("bla_start", "bla_end"), "request_ms": ("request_submitted", "request_drained"),
                 "cancel_to_backend_exit_ms": ("cancel_requested", "backend_exit"),
                 "cancel_to_last_worker_exit_ms": ("cancel_requested", "last_worker_exit"),
                 "cancel_to_drain_ms": ("cancel_requested", "request_drained")}
    with (directory / "scheduling-summary.csv").open("w", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(["trial", "render", "fixture", "step", "phase", "run", *intervals,
                         "mask_scan_ms", "worker_queue_max_ms", "worker_run_sum_ms", "worker_tasks_started", "worker_tasks_cancelled_before_start"])
        for (trial, render), v in groups.items():
            row = trials[trial]
            duration = lambda a, b: f"{(v[b] - v[a]) / 1e6:.6f}" if a in v and b in v and v[b] >= v[a] else ""
            writer.writerow([trial, render, *(row[k] for k in ("fixture", "step", "phase", "run")),
                             *(duration(a, b) for a, b in intervals.values()),
                             *(f"{v.get(k, 0) / 1e6:.6f}" for k in ("mask_scan_ns", "worker_queue_max_ns", "worker_run_sum_ns")),
                             v.get("worker_tasks_started", 0), v.get("worker_tasks_cancelled_before_start", 0)])
    print(f"Validated {len(trials)} trials, {len(groups)} drained requests: {directory}")


if __name__ == "__main__":
    summarize(Path(sys.argv[1]))
