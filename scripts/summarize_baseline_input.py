#!/usr/bin/env python3
"""Validate and summarize 9.1 production-handler trials; never accepts partial runs."""
import csv
import math
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def read_csv(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(directory):
    require((directory / "SUCCESS").is_file(), "Missing conformance SUCCESS marker")
    env = dict(line.split("=", 1) for line in (directory / "environment.txt").read_text().splitlines() if "=" in line)
    require(env["input_matrix"] == "9.1-input-v1", "Unknown input matrix")
    rows = read_csv(directory / "samples.csv")
    manifest = read_csv(directory / "manifest.csv")
    actual = read_csv(directory / "actual-manifest.csv")
    events = read_csv(directory / "events.csv")
    require(rows, "No trials")
    trials = {r["trial"]: r for r in rows}
    require(len(trials) == len(rows), "Duplicate trial")
    require(set(map(int, trials)) == set(range(1, len(rows) + 1)), "Missing trial identity")
    actual_by_trial = defaultdict(dict)
    for record in actual:
        trial, role = record["trial"], record["role"]
        require(trial in trials and role in ("source", "target"), "Unknown actual manifest identity")
        require(role not in actual_by_trial[trial], "Duplicate actual manifest role")
        actual_by_trial[trial][role] = record
    by_trial = defaultdict(list)
    for event in events:
        require(event["trial"] in trials, "Unknown event trial")
        by_trial[event["trial"]].append(event)
    expected = set()
    seeds = defaultdict(list)
    for record in manifest:
        seeds[record["fixture"]].append(record)
    # A fixture may occur at several dimensions; its first named step identifies each sequence.
    first_names = {name: records[0]["step"] for name, records in seeds.items()}
    for record in manifest:
        name = record["fixture"]
        if record["step"] != first_names[name]:
            continue
        width, height = record["width"], record["height"]
        steps = list(dict.fromkeys(r["step"] for r in seeds[name]))
        gestures = ["sequence"] if len(steps) > 1 else ["replacement"] if name.startswith("cancel-") else env["gestures"].split(",")
        for gesture in gestures:
            for step in steps[1:] if gesture == "sequence" else steps:
                for mode in env["modes"].split(","):
                    for run in range(-int(env["warmups"]) - 1, int(env["samples"])):
                        phase = "first_sequence" if run == -int(env["warmups"]) - 1 else "warmup" if run < 0 else "sample"
                        expected.add((name, step, width, height, gesture, mode, phase, str(run)))
    keys = [tuple(r[k] for k in ("fixture", "step", "seed_width", "seed_height", "gesture", "requested_mode", "phase", "run")) for r in rows]
    require(len(set(keys)) == len(keys), "Duplicate sequence repetition")
    require(set(keys) == expected, "Missing or unexpected fixture/mode/gesture/repetition")
    hashes = defaultdict(set)
    for row in rows:
        trial = row["trial"]
        require(set(actual_by_trial[trial]) == {"source", "target"}, "Missing actual source/target")
        source, target = (actual_by_trial[trial][role] for role in ("source", "target"))
        require(target["fixture"] == row["fixture"] and target["step"] == row["step"], "Manifest fixture mismatch")
        for field, target_field in (("width", "width"), ("height", "height"), ("cap", "actual_cap"), ("aa", "aa")):
            require(row[field] == target[target_field], f"Actual {field} mismatch")
        trial_events = by_trial[trial]
        require(len({e["epoch"] for e in trial_events}) == 1, "Mixed recording epochs")
        inputs = [e for e in trial_events if e["stage"].startswith("input_")]
        require(inputs, "No real handler input recorded")
        last = max(int(e["input"]) for e in inputs)
        require(sorted(int(e["input"]) for e in inputs) == list(range(1, last + 1)), "Missing or duplicate input entry")
        origins = {int(e["input"]): int(e["nanos"]) for e in inputs}
        starts = {e["render"]: e for e in trial_events if e["stage"] == "render_start"}
        require(len(starts) == int(row["render_count"]), "Render count mismatch")
        for event in trial_events:
            if event["render"] != "0":
                require(event["render"] in starts, "Event has no render start")
                require(event["input"] == starts[event["render"]]["input"], "Event changed input generation")
            if int(event["input"]) in origins:
                require(int(event["nanos"]) >= origins[int(event["input"])], "Event precedes its input")
        final = [e for e in trial_events if int(e["input"]) == last and e["stage"] == "complete"]
        pulses = [e for e in trial_events if int(e["input"]) == last and e["stage"] == "complete_post_layout"]
        require(row["status"] in ("complete", "no_change"), "Unknown status")
        if row["status"] == "complete":
            require(len(final) == 1 and len(pulses) == 1, "Last input lacks unique completion and pulse")
            require(final[0]["render"] == pulses[0]["render"], "Completion pulse changed generation")
            require(int(pulses[0]["nanos"]) >= int(final[0]["nanos"]), "Pulse precedes completion")
        else:
            require(not starts and not final and not pulses, "No-change trial contains a render")
            for field in ("center_real", "center_imaginary", "scale", "width", "height", "actual_cap"):
                require(source[field] == target[field], "No-change trial changed its job")
        for field, value in row.items():
            if field.endswith("_ms"):
                number = float(value)
                require(math.isfinite(number) and (number >= 0 or number == -1), "Invalid timing")
        def timestamp(stage, input_id=None):
            values = [int(e["nanos"]) for e in trial_events if e["stage"] == stage
                      and (input_id is None or int(e["input"]) == input_id)]
            return min(values) if values else -1

        def elapsed(end, start):
            return (end - start) / 1e6 if end >= 0 else -1

        first_time = min(origins.values())
        derived = {
            "first_input_to_preview_ms": elapsed(timestamp("preview_publish"), first_time),
            "first_input_to_preview_post_layout_ms": elapsed(timestamp("preview_publish_post_layout"), first_time),
        }
        for field, stage in (("render", "render_start"), ("base", "base_publish"), ("aa", "aa_publish"),
                             ("complete", "complete"), ("post_layout", "complete_post_layout")):
            derived[f"last_input_to_{field}_ms"] = elapsed(timestamp(stage, last), origins[last])
        for suffix in ("", "_post_layout"):
            times = [timestamp(stage + suffix, last) for stage in ("base_publish", "aa_publish", "frame_promoted")]
            end = min((t for t in times if t >= 0), default=-1)
            derived[f"last_input_to_first_target{suffix}_ms"] = elapsed(end, origins[last])
        for field, value in derived.items():
            require(abs(float(row[field]) - value) <= .000001, f"Timing disagrees with raw events: {field}")
        key = tuple(row[k] for k in ("fixture", "step", "seed_width", "seed_height", "gesture", "requested_mode"))
        hashes[key].add((row["sample_hash"], row["argb_hash"], row["status"], target["center_real"], target["center_imaginary"], target["scale"], target["actual_cap"]))
    require(all(len(values) == 1 for values in hashes.values()), "Non-repeatable endpoint or fingerprints")
    return rows


def summarize(directory, output):
    rows = validate(directory)
    groups = defaultdict(list)
    columns = ("fixture", "step", "seed_width", "seed_height", "gesture", "requested_mode", "status")
    for row in rows:
        if row["phase"] == "sample":
            groups[tuple(row[k] for k in columns)].append(row)
    with output.open("x", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow((*columns, "scope", "present", "samples", "median_ms", "p95_ms", "max_ms"))
        for key, samples in sorted(groups.items()):
            for scope in (field for field in rows[0] if field.endswith("_ms")):
                values = sorted(float(row[scope]) for row in samples if float(row[scope]) >= 0)
                stats = (statistics.median(values), values[math.ceil(.95 * len(values)) - 1], max(values)) if values else (-1, -1, -1)
                writer.writerow((*key, scope, len(values), len(samples), *stats))
    return len(rows)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: summarize_baseline_input.py RUN_DIRECTORY NEW_SUMMARY.csv")
    try:
        print(f"Validated {summarize(Path(sys.argv[1]), Path(sys.argv[2]))} trials")
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit(str(error))
