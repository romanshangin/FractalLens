#!/usr/bin/env python3
"""Strict pair accounting and descriptive/decision statistics for frozen builds."""
import argparse
from collections import defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import random
import re
import statistics

from summarize_baseline import SCOPES as HEADLESS_SCOPES
from summarize_baseline_fx import SCOPES as FX_SCOPES, summarize as validate_fx

KEY = ("fixture", "step", "width", "height")
FORBIDDEN = re.compile(r"StartFlightRecording|FlightRecorderOptions|javaagent|agentpath|agentlib|fractal\.(?:aa\.profile|render\.diagnostics)=true|NativeMemoryTracking=(?:summary|detail)")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def validate_policy(p):
    require(p["version"] == "9.1-pairs-v1", "Unknown paired policy")
    require(p["purpose"] in ("calibration", "candidate"), "Unknown purpose")
    require(p["runner"] in ("headless", "fx"), "Unknown runner")
    require(type(p["process_pairs"]) is int and p["process_pairs"] >= 3, "At least three fresh process pairs required")
    require(type(p["samples"]) is int and p["samples"] >= 1 and p["process_pairs"] * p["samples"] >= 30, "At least 30 measured pairs required")
    require(type(p["warmups"]) is int and p["warmups"] >= 1, "Explicit positive warmup count required")
    require(type(p["timeout_seconds"]) is int and p["timeout_seconds"] > 0, "Invalid process timeout")
    for key in ("sizes", "fixtures"):
        values = p[key].split(",")
        require(all(values) and len(set(values)) == len(values), "Empty or duplicate " + key)
    for size in p["sizes"].split(","):
        dims = size.split("x")
        require(len(dims) == 2 and all(d.isdigit() and 65 <= int(d) <= 4096 for d in dims), "Invalid size")
    if p["runner"] == "fx":
        modes = p["modes"].split(",")
        require(set(modes) <= {"FAST", "REFINED"} and len(modes) == len(set(modes)) and modes, "Invalid FX modes")
    require(p["metrics"], "Declare target and control metrics before timing")
    identities = set()
    for m in p["metrics"]:
        identity = (m["fixture"], m["step"], m["metric"])
        require(identity not in identities, "Duplicate metric")
        identities.add(identity)
        require(m["fixture"] in p["fixtures"].split(","), "Metric fixture not selected")
        require(m["metric"] in (HEADLESS_SCOPES if p["runner"] == "headless" else FX_SCOPES), "Unknown timer scope")
        require(m["role"] in ("target", "control"), "Unknown metric role")
        limit = m["max_ratio"]
        require(isinstance(limit, (int, float)) and math.isfinite(limit) and 0 < limit <= (0.90 if m["role"] == "target" else 1.05), "Invalid or relaxed performance threshold")
    require({m["role"] for m in p["metrics"]} == {"target", "control"}, "Declare both target and control metrics")


def validate_definition(policy, manifest):
    keys = [tuple(r[k] for k in KEY) for r in manifest]
    require(keys and len(set(keys)) == len(keys), "Empty or duplicate fixture manifest")
    require({r["matrix"] for r in manifest} == {"9.1-v1"}, "Unknown fixture matrix")
    require({r["fixture"] for r in manifest} == set(policy["fixtures"].split(",")), "Wrong fixture selection")
    for metric in policy["metrics"]:
        require(any(r["fixture"] == metric["fixture"] and r["step"] == metric["step"] for r in manifest), "Metric step absent from manifest")


def validate_run(directory, policy):
    env = dict(line.split("=", 1) for line in (directory / "environment.txt").read_text().splitlines())
    require(env["aa_profile"] == "false" and not FORBIDDEN.search(env["vm_arguments"]), "Instrumented timing run")
    require(int(env["samples"]) == policy["samples"] and int(env["warmups"]) == policy["warmups"], "Changed repetition counts")
    manifest = read(directory / "manifest.csv")
    validate_definition(policy, manifest)
    rows = read(directory / "samples.csv")
    definitions = {tuple(r[k] for k in KEY): r for r in manifest}
    modes = policy["modes"].split(",") if policy["runner"] == "fx" else ["none"]
    phases = {("cold_fixture", -policy["warmups"] - 1)} | {("warmup", r) for r in range(-policy["warmups"], 0)} | {("sample", r) for r in range(policy["samples"])}
    expected = {(*key, mode, phase, run) for key in definitions for mode in modes for phase, run in phases}
    actual = set()
    hashes = defaultdict(set)
    for row in rows:
        key = tuple(row[k] for k in KEY)
        identity = (*key, row.get("requested_mode", "none"), row["phase"], int(row["run"]))
        require(identity in expected and identity not in actual, "Unexpected or duplicate row identity")
        actual.add(identity)
        definition = definitions[key]
        require(row["cap"] == definition["actual_cap"], "Iteration cap differs from manifest")
        for name, value in row.items():
            if name.endswith("_ms"):
                v = float(value)
                require(math.isfinite(v) and (v >= 0 or v == -1), "Invalid timing")
        if definition["cancel_trigger"] == "none":
            require(row["complete"] == "true", "Incomplete non-cancelled frame")
            hashes[(*key, row.get("requested_mode", "none"))].add((row["sample_hash"], row["argb_hash"]))
        else:
            require(float(row["cancel_request_ms"]) >= 0 and float(row["cancel_tail_ms"]) >= 0, "Missing cancellation trigger/tail")
    require(actual == expected, "Missing cold/warmup/sample rows")
    require(all(len(h) == 1 for h in hashes.values()), "Unstable non-cancelled fingerprints")
    if policy["runner"] == "fx":
        require(env["modes"] == policy["modes"], "Changed FX modes")
        validate_fx(directory)
    return env, manifest, rows


def validate_campaign(directory, require_marker=True):
    if require_marker:
        require((directory / "CAMPAIGN_COMPLETE").is_file(), "Missing campaign completion")
    policy = json.loads((directory / "policy.json").read_text())
    validate_policy(policy)
    builds = {label: json.loads((directory / ("build-" + label + ".json")).read_text()) for label in "AB"}
    identical = builds["A"]["identity"] == builds["B"]["identity"]
    require(identical == (policy["purpose"] == "calibration"), "Build identity conflicts with purpose")
    launches = json.loads((directory / "launches.json").read_text())
    expected = [(pair, "AB" if pair % 2 == 0 else "BA", label)
                for pair in range(policy["process_pairs"]) for label in ("AB" if pair % 2 == 0 else "BA")]
    require([(r["process_pair"], r["order"], r["label"]) for r in launches] == expected, "Missing or reordered JVM launches")
    reference_manifest, reference_env = None, None
    runs = {}
    for launch in launches:
        require(launch["policy_sha256"] == hashlib.sha256((directory / "policy.json").read_bytes()).hexdigest(), "Policy changed after declaration")
        require(launch["directory"] == f'pair-{launch["process_pair"]:02d}-{launch["label"]}', "Unexpected run directory")
        require(launch["result"]["returncode"] == 0, "Unsuccessful JVM")
        require(not FORBIDDEN.search(" ".join(launch["command"])), "Profiled launch command")
        env, manifest, rows = validate_run(directory / launch["directory"], policy)
        require(env["revision"] == builds[launch["label"]]["revision"] and env["label"] == launch["label"], "Wrong build label/revision")
        invariant = tuple(env[k] for k in ("matrix", "java", "vm", "os", "arch", "processors", "workers_per_pool", "max_heap_bytes"))
        if policy["runner"] == "fx":
            invariant += tuple(env[k] for k in ("javafx", "output_scale"))
        if reference_manifest is None:
            reference_manifest, reference_env = manifest, invariant
        require(manifest == reference_manifest, "A/B fixture or numeric contract mismatch")
        require(invariant == reference_env, "Runtime/hardware/heap mismatch")
        runs[launch["process_pair"], launch["label"]] = {(*tuple(r[k] for k in KEY), r.get("requested_mode", "none"), int(r["run"])): r
                                                       for r in rows if r["phase"] == "sample"}
    definitions = {tuple(r[k] for k in KEY): r for r in reference_manifest}
    pairs = []
    for process_pair in range(policy["process_pairs"]):
        a, b = runs[process_pair, "A"], runs[process_pair, "B"]
        require(set(a) == set(b), "Unmatched sample identities")
        for key in sorted(a):
            ar, br = a[key], b[key]
            for field in ("backend", "cap", "reused_pixels", "reuse_source", "effective_mode"):
                require(ar.get(field) == br.get(field), "Changed workload/dispatch: " + field)
            cancelled = definitions[key[:4]]["cancel_trigger"] != "none"
            if not cancelled:
                require((ar["sample_hash"], ar["argb_hash"]) == (br["sample_hash"], br["argb_hash"]), "A/B sample or ARGB fingerprint mismatch")
            for metric in policy["metrics"]:
                if (metric["fixture"], metric["step"]) != key[:2]:
                    continue
                av, bv = float(ar[metric["metric"]]), float(br[metric["metric"]])
                require(av > 0 and bv > 0, "Declared metric missing or zero")
                pairs.append(dict(process_pair=process_pair, order="AB" if process_pair % 2 == 0 else "BA", sample_pair=key[-1],
                                  fixture=key[0], step=key[1], width=key[2], height=key[3], mode=key[4],
                                  metric=metric["metric"], role=metric["role"], max_ratio=metric["max_ratio"], a_ms=av, b_ms=bv, ratio=bv / av,
                                  fingerprint_check="not_applicable_partial_cancel" if cancelled else "matched_not_exact_conformance"))
    require(pairs, "No declared metrics matched")
    return policy, pairs


def paired_statistics(rows, resamples=5000):
    clusters = defaultdict(list)
    for r in rows:
        clusters[int(r["process_pair"])].append(float(r["ratio"]))
    rng = random.Random(910930)
    ids = sorted(clusters)
    medians = []
    for _ in range(resamples):
        values = []
        for key in rng.choices(ids, k=len(ids)):
            values.extend(rng.choices(clusters[key], k=len(clusters[key])))
        medians.append(statistics.median(values))
    medians.sort()
    a, b = sorted(float(r["a_ms"]) for r in rows), sorted(float(r["b_ms"]) for r in rows)
    return dict(pairs=len(rows), process_pairs=len(ids), median_a_ms=statistics.median(a), median_b_ms=statistics.median(b),
                p95_a_ms=a[math.ceil(.95 * len(a)) - 1], p95_b_ms=b[math.ceil(.95 * len(b)) - 1],
                median_paired_ratio=statistics.median(float(r["ratio"]) for r in rows),
                ratio_ci95_low=medians[int(.025 * resamples)], ratio_ci95_high=medians[min(resamples - 1, int(.975 * resamples))],
                process_median_ratios={str(k): statistics.median(v) for k, v in clusters.items()})


def summarize(directory):
    require(not (directory / "pairs.csv").exists() and not (directory / "analysis.json").exists(), "Analysis output already exists")
    policy, pairs = validate_campaign(directory)
    groups = defaultdict(list)
    for row in pairs:
        groups[tuple(row[k] for k in ("fixture", "step", "width", "height", "mode", "metric", "role", "max_ratio"))].append(row)
    summaries = []
    for key, rows in sorted(groups.items()):
        stats = paired_statistics(rows)
        limit = key[-1]
        verdict = "pass" if stats["ratio_ci95_high"] <= limit else "fail" if stats["ratio_ci95_low"] > limit else "inconclusive"
        summaries.append(dict(zip(("fixture", "step", "width", "height", "mode", "metric", "role", "max_ratio"), key),
                              **stats, timing_verdict="calibration_only" if policy["purpose"] == "calibration" else verdict))
    decision = "calibration_only" if policy["purpose"] == "calibration" else "pass" if all(s["timing_verdict"] == "pass" for s in summaries) else "fail" if any(s["timing_verdict"] == "fail" for s in summaries) else "inconclusive"
    result = dict(purpose=policy["purpose"], timing_verdict=decision, production_promotion="not_evaluated",
                  policy_sha256=hashlib.sha256((directory / "policy.json").read_bytes()).hexdigest(),
                  confidence_method="Seeded hierarchical percentile bootstrap: resample process pairs, then matched samples within each selected pair; 5000 resamples. Process-pair counts are recorded per metric.",
                  caveats="Fingerprints are not sample-by-sample conformance. Separate numerical/lifecycle checks and JavaFX/input/thermal evidence remain required for promotion. P95 stability depends on the recorded sample and process-pair counts.", metrics=summaries)
    with (directory / "pairs.csv").open("x", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=pairs[0], lineterminator="\n"); writer.writeheader(); writer.writerows(pairs)
    with (directory / "analysis.json").open("x") as f:
        json.dump(result, f, indent=2); f.write("\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    result = summarize(args.directory)
    print(result["timing_verdict"] + ": " + str(len(result["metrics"])) + " declared metric groups")
