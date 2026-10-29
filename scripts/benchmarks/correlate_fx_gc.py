#!/usr/bin/env python3
"""Correlate diagnostic FX sample intervals with outer JFR stop-the-world GC pauses."""
import argparse
import csv
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path
import re
import subprocess

SAMPLE = "com.shangin.fractal.BaselineFxSample"
PAUSE = "jdk.GCPhasePause"
CPU_FIELDS = {"grid_creation": "gridCpuNanos", "frame_storage_creation": "frameCpuNanos"}
INTERVALS = {
    "planning": ("fxStartNanos", "plannedNanos"),
    "grid_creation": ("gridStartNanos", "gridEndNanos"),
    "frame_storage_creation": ("frameStartNanos", "frameEndNanos"),
    "planned_to_first_backend": ("plannedNanos", "firstBackendRegionNanos"),
    "first_backend_to_first_visible": ("firstBackendRegionNanos", "firstVisibleNanos"),
    "request_to_first_visible": ("requestNanos", "firstVisibleNanos"),
    "request_to_full_post_layout": ("requestNanos", "fullPostLayoutNanos"),
    "snapshot": ("snapshotStartNanos", "snapshotEndNanos"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def instant_ns(value):
    """Keep nanoseconds: datetime alone truncates JFR timestamps to microseconds."""
    match = re.fullmatch(r"(.+T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})", value)
    require(match is not None, "Invalid JFR instant")
    whole, fraction, zone = match.groups()
    date = datetime.fromisoformat(whole + ("+00:00" if zone == "Z" else zone))
    epoch = datetime.fromisoformat("1970-01-01T00:00:00+00:00")
    delta = date - epoch
    return (delta.days * 86400 + delta.seconds) * 10**9 + int((fraction or "").ljust(9, "0"))


def duration_ns(value):
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    require(match is not None and any(match.groups()), "Invalid JFR duration")
    hours, minutes, seconds = match.groups()
    nanos = (Decimal(hours or 0) * 3600 + Decimal(minutes or 0) * 60 + Decimal(seconds or 0)) * 10**9
    require(nanos == int(nanos), "Sub-nanosecond JFR duration")
    return int(nanos)


def correlate(events):
    samples = [event["values"] for event in events if event["type"] == SAMPLE]
    pauses = []
    for event in events:
        if event["type"] == PAUSE:
            values = event["values"]
            start = instant_ns(values["startTime"])
            pauses.append((start, start + duration_ns(values["duration"]), values["gcId"]))
    require(samples, "No FX sample events; enable baseline.fx.jfr and the custom event")
    # Absence can mean disabled/threshold-filtered GC events. Never claim a GC-free run from it.
    require(pauses, "No GCPhasePause events; GC coverage cannot be established")
    pauses.sort()
    require(all(a[1] <= b[0] for a, b in zip(pauses, pauses[1:])), "Overlapping outer GC pauses")
    rows, requests = [], set()
    for sample in samples:
        validate_planning(sample)
        request = sample["request"]
        require(request not in requests, "Duplicate sample request; analyze one JVM recording at a time")
        requests.add(request)
        before, after = sample["anchorBeforeNanos"], sample["anchorAfterNanos"]
        require(after >= before, "Reversed clock anchor")
        anchor = instant_ns(sample["startTime"])
        # JFR anchor maps any monotonic timestamp to [timestamp + low, timestamp + high].
        low, high = anchor - after, anchor - before
        for name, (begin, end) in INTERVALS.items():
            start, stop = sample.get(begin, -1), sample.get(end, -1)
            if start == -1 or stop == -1:
                continue
            if name == "first_backend_to_first_visible" and stop < start:
                continue  # Reused pixels can be published before new backend work.
            require(stop >= start, "Reversed sample interval: " + name)
            # Half-open intervals; bounds account for the same unknown clock offset.
            possible = [(a, b, gc) for a, b, gc in pauses if stop > start and a < stop + high and b > start + low]
            definite = [(a, b, gc) for a, b, gc in possible if a < stop + low and b > start + high]
            lower = sum(max(0, min(stop + low, b) - max(start + high, a)) for a, b, _ in possible)
            upper = min(stop - start, sum(max(0, min(stop + high, b) - max(start + low, a)) for a, b, _ in possible))
            rows.append(dict(fixture=sample["fixture"], step=sample["step"], width=sample["width"], height=sample["height"],
                             requested_mode=sample["requestedMode"], effective_mode=sample["effectiveMode"],
                             phase=sample["phase"], run=sample["run"], request=request, interval=name,
                             duration_ns=stop - start,
                             thread_cpu_ns=sample.get(CPU_FIELDS.get(name), -1),
                             clock_uncertainty_ns=after - before,
                             definite_pause_count=len(definite), possible_pause_count=len(possible),
                             gc_overlap_lower_ns=lower, gc_overlap_upper_ns=upper,
                             possible_gc_ids=";".join(str(gc) for _, _, gc in possible)))
    require(rows, "No measured intervals")
    return rows


def validate_planning(sample):
    version = sample.get("planningVersion", 0)
    require(version in (0, 1), "Unknown planning diagnostic version")
    if version == 0:
        return  # Earlier recordings predate the optional fresh-frame split.
    keys = ("gridStartNanos", "gridEndNanos", "frameStartNanos", "frameEndNanos")
    points = [sample[key] for key in keys]
    cpu = [sample["gridCpuNanos"], sample["frameCpuNanos"]]
    require(all(value >= -1 for value in cpu), "Invalid thread CPU duration")
    if all(point == -1 for point in points):
        require(cpu == [-1, -1], "CPU timing without frame boundaries")
        return  # Reuse planner calls are not split.
    require(all(point != -1 for point in points), "Incomplete planning boundaries")
    timeline = [sample["fxStartNanos"], *points, sample["plannedNanos"]]
    require(timeline == sorted(timeline), "Planning boundaries outside parent interval or reversed")


def validate_samples(events, csv_rows):
    """Reject truncated recordings and wrong CSV joins before interpreting absence of pauses."""
    samples = [event["values"] for event in events if event["type"] == SAMPLE]
    recorded = {str(sample["request"]): sample for sample in samples}
    expected = {row["request"]: row for row in csv_rows}
    require(len(recorded) == len(samples) and len(expected) == len(csv_rows), "Duplicate request identity")
    require(recorded.keys() == expected.keys(), "Recording/CSV request coverage differs")
    identity = dict(fixture="fixture", step="step", width="width", height="height", requestedMode="requested_mode",
                    effectiveMode="effective_mode", phase="phase", run="run")
    metrics = dict(plan_ms=("fxStartNanos", "plannedNanos"),
                   first_backend_region_ms=("requestNanos", "firstBackendRegionNanos"),
                   first_visible_publish_ms=("requestNanos", "firstVisibleNanos"),
                   full_post_layout_ms=("requestNanos", "fullPostLayoutNanos"))
    for request, sample in recorded.items():
        row = expected[request]
        require(all(str(sample[key]) == row[column] for key, column in identity.items()), "Recording/CSV sample identity differs")
        for metric, (begin, end) in metrics.items():
            actual = Decimal(-1) if sample[begin] == -1 or sample[end] == -1 else Decimal(sample[end] - sample[begin]) / 10**6
            require(abs(actual - Decimal(row[metric])) <= Decimal("0.000001"), "Recording/CSV timing differs: " + metric)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path)
    parser.add_argument("output", type=Path, help="New private CSV path")
    parser.add_argument("--jfr", type=Path, required=True, help="jfr executable of the measured JDK")
    parser.add_argument("--samples", type=Path, required=True, help="samples.csv from the same successful benchmark JVM")
    args = parser.parse_args()
    require(not args.output.exists(), "Output already exists")
    printed = subprocess.run([str(args.jfr), "print", "--json", "--events", SAMPLE + "," + PAUSE,
                              str(args.recording)], text=True, capture_output=True, check=True)
    events = json.loads(printed.stdout)["recording"]["events"]
    require((args.samples.parent / "SUCCESS").is_file(), "Missing benchmark conformance marker")
    with args.samples.open(newline="") as stream:
        validate_samples(events, list(csv.DictReader(stream)))
    rows = correlate(events)
    with args.output.open("x", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0], lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
