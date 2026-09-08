#!/usr/bin/env python3
"""Validate and summarize one complete JavaFX fixture run; never infer display scanout."""
import argparse
import csv
import math
import statistics
from collections import defaultdict
from pathlib import Path

KEY = ("fixture", "step", "width", "height")
SCOPES = ("fx_dispatch_ms", "plan_ms", "first_backend_region_ms", "backend_ms", "base_complete_ms",
          "first_base_publish_ms", "base_full_publish_ms", "aa_prepare_ms", "aa_ms", "first_aa_publish_ms",
          "first_visible_publish_ms", "full_publish_ms", "first_post_layout_ms", "full_post_layout_ms",
          "cancel_request_ms", "cancel_tail_ms", "base_fx_work_ms", "aa_fx_work_ms")


def summarize(directory):
    if not (directory / "SUCCESS").is_file():
        raise ValueError("Run has no successful pixel/sample conformance marker")
    environment = dict(line.split("=", 1) for line in (directory / "environment.txt").read_text().splitlines())
    runs, warmups = int(environment["samples"]), int(environment["warmups"])
    modes = environment["modes"].split(",")
    expected = {("cold_fixture", -warmups - 1)} | {("warmup", r) for r in range(-warmups, 0)} | {("sample", r) for r in range(runs)}
    with (directory / "manifest.csv").open(newline="") as stream:
        entries = list(csv.DictReader(stream))
        manifest = {tuple(row[k] for k in KEY): row for row in entries}
    if len(manifest) != len(entries):
        raise ValueError("Duplicate manifest step")
    groups = defaultdict(list)
    requests = set()
    with (directory / "samples.csv").open(newline="") as stream:
        for row in csv.DictReader(stream):
            key = tuple(row[k] for k in KEY)
            if key not in manifest or row["cap"] != manifest[key]["actual_cap"] or row["requested_mode"] not in modes:
                raise ValueError(f"Sample does not match manifest/modes: {key}")
            if row["request"] in requests:
                raise ValueError("Duplicate request identity")
            requests.add(row["request"])
            groups[(*key, row["requested_mode"])].append(row)
    if set(groups) != {(*key, mode) for key in manifest for mode in modes}:
        raise ValueError("Incomplete fixture/mode matrix")
    summary = []
    for key, rows in groups.items():
        identities = [(r["phase"], int(r["run"])) for r in rows]
        if len(set(identities)) != len(identities) or set(identities) != expected:
            raise ValueError(f"Incomplete or duplicate repetitions: {key}")
        if len({(r["backend"], r["effective_mode"]) for r in rows}) != 1:
            raise ValueError(f"Backend/presentation policy changed: {key}")
        cancelled = manifest[key[:4]]["cancel_trigger"] != "none"
        for row in rows:
            values = {scope: float(row[scope]) for scope in SCOPES}
            if any(not math.isfinite(v) or (v < 0 and v != -1) for v in values.values()):
                raise ValueError(f"Invalid duration: {key}")
            if cancelled:
                if values["cancel_request_ms"] < 0 or values["cancel_tail_ms"] < 0 or values["full_publish_ms"] != -1:
                    raise ValueError(f"Invalid cancellation boundaries: {key}")
            elif (row["complete"] != "true" or values["first_visible_publish_ms"] < 0
                  or values["full_publish_ms"] < values["first_visible_publish_ms"]
                  or values["first_post_layout_ms"] < values["first_visible_publish_ms"]
                  or values["full_post_layout_ms"] < values["full_publish_ms"]):
                raise ValueError(f"Missing or reversed publication boundaries: {key}")
        measured = [r for r in rows if r["phase"] == "sample"]
        fingerprints = {(r["sample_hash"], r["argb_hash"]) for r in rows}
        if not cancelled and len(fingerprints) != 1:
            raise ValueError(f"Non-repeatable sample/ARGB fingerprints: {key}")
        for scope in SCOPES:
            values = [float(r[scope]) for r in measured]
            if all(v == -1 for v in values):
                continue
            if any(v < 0 for v in values):
                raise ValueError(f"Partially missing scope: {key}/{scope}")
            summary.append((*key, rows[0]["effective_mode"], rows[0]["backend"], scope, len(values),
                            statistics.median(values), sorted(values)[math.ceil(.95 * len(values)) - 1], max(values)))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rows = summarize(args.directory)
    with args.output.open("x", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow((*KEY, "requested_mode", "effective_mode", "backend", "scope", "n", "median_ms", "p95_nearest_rank_ms", "max_ms"))
        writer.writerows(rows)


if __name__ == "__main__":
    main()
