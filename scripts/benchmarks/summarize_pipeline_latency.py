#!/usr/bin/env python3
"""Validate a Prism pipeline latency campaign; pair screen-observed trials and summarize JFR pulse phases."""
import argparse
from collections import defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import subprocess

from summarize_baseline_pairs import CONFIGURATION_OPTION, FORBIDDEN, PIPELINE, paired_statistics, require
from summarize_interaction_latency import trial_metrics

GESTURES = {"wheel", "wheel-burst", "pinch", "trackpad", "drag"}
MODES = ("REFINED", "FAST", "BASE_ONLY")
METRICS = {"preview_publish_screen_observed_from_first_input_ms", "complete_screen_observed_from_last_input_ms",
           "details_publish_to_screen_ms", "complete_publish_to_screen_ms", "complete_from_last_input_ms"}
HEADER = re.compile(r"# Java=.*; requestedPipeline=(\S+); screenProbe=(true|false);")
SCALE = re.compile(r"# (logical=\S+; outputScale=\S+)$")
DURATION = re.compile(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?")


def validate_policy(p):
    require(p["version"] == "pipeline-latency-v1", "Unknown pipeline latency policy")
    require(type(p["process_pairs"]) is int and p["process_pairs"] >= 3, "At least three fresh process pairs required")
    require(type(p["samples"]) is int and p["samples"] >= 1 and p["process_pairs"] * p["samples"] >= 30, "At least 30 measured pairs required")
    require(type(p["warmups"]) is int and p["warmups"] >= 1, "Explicit positive warmup count required")
    require(type(p["timeout_seconds"]) is int and p["timeout_seconds"] > 0, "Invalid process timeout")
    require(type(p["jfr_diagnostic_runs"]) is int and p["jfr_diagnostic_runs"] >= 0, "Invalid diagnostic run count")
    gestures = p["gestures"].split(",")
    require(gestures and set(gestures) <= GESTURES and len(set(gestures)) == len(gestures), "Invalid gestures")
    options = p["jvm_options"]
    require(set(options) == {"A", "B"} and options["A"] != options["B"], "Declare different A/B JVM options")
    require(all(len(o) == 1 and CONFIGURATION_OPTION.fullmatch(o[0]) for o in options.values()), "Unsupported configuration option")
    require(p["expected_pipeline"] == {label: CONFIGURATION_OPTION.fullmatch(options[label][0]).group(1) for label in "AB"},
            "Expected pipelines must match the requested Prism order")
    names = [m["metric"] for m in p["metrics"]]
    require(names and len(set(names)) == len(names) and set(names) <= METRICS, "Unknown or duplicate metric")
    for m in p["metrics"]:
        limit = m["max_ratio"]
        require(isinstance(limit, (int, float)) and math.isfinite(limit) and 0 < limit <= 1.05, "Invalid or relaxed performance threshold")


def validate_launch(directory, policy, launch, screen):
    require(launch["policy_sha256"] == hashlib.sha256((directory / "policy.json").read_bytes()).hexdigest(), "Policy changed after declaration")
    require(launch["result"]["returncode"] == 0, "Unsuccessful JVM")
    declared = policy["jvm_options"][launch["label"]]
    require([o for o in launch["command"] if CONFIGURATION_OPTION.fullmatch(o)] == declared, "Launch does not use its declared JVM options")
    pipelines = PIPELINE.findall((directory / (launch["directory"] + ".log")).read_text())
    require(pipelines == [policy["expected_pipeline"][launch["label"]]], "Actual Prism pipeline differs from the declared one")
    lines = (directory / (launch["directory"] + ".csv")).read_text().splitlines()
    header = next((HEADER.match(line) for line in lines if HEADER.match(line)), None)
    require(header is not None and header.group(1) == CONFIGURATION_OPTION.fullmatch(declared[0]).group(1)
            and header.group(2) == str(screen).lower(), "Recording does not match its declared pipeline or screen probe")
    scale = next((SCALE.match(line).group(1) for line in lines if SCALE.match(line)), None)
    require(scale is not None, "Missing window size and output scale")
    trials = trial_metrics(directory / (launch["directory"] + ".csv"))
    expected = {(mode, gesture, str(i)) for mode in MODES for gesture in policy["gestures"].split(",") for i in range(policy["samples"])}
    require(set(trials) == expected, "Missing or unexpected measured trials")
    return scale, trials


def validate_campaign(directory, require_marker=True):
    if require_marker:
        require((directory / "CAMPAIGN_COMPLETE").is_file(), "Missing campaign completion")
    policy = json.loads((directory / "policy.json").read_text())
    validate_policy(policy)
    launches = json.loads((directory / "launches.json").read_text())
    expected = [(pair, "AB" if pair % 2 == 0 else "BA", label)
                for pair in range(policy["process_pairs"]) for label in ("AB" if pair % 2 == 0 else "BA")]
    require([(r["process_pair"], r["order"], r["label"]) for r in launches] == expected, "Missing or reordered JVM launches")
    scales, runs = set(), {}
    for launch in launches:
        require(launch["directory"] == f'pair-{launch["process_pair"]:02d}-{launch["label"]}', "Unexpected run directory")
        require(not FORBIDDEN.search(" ".join(launch["command"])), "Profiled launch command")
        scale, runs[launch["process_pair"], launch["label"]] = validate_launch(directory, policy, launch, screen=True)
        scales.add(scale)
    require(len(scales) == 1, "Window size or output scale changed between runs")
    diagnostics = json.loads((directory / "diagnostics.json").read_text())
    require(len(diagnostics) == 2 * policy["jfr_diagnostic_runs"], "Missing JFR diagnostic runs")
    for launch in diagnostics:
        validate_launch(directory, policy, launch, screen=False)
        require((directory / (launch["directory"] + ".jfr")).is_file(), "Missing JFR recording")
    pairs, missing = [], defaultdict(int)
    for pair in range(policy["process_pairs"]):
        a, b = runs[pair, "A"], runs[pair, "B"]
        for trial in sorted(a):
            for metric in policy["metrics"]:
                name = metric["metric"]
                if name not in a[trial] or name not in b[trial]:
                    missing[trial[:2] + (name,)] += 1
                    continue
                av, bv = a[trial][name], b[trial][name]
                require(math.isfinite(av) and math.isfinite(bv), "Invalid timing")
                if av <= 0 or bv <= 0:
                    missing[trial[:2] + (name,)] += 1
                    continue
                pairs.append(dict(process_pair=pair, order="AB" if pair % 2 == 0 else "BA", mode=trial[0], gesture=trial[1],
                                  sample_pair=trial[2], metric=name, max_ratio=metric["max_ratio"], a_ms=av, b_ms=bv, ratio=bv / av))
    return policy, scales.pop(), diagnostics, pairs, missing


def seconds(duration):
    match = DURATION.fullmatch(duration)
    require(match is not None, "Unknown JFR duration: " + duration)
    hours, minutes, secs = match.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(secs or 0)


def pulse_phases(directory, diagnostics, jfr):
    """Per-phase and per-pulse render-thread durations from JavaFX JFR events."""
    phases, render_pulses = defaultdict(list), defaultdict(list)
    for launch in diagnostics:
        printed = subprocess.run([str(jfr), "print", "--json", "--events", "javafx.PulsePhase",
                                  str(directory / (launch["directory"] + ".jfr"))], text=True, capture_output=True, check=True)
        per_pulse = defaultdict(float)
        events = json.loads(printed.stdout)["recording"]["events"]
        require(events, "No javafx.PulsePhase events in " + launch["directory"] + ".jfr")
        for event in events:
            values = event["values"]
            # Pulse phases run on the renderer or the FX thread; the latter is unnamed on macOS (the AppKit main thread).
            thread = "render" if values["eventThread"]["javaName"].startswith("QuantumRenderer") else "fx"
            ms = seconds(values["duration"]) * 1000
            phases[launch["label"], thread, values["phaseName"]].append(ms)
            if thread == "render":
                per_pulse[values["pulseId"]] += ms
        render_pulses[launch["label"]].extend(per_pulse.values())
    for label, values in render_pulses.items():
        phases[label, "render", "total per pulse"] = values
    rows = []
    for (label, thread, phase), values in sorted(phases.items()):
        values.sort()
        rows.append(dict(label=label, thread=thread, phase=phase, count=len(values), median_ms=f"{statistics.median(values):.4f}",
                         p95_ms=f"{values[math.ceil(.95 * len(values)) - 1]:.4f}", total_ms=f"{sum(values):.1f}"))
    return rows


def write_csv(path, rows):
    with path.open("x", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0], lineterminator="\n"); writer.writeheader(); writer.writerows(rows)


def summarize(directory, jfr=None):
    require(not (directory / "pairs.csv").exists() and not (directory / "analysis.json").exists(), "Analysis output already exists")
    policy, scale, diagnostics, pairs, missing = validate_campaign(directory)
    groups = defaultdict(list)
    for row in pairs:
        groups[row["mode"], row["gesture"], row["metric"], row["max_ratio"]].append(row)
    # Every declared group is reported; a metric absent from every trial must not disappear from the verdict.
    for mode in MODES:
        for gesture in policy["gestures"].split(","):
            for metric in policy["metrics"]:
                groups.setdefault((mode, gesture, metric["metric"], metric["max_ratio"]), [])
    summaries = []
    for (mode, gesture, metric, limit), rows in sorted(groups.items()):
        stats = paired_statistics(rows) if rows else dict(pairs=0, process_pairs=0)
        if stats["pairs"] < 30 or stats["process_pairs"] < 3:
            verdict = "inconclusive"
        else:
            verdict = "pass" if stats["ratio_ci95_high"] <= limit else "fail" if stats["ratio_ci95_low"] > limit else "inconclusive"
        summaries.append(dict(mode=mode, gesture=gesture, metric=metric, max_ratio=limit, missing_pairs=missing[mode, gesture, metric],
                              **stats, timing_verdict=verdict))
    decision = "pass" if all(s["timing_verdict"] == "pass" for s in summaries) else "fail" if any(s["timing_verdict"] == "fail" for s in summaries) else "inconclusive"
    result = dict(purpose="configuration", timing_verdict=decision, production_promotion="not_evaluated",
                  jvm_options=policy["jvm_options"], window=scale,
                  policy_sha256=hashlib.sha256((directory / "policy.json").read_bytes()).hexdigest(),
                  confidence_method="Seeded hierarchical percentile bootstrap over process pairs, then matched trials; 5000 resamples. Ratio is B/A.",
                  caveats="Screen observation is a JavaFX Robot compositor-capture proxy, not physical scanout. Trials with an absent marker are counted as missing, never zero.",
                  metrics=summaries)
    if pairs:
        write_csv(directory / "pairs.csv", pairs)
    if jfr is not None and diagnostics:
        write_csv(directory / "pulse-phases.csv", pulse_phases(directory, diagnostics, jfr))
        result["pulse_phases"] = "pulse-phases.csv: diagnostic JFR runs, excluded from timing verdicts"
    with (directory / "analysis.json").open("x") as f:
        json.dump(result, f, indent=2); f.write("\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--jfr", type=Path, help="jfr tool of the measured JDK; enables the pulse-phase summary")
    args = parser.parse_args()
    result = summarize(args.directory, args.jfr)
    print(result["timing_verdict"] + ": " + str(len(result["metrics"])) + " declared metric groups")
