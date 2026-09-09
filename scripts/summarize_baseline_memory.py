#!/usr/bin/env python3
"""Validate diagnostic soak coverage and summarize scope, memory and sampled allocations."""
import argparse
from bisect import bisect_right
from collections import defaultdict
from datetime import datetime
import json
import hashlib
import math
from pathlib import Path
import re
import statistics
import subprocess
import tempfile

from summarize_baseline_input import read_csv, require, validate_trials
from summarize_baseline_scheduling import validate_request


def timestamp(value):
    # Python 3.9 accepts microseconds; JFR writes nanosecond fractional seconds.
    value = re.sub(r"(\.\d{6})\d+", r"\1", value.replace("Z", "+00:00"))
    return datetime.fromisoformat(value).timestamp()


def validate(directory):
    require((directory / "MONITOR_SUCCESS").is_file(), "Missing monitor success")
    work = directory / "workload"
    require((work / "SUCCESS").is_file(), "Missing conformance success")
    env = dict(line.split("=", 1) for line in (work / "environment.txt").read_text().splitlines() if "=" in line)
    require(env["memory_matrix"] == "9.1-memory-v1", "Unknown memory matrix")
    success = dict(line.split("=", 1) for line in (work / "SUCCESS").read_text().splitlines() if "=" in line)
    rounds, cycles = int(success["rounds"]), int(env["cycles"])
    require(rounds >= 2 and cycles >= 1, "Insufficient rounds/cycles")
    rows = read_csv(work / "samples.csv")
    require(len(rows) == int(success["trials"]) > 0, "Trial count mismatch")
    require([int(r["trial"]) for r in rows] == list(range(1, len(rows) + 1)), "Missing or duplicate trial")
    manifest = read_csv(work / "manifest.csv")
    # Split the flattened manifest at each fixture's first step (also handles multiple sizes).
    cases = []
    first_names = {}
    for r in manifest:
        first_names.setdefault(r["fixture"], r["step"])
        if r["step"] == first_names[r["fixture"]]:
            cases.append([])
        require(cases, "Malformed manifest")
        cases[-1].append(r)
    expected = []
    expected_scopes = []
    modes = env["modes"].split(",")
    for round_id in range(rounds):
        for case in cases:
            first = case[0]
            for mode in modes:
                expected_scopes.append(("seed_and_control", str(round_id), first["fixture"], mode, "-1", "0"))
                for cycle in range(cycles):
                    gesture = "sequence" if len(case) > 1 else "replacement" if first["fixture"].startswith("cancel-") else "pinch"
                    for step in (case[1:] if len(case) > 1 else case):
                        expected.append((first["fixture"], step["step"], first["width"], first["height"], mode, gesture, str(round_id * cycles + cycle)))
                        expected_scopes.extend((scope, str(round_id), first["fixture"], mode, str(cycle), str(len(expected))) for scope in ("navigation", "control"))
                    expected.append((first["fixture"], first["step"], first["width"], first["height"], mode, "sequence", str(round_id * cycles + cycle)))
                    expected_scopes.extend((scope, str(round_id), first["fixture"], mode, str(cycle), str(len(expected))) for scope in ("navigation", "control"))
        expected_scopes.append(("detached_gc_checkpoint", str(round_id), "all", modes[0], "-1", "0"))
    keys = ("fixture", "step", "seed_width", "seed_height", "requested_mode", "gesture", "run")
    require([tuple(r[k] for k in keys) for r in rows] == expected, "Incomplete or reordered round coverage")
    actual = read_csv(work / "actual-manifest.csv")
    # Repeated navigation retains history, so fingerprints need not repeat across cycles.
    events = read_csv(work / "events.csv")
    validate_trials(rows, actual, events, repeatable=False)
    require(env.get("render_diagnostics") == "true", "Missing worker drain diagnostics")
    requests = {(e["trial"], e["render"]) for e in events if e["stage"] == "render_submit"}
    diagnostics = defaultdict(dict)
    for event in events:
        if not event["stage"].startswith("diagnostic_"):
            continue
        key = event["stage"].removeprefix("diagnostic_")
        group = diagnostics[event["trial"], event["render"]]
        require(key not in group, "Duplicate diagnostic event")
        metric = key.endswith("_ns") or key.startswith("worker_tasks_") or key in ("planned_tasks", "candidate_tiles", "generation")
        group[key] = int(event["duration_nanos"] if metric else event["nanos"])
    require(set(diagnostics) == requests, "Missing submitted request diagnostics")
    for group in diagnostics.values():
        validate_request(group)
    scopes = read_csv(work / "scopes.csv")
    previous_end = -math.inf
    navigation, controls, checkpoints, seeds = [], [], [], []
    for s in scopes:
        start, end = timestamp(s["start"]), timestamp(s["end"])
        require(start >= previous_end and end >= start, "Overlapping or reversed scope clocks")
        previous_end = end
        for key in ("elapsed_ms", "heap_before", "heap_after", "nonheap_after", "gc_count_delta", "gc_ms_delta"):
            value = float(s[key])
            require(math.isfinite(value) and value >= 0, "Invalid scope metric: " + key)
        require(abs((end - start) * 1000 - float(s["elapsed_ms"])) < 10, "Wall/monotonic clock disagreement")
        scope = s["scope"]
        require(scope in ("navigation", "control", "seed_and_control", "detached_gc_checkpoint"), "Unknown scope")
        {"navigation": navigation, "control": controls, "seed_and_control": seeds, "detached_gc_checkpoint": checkpoints}[scope].append(s)
    require([s["trial"] for s in navigation] == [r["trial"] for r in rows], "Missing navigation scope")
    require([s["trial"] for s in controls] == [r["trial"] for r in rows], "Missing control scope")
    for n, c, r in zip(navigation, controls, rows):
        require(all(n[k] == c[k] for k in ("round", "fixture", "mode", "cycle", "trial")), "Control identity mismatch")
        require(n["fixture"] == r["fixture"] and n["mode"] == r["requested_mode"], "Navigation identity mismatch")
        require(int(n["round"]) * cycles + int(n["cycle"]) == int(r["run"]), "Scope round mismatch")
        require(timestamp(n["end"]) <= timestamp(c["start"]), "Control precedes navigation")
    require([int(s["round"]) for s in checkpoints] == list(range(rounds)), "Missing detached checkpoint")
    require(len(seeds) == rounds * len(cases) * len(modes), "Missing seed scope")
    require([tuple(s[k] for k in ("scope", "round", "fixture", "mode", "cycle", "trial")) for s in scopes] == expected_scopes,
            "Incomplete or reordered scope identity")
    require(timestamp(scopes[-1]["end"]) - timestamp(scopes[0]["start"]) >= int(env["seconds"]), "Run shorter than requested")
    process = read_csv(directory / "process.csv")
    require(len(process) >= 2, "Missing process observations")
    require(all(int(r["rss_bytes"]) > 0 for r in process), "RSS unavailable")
    require(all(float(b["elapsed_s"]) > float(a["elapsed_s"]) for a, b in zip(process, process[1:])), "Process clock reversed")
    for name, text in (("nmt-baseline.json", "Baseline taken"), ("nmt-final.json", "Total: reserved=")):
        result = json.loads((directory / name).read_text())
        require(result["returncode"] == 0 and text in result["stdout"], "Missing native memory evidence")
    return rows, scopes, process


def summarize(directory):
    rows, scopes, process = validate(directory)
    checkpoints = [s for s in scopes if s["scope"] == "detached_gc_checkpoint"]
    groups = defaultdict(list)
    for s in scopes:
        groups[s["scope"]].append(s)
    return {
        "trials": len(rows), "rounds": len(checkpoints),
        "elapsed_seconds": timestamp(scopes[-1]["end"]) - timestamp(scopes[0]["start"]),
        "scope_totals_ms": {k: sum(float(s["elapsed_ms"]) for s in v) for k, v in groups.items()},
        "scope_gc_ms": {k: sum(int(s["gc_ms_delta"]) for s in v) for k, v in groups.items()},
        "detached_heap_bytes": [int(s["heap_after"]) for s in checkpoints],
        "detached_nonheap_bytes": [int(s["nonheap_after"]) for s in checkpoints],
        "rss_min_bytes": min(int(r["rss_bytes"]) for r in process),
        "rss_max_bytes": max(int(r["rss_bytes"]) for r in process),
        "rss_final_bytes": int(process[-1]["rss_bytes"]),
        "thermal_states": sorted(set(r["thermal_state"] for r in process)),
        "input_complete_by_round": input_rounds(directory, rows, scopes),
        "limits": "Diagnostic workload including exact controls and forced detached GC; RSS is not live heap; NMT excludes some external native allocations; thermal state is OS pressure, not temperature."
    }


def input_rounds(directory, rows, scopes):
    targets = {r["trial"]: r for r in read_csv(directory / "workload/actual-manifest.csv") if r["role"] == "target"}
    rounds = {s["trial"]: int(s["round"]) for s in scopes if s["scope"] == "navigation"}
    groups = defaultdict(lambda: defaultdict(list))
    for r in rows:
        target = targets[r["trial"]]
        key = tuple(r[k] for k in ("fixture", "step", "seed_width", "seed_height", "gesture", "requested_mode"))
        key += tuple(target[k] for k in ("center_real", "center_imaginary", "scale", "width", "height", "actual_cap", "aa"))
        value = float(r["last_input_to_complete_ms"])
        if value >= 0:
            groups[key][rounds[r["trial"]]].append(value)
    return [{"case_and_exact_target": key,
             "rounds": [{"round": n, "count": len(v), "median_ms": statistics.median(v), "max_ms": max(v)} for n, v in sorted(values.items())]}
            for key, values in sorted(groups.items())]


def allocation_report(directory, jfr):
    _, scopes, _ = validate(directory)
    # JSON export is temporary: raw JFR is authoritative and kept outside version control.
    with tempfile.TemporaryFile(mode="w+") as stream:
        subprocess.run([str(jfr), "print", "--json", "--stack-depth", "64", "--events", "jdk.ObjectAllocationSample",
                        str(directory / "allocation.jfr")], stdout=stream, check=True)
        stream.seek(0)
        data = json.load(stream)
    report = allocation_groups(data["recording"]["events"], scopes)
    require(report, "No allocation samples")
    return {"jfr_sha256": hashlib.sha256((directory / "allocation.jfr").read_bytes()).hexdigest(),
            "method": "JFR ObjectAllocationSample weight estimates; sampled stacks and wall-clock scopes; not exact allocation counts or retained size. Boundary weights may include earlier allocations on the same thread.", "groups": report}


def allocation_groups(events, scopes):
    starts = [timestamp(s["start"]) for s in scopes]
    groups = defaultdict(lambda: [0, 0])
    for event in events:
        v = event["values"]
        t = timestamp(v["startTime"])
        index = bisect_right(starts, t) - 1
        matched = scopes[index] if index >= 0 and t < timestamp(scopes[index]["end"]) else None
        scope = matched["scope"] if matched else "outside_scopes"
        frames = (v.get("stackTrace") or {}).get("frames", [])
        stack = []
        for frame in frames:
            method = frame["method"]
            stack.append(method["type"]["name"].replace("/", ".") + "." + method["name"])
        app = next((f for f in stack if f.startswith("com.shangin.fractal.")), "no_application_frame")
        key = (scope, matched["fixture"] if matched else "none", matched["mode"] if matched else "none", v["objectClass"]["name"], app)
        groups[key][0] += 1
        groups[key][1] += int(v["weight"])
    return [{"scope": k[0], "fixture": k[1], "requested_mode": k[2], "class": k[3], "application_frame": k[4], "events": v[0], "estimated_bytes": v[1]}
            for k, v in sorted(groups.items(), key=lambda item: -item[1][1])]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--jfr", type=Path)
    args = parser.parse_args()
    summary = summarize(args.directory)
    with (args.directory / "summary.json").open("x") as f:
        json.dump(summary, f, indent=2); f.write("\n")
    if args.jfr:
        allocations = allocation_report(args.directory, args.jfr)
        with (args.directory / "allocations.json").open("x") as f:
            json.dump(allocations, f, indent=2); f.write("\n")
