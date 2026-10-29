#!/usr/bin/env python3
"""Validate a benchmark-only fresh-frame preparation experiment; never promote application code."""
import argparse
from collections import defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path

from summarize_baseline_fx import KEY, summarize as validate_fx
from summarize_baseline_pairs import FORBIDDEN, PIPELINE, paired_statistics, require

VARIANT_OPTION = "-Dbaseline.fx.preparation="
METRICS = {"fx_setup_ms", "first_visible_publish_ms", "full_publish_ms", "first_post_layout_ms",
           "full_post_layout_ms", "first_aa_tile_ms"}
FIXTURES = {"mandelbrot-overview", "mandelbrot-aa-regular", "pan-25"}
CANONICAL_MANIFEST = Path(__file__).resolve().parents[2] / "benchmarks/BASELINE_FIXTURES.csv"
ENVIRONMENT = ("matrix", "java", "javafx", "os", "arch", "processors", "max_heap_bytes", "output_scale", "modes")


def read_csv(path):
    with path.open(newline="") as stream:
        return list(csv.DictReader(stream))


def validate_policy(policy):
    require(policy["version"] == "p17-fresh-frame-preparation-v1" and policy["purpose"] == "benchmark_experiment", "Unknown preparation policy")
    require(policy["variants"] == {"A": "fx", "B": "worker"} and policy["pipeline"] in ("es2", "mtl", "sw"), "Invalid variants or pipeline")
    pairs, samples = policy["process_pairs"], policy["samples"]
    require(type(pairs) is int and pairs >= 3 and type(samples) is int and samples >= 1 and pairs * samples >= 30, "Insufficient paired budget")
    require(type(policy["warmups"]) is int and policy["warmups"] >= 1, "Explicit warmups required")
    require(type(policy["timeout_seconds"]) is int and policy["timeout_seconds"] > 0, "Invalid timeout")
    require(0 < policy["rss_max_pair_ratio"] <= 1.05, "Invalid or relaxed RSS budget")
    fixtures = policy["fixtures"].split(",")
    require(fixtures and len(set(fixtures)) == len(fixtures) and set(fixtures) <= FIXTURES, "Unsupported or duplicate fixtures")
    sizes = policy["sizes"].split(",")
    require(sizes and len(set(sizes)) == len(sizes), "Missing or duplicate sizes")
    for size in sizes:
        parts = size.split("x")
        require(len(parts) == 2 and all(part.isdecimal() and 65 <= int(part) <= 4096 for part in parts), "Invalid size")
    modes = policy["modes"].split(",")
    require(len(set(modes)) == len(modes) and set(modes) <= {"FAST", "REFINED"}, "Invalid modes")
    require(policy["metrics"], "Missing metric declarations")
    require(any(metric["role"] == "target" for metric in policy["metrics"]), "Missing target")
    identities = set()
    for metric in policy["metrics"]:
        identity = (metric["metric"], metric.get("fixture"), metric.get("action"))
        require(identity not in identities, "Duplicate metric declaration")
        identities.add(identity)
        require(metric["metric"] in METRICS and metric["role"] in ("target", "control"), "Unknown metric or role")
        require(metric.get("action") in (None, "FRESH", "REUSE"), "Unknown action selector")
        limit = metric["max_ratio"]
        require(math.isfinite(limit) and 0 < limit <= (0.90 if metric["role"] == "target" else 1.05), "Invalid or relaxed metric limit")


def validate_manifest(policy, manifest):
    # Step/action schema comes from the canonical fixtures, never from possibly truncated run inputs.
    schema = defaultdict(dict)
    for row in read_csv(CANONICAL_MANIFEST):
        if row["fixture"] in FIXTURES:
            schema[row["fixture"]][row["step"]] = row["action"]
    expected = {(fixture, step, *size.split("x")): action
                for fixture in policy["fixtures"].split(",") for size in policy["sizes"].split(",")
                for step, action in schema[fixture].items()}
    require(all(schema[fixture] for fixture in policy["fixtures"].split(",")), "Missing canonical fixture schema")
    actual = {tuple(row[key] for key in KEY): row["action"] for row in manifest}
    require(len(actual) == len(manifest) and actual == expected, "Incomplete or unexpected fixture/size/step matrix")


def validate_campaign(directory, require_marker=True):
    if require_marker:
        require((directory / "CAMPAIGN_COMPLETE").is_file(), "Missing campaign completion")
    policy = json.loads((directory / "policy.json").read_text())
    validate_policy(policy)
    policy_hash = hashlib.sha256((directory / "policy.json").read_bytes()).hexdigest()
    before = json.loads((directory / "build-before.json").read_text())
    after = json.loads((directory / "build-after.json").read_text())
    require(before and before == after, "Frozen build changed")
    launches = json.loads((directory / "launches.json").read_text())
    expected = [(pair, "AB" if pair % 2 == 0 else "BA", label)
                for pair in range(policy["process_pairs"]) for label in ("AB" if pair % 2 == 0 else "BA")]
    require([(r["process_pair"], r["order"], r["label"]) for r in launches] == expected, "Missing or reordered JVMs")
    environments, manifests, runs, peaks = set(), set(), {}, {}
    for launch in launches:
        pair, label, name = launch["process_pair"], launch["label"], launch["directory"]
        require(name == f"pair-{pair:02d}-{label}", "Unexpected JVM directory")
        require(launch["policy_sha256"] == policy_hash, "Policy changed after declaration")
        require(launch["result"]["returncode"] == 0, "Unsuccessful JVM")
        command = launch["command"]
        require(not FORBIDDEN.search(" ".join(command)), "Profiled timing run")
        require([v for v in command if v.startswith(VARIANT_OPTION)] == [VARIANT_OPTION + policy["variants"][label]], "Wrong preparation variant")
        require([v for v in command if v.startswith("-Dprism.order=")] == ["-Dprism.order=" + policy["pipeline"]], "Wrong requested pipeline")
        require(PIPELINE.findall((directory / (name + ".log")).read_text()) == [policy["pipeline"]], "Wrong actual pipeline")
        for position in ("before", "after"):
            observed = launch[position]
            require(observed["power"]["returncode"] == 0 and "Now drawing from 'AC Power'" in observed["power"]["stdout"], "AC power not confirmed")
            require(observed["thermal"]["returncode"] == 0 and observed["thermal"]["stdout"].strip() == "nominal", "Nominal thermal pressure not confirmed")
        run = directory / name
        validate_fx(run)
        env = dict(line.split("=", 1) for line in (run / "environment.txt").read_text().splitlines())
        require(env["frame_preparation"] == policy["variants"][label], "Environment variant differs")
        require(env["aa_profile"] == "false" and env["interval_jfr"] == "false" and not FORBIDDEN.search(env["vm_arguments"]), "Instrumented environment")
        require(env["requested_pipeline"] == policy["pipeline"] and env["modes"] == policy["modes"], "Wrong environment pipeline/modes")
        require(int(env["samples"]) == policy["samples"] and int(env["warmups"]) == policy["warmups"], "Changed sample budget")
        require(int(env["max_heap_bytes"]) == 4 * 1024**3 and env["gpu_calculation"] == "false", "Wrong heap or GPU calculation")
        environments.add(tuple(env[key] for key in ENVIRONMENT))
        manifests.add((run / "manifest.csv").read_bytes())
        rows = read_csv(run / "samples.csv")
        require(len({row["request"] for row in rows}) == len(rows), "Duplicate request")
        runs[pair, label] = {tuple(row[k] for k in (*KEY, "requested_mode", "phase", "run")): row for row in rows}
        require(len(runs[pair, label]) == len(rows), "Duplicate sample")
        peak = launch["result"]["max_rss_bytes"]
        require(type(peak) is int and peak > 0, "Missing peak RSS")
        peaks[pair, label] = peak
    require(len(environments) == 1 and len(manifests) == 1, "Changed environment or workload manifest")
    manifest = read_csv(directory / launches[0]["directory"] / "manifest.csv")
    validate_manifest(policy, manifest)
    definitions = {tuple(row[k] for k in KEY): row for row in manifest}
    matched = []
    for metric in policy["metrics"]:
        selected = {key for key, row in definitions.items()
                    if ("fixture" not in metric or metric["fixture"] == row["fixture"])
                    and ("action" not in metric or metric["action"] == row["action"])}
        require(selected, "Declared metric has no matching case")
        for pair in range(policy["process_pairs"]):
            a, b = runs[pair, "A"], runs[pair, "B"]
            require(a.keys() == b.keys(), "Unpaired request coverage")
            for key in a:
                ar, br = a[key], b[key]
                require((ar["sample_hash"], ar["argb_hash"], ar["effective_mode"]) ==
                        (br["sample_hash"], br["argb_hash"], br["effective_mode"]), "Paired fingerprints or presentation differ")
                action = definitions[key[:4]]["action"]
                for label, row in (("A", ar), ("B", br)):
                    preparation = float(row["preparation_ms"])
                    require(math.isfinite(preparation) and (preparation >= 0 if label == "B" and action != "REUSE" else preparation == -1), "Wrong preparation timing scope")
                    require(math.isfinite(float(row["fx_setup_ms"])) and float(row["fx_setup_ms"]) > 0, "Missing FX setup work")
                if key[:4] not in selected or ar["phase"] != "sample":
                    continue
                av, bv = float(ar[metric["metric"]]), float(br[metric["metric"]])
                require(math.isfinite(av) and math.isfinite(bv) and av > 0 and bv > 0, "Missing or invalid metric")
                matched.append(dict(process_pair=pair, fixture=key[0], step=key[1], width=key[2], height=key[3], mode=key[4],
                                    run=key[6], metric=metric["metric"], role=metric["role"], max_ratio=metric["max_ratio"],
                                    a_ms=av, b_ms=bv, ratio=bv / av))
    memory = [dict(process_pair=pair, a_peak_rss_bytes=peaks[pair, "A"], b_peak_rss_bytes=peaks[pair, "B"],
                   ratio=peaks[pair, "B"] / peaks[pair, "A"]) for pair in range(policy["process_pairs"])]
    return policy, matched, memory


def summarize(directory):
    require(not (directory / "analysis.json").exists() and not (directory / "pairs.csv").exists(), "Analysis output already exists")
    policy, pairs, memory = validate_campaign(directory)
    groups = defaultdict(list)
    keys = ("fixture", "step", "width", "height", "mode", "metric", "role", "max_ratio")
    for row in pairs:
        groups[tuple(row[k] for k in keys)].append(row)
    summaries = []
    for identity, rows in sorted(groups.items()):
        require(len(rows) == policy["process_pairs"] * policy["samples"], "Incomplete metric budget")
        stats = paired_statistics(rows)
        limit = identity[-1]
        verdict = "pass" if stats["ratio_ci95_high"] <= limit else "fail" if stats["ratio_ci95_low"] > limit else "inconclusive"
        summaries.append(dict(zip(keys, identity), **stats, timing_verdict=verdict))
    timing = "pass" if all(row["timing_verdict"] == "pass" for row in summaries) else "fail" if any(row["timing_verdict"] == "fail" for row in summaries) else "inconclusive"
    memory_verdict = "pass" if all(row["ratio"] <= policy["rss_max_pair_ratio"] for row in memory) else "fail"
    decision = "reject" if timing == "fail" or memory_verdict == "fail" else "retain" if timing == "pass" else "inconclusive"
    result = dict(purpose="benchmark_experiment", decision=decision, production_promotion="not_evaluated",
                  timing_verdict=timing, memory_verdict=memory_verdict, metrics=summaries, memory=memory,
                  policy_sha256=hashlib.sha256((directory / "policy.json").read_bytes()).hexdigest(),
                  confidence_method="5000 seeded hierarchical bootstrap resamples by process pair, then matched sample",
                  memory_rule="Every observed paired peak RSS ratio must meet the limit; this is not a long-session memory or leak test")
    with (directory / "pairs.csv").open("x", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=pairs[0], lineterminator="\n")
        writer.writeheader(); writer.writerows(pairs)
    with (directory / "analysis.json").open("x") as stream:
        json.dump(result, stream, indent=2); stream.write("\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    result = summarize(args.directory)
    print(result["decision"] + ": " + str(len(result["metrics"])) + " timing groups; RSS " + result["memory_verdict"])
