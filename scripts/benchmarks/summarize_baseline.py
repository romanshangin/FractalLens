#!/usr/bin/env python3
"""Summarize one baseline run without merging cold/cache/instrumentation scopes."""
import argparse
import csv
import math
import statistics
from collections import defaultdict
from pathlib import Path

KEY = ("fixture", "step", "width", "height")
SCOPES = ("plan_ms", "first_useful_samples_ms", "first_new_region_ms", "backend_ms",
          "returned_argb_ms", "color_ms", "aa_prepare_ms", "aa_ms", "first_aa_tile_ms",
          "operation_ms", "cancel_request_ms", "cancel_tail_ms")


def summarize(directory):
    environment = dict(line.split("=", 1) for line in (directory / "environment.txt").read_text().splitlines())
    expected_runs = set(range(int(environment["samples"])))
    with (directory / "manifest.csv").open(newline="") as stream:
        entries = list(csv.DictReader(stream))
        manifest = {tuple(row[k] for k in KEY): row for row in entries}
        if len(manifest) != len(entries):
            raise ValueError("Duplicate manifest step")
    groups = defaultdict(list)
    seen = set()
    with (directory / "samples.csv").open(newline="") as stream:
        for row in csv.DictReader(stream):
            key = tuple(row[k] for k in KEY)
            if key not in manifest or row["cap"] != manifest[key]["actual_cap"]:
                raise ValueError(f"Sample does not match manifest: {key}")
            identity = (*key, row["phase"], row["run"])
            if identity in seen:
                raise ValueError(f"Duplicate sample: {identity}")
            seen.add(identity)
            if row["phase"] == "sample":
                groups[key].append(row)
    if set(groups) != set(manifest):
        raise ValueError("Run is incomplete: some manifest steps have no measured samples")
    summary = []
    for key, rows in groups.items():
        if {int(r["run"]) for r in rows} != expected_runs:
            raise ValueError(f"Run is incomplete: measured repetitions do not match environment: {key}")
        if len({r["backend"] for r in rows}) != 1:
            raise ValueError(f"Backend changed within a scope: {key}")
        fingerprints = {(r["sample_hash"], r["argb_hash"]) for r in rows}
        cancelled = manifest[key]["cancel_trigger"] != "none"
        if not cancelled and any(r["complete"] != "true" for r in rows):
            raise ValueError(f"Incomplete non-cancelled frame: {key}")
        for scope in SCOPES:
            values = [float(r[scope]) for r in rows]
            if any(not math.isfinite(v) or v < -1 for v in values):
                raise ValueError(f"Invalid duration: {key}/{scope}")
            if all(v == -1 for v in values):
                continue
            if any(v < 0 for v in values):
                raise ValueError(f"Partially missing scope: {key}/{scope}")
            summary.append((*key, rows[0]["backend"], scope, len(values),
                            statistics.median(values), sorted(values)[math.ceil(.95 * len(values)) - 1],
                            max(values), "not_applicable" if cancelled else str(len(fingerprints) == 1).lower()))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("output", type=Path, help="New CSV; existing files are never overwritten")
    args = parser.parse_args()
    rows = summarize(args.directory)
    with args.output.open("x", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow((*KEY, "backend", "scope", "n", "median_ms", "p95_nearest_rank_ms", "max_ms", "stable_hashes"))
        writer.writerows(rows)


if __name__ == "__main__":
    main()
