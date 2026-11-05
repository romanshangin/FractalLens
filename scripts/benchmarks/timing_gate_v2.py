#!/usr/bin/env python3
"""Timing gate v2: A/A-calibrated timing acceptance (see benchmarks/TIMING_GATE_V2.md).

The unit of analysis is a process pair. For each timing group, every process is reduced to the
median of its measured samples, and the pair contributes d = ln(median B / median A).

- Averages: the geometric-mean B/A over all primary groups (overall) and over each fixture x
  primary metric (family). Each passes if its upper 95% bound, from a joint percentile bootstrap
  over process pairs, is <= 1.13 and fails if its lower bound exceeds 1.13.
- Individual groups (all 56, primary and diagnostic) have the tolerance
  max(1.25, 1 + 1 ms / control median). A group fails if its Bonferroni-adjusted (one-sided
  0.025 / groups) Student-t lower bound exceeds the tolerance. Otherwise it passes if its
  geometric-mean B/A point estimate is within the tolerance, and is inconclusive if it is not.
  Individual regressions within the tolerance are accepted by owner decision; they are not proven
  absent. The t bound is used because a percentile bootstrap cannot reach the Bonferroni tail with
  few pairs: its lower bound never falls below the smallest pair, which inflates false failures.

Timing passes only if every average and every group passes; any fail is a fail. The limits were
set by the project owner on 2026-11-05. An A/A calibration plan chooses only the number of pairs.
"""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import random
import statistics

VERSION = "timing-gate-v2"
PRIMARY_METRICS = ("first_input_to_preview_post_layout_ms", "last_input_to_first_target_post_layout_ms",
                   "last_input_to_aa_ms", "last_input_to_post_layout_ms")
DIAGNOSTIC_METRICS = ("first_input_to_preview_ms", "last_input_to_first_target_ms", "last_input_to_complete_ms")
GROUP_FIELDS = ("fixture", "step", "seed_width", "seed_height", "requested_mode")
MATCH_FIELDS = GROUP_FIELDS + ("phase", "run", "status")
AVERAGE_LIMIT = 1.13
GROUP_LIMIT = 1.25
GROUP_FLOOR_MS = 1.0
CONFIDENCE = .95
SEED = 910930
RESAMPLES = 5000
PLAN_SIZES = (12, 18, 24, 30, 36)
PLAN_DRAWS = 1000
PLAN_RESAMPLES = 1000
PLAN_SEED = 20261105
JOINT_PASS_TARGET = .80
FALSE_FAIL_LIMIT = .05
LIMITS = dict(average=AVERAGE_LIMIT, group=GROUP_LIMIT, group_floor_ms=GROUP_FLOOR_MS)
# WidePreviewInputBenchmark records wide_preview_settings per process; one frozen build is its own control.
AA_SETTINGS = {"A": "DEFAULT", "B": "DEFAULT"}
CANDIDATE_SETTINGS = {"A": "DEFAULT", "B": "CANDIDATE"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def family_of(group):
    """Groups are tuples whose first element is the fixture and whose last element is the metric."""
    return group[0], group[-1]


def pair_log_ratios(pairs):
    """pairs: list of {group: (a_samples, b_samples)} -> list of {group: ln(median B / median A)}."""
    require(pairs, "No process pairs")
    groups = set(pairs[0])
    rows = []
    for index, pair in enumerate(pairs):
        require(set(pair) == groups, f"Pair {index} has a different group set")
        row = {}
        for group, (a, b) in pair.items():
            require(a and b and len(a) == len(b), f"Pair {index} group {group} lacks matched samples")
            require(all(v > 0 and math.isfinite(v) for v in list(a) + list(b)), f"Pair {index} group {group} has invalid timing")
            row[group] = math.log(statistics.median(b) / statistics.median(a))
        rows.append(row)
    return rows


def control_medians(pairs):
    """Median control (A) sample per group over all pairs; used only for the 1 ms group floor."""
    return {group: statistics.median(v for pair in pairs for v in pair[group][0]) for group in pairs[0]}


def group_limit(median_ms):
    return max(GROUP_LIMIT, 1 + GROUP_FLOOR_MS / median_ms)


def verdict(low, high, limit):
    return "pass" if high <= limit else "fail" if low > limit else "inconclusive"


def joint_bootstrap(rows, resamples, rng):
    """Bootstrap distribution of per-group mean log ratios, resampling whole pairs jointly."""
    groups = sorted(rows[0])
    columns = [[row[g] for row in rows] for g in groups]
    n = len(rows)
    draws = {g: [] for g in groups}
    for _ in range(resamples):
        picks = [rng.randrange(n) for _ in range(n)]
        for g, column in zip(groups, columns):
            draws[g].append(sum(column[i] for i in picks) / n)
    return draws


def _beta_continued_fraction(a, b, x):
    """Lentz evaluation of the incomplete-beta continued fraction (Numerical Recipes betacf)."""
    tiny = 1e-300
    c, d = 1.0, 1 - (a + b) * x / (a + 1)
    d = 1 / (d if abs(d) > tiny else tiny)
    h = d
    for m in range(1, 1000):
        for numerator in (m * (b - m) * x / ((a + 2 * m - 1) * (a + 2 * m)),
                          -(a + m) * (a + b + m) * x / ((a + 2 * m) * (a + 2 * m + 1))):
            d = 1 + numerator * d
            d = 1 / (d if abs(d) > tiny else tiny)
            c = 1 + numerator / c
            c = c if abs(c) > tiny else tiny
            h *= d * c
        if abs(d * c - 1) < 1e-15:
            return h
    raise ArithmeticError("Incomplete beta did not converge")


def regularized_beta(x, a, b):
    if x <= 0 or x >= 1:
        return 0.0 if x <= 0 else 1.0
    front = math.exp(math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b) + a * math.log(x) + b * math.log(1 - x))
    if x < (a + 1) / (a + b + 2):
        return front * _beta_continued_fraction(a, b, x) / a
    return 1 - front * _beta_continued_fraction(b, a, 1 - x) / b


def t_upper_tail(t, df):
    """P(T > t) for t >= 0."""
    return .5 * regularized_beta(df / (df + t * t), df / 2, .5)


def t_quantile(tail, df):
    """The t >= 0 with P(T > t) = tail, by bisection."""
    require(0 < tail < .5 and df >= 1, "Invalid t quantile request")
    low, high = 0.0, 1.0
    while t_upper_tail(high, df) > tail:
        high *= 2
    for _ in range(200):
        middle = (low + high) / 2
        low, high = (middle, high) if t_upper_tail(middle, df) > tail else (low, middle)
    return (low + high) / 2


def quantile(sorted_values, q):
    return sorted_values[min(len(sorted_values) - 1, max(0, int(q * len(sorted_values))))]


def evaluate(rows, medians, resamples=RESAMPLES, seed=SEED):
    """Applies the gate to per-pair log ratios; medians are control medians per group (ms)."""
    require(len(rows) >= 2, "At least two process pairs are required")
    groups = sorted(rows[0])
    require({g[-1] for g in groups} <= set(PRIMARY_METRICS + DIAGNOSTIC_METRICS), "Unknown timing metric")
    primary = [g for g in groups if g[-1] in PRIMARY_METRICS]
    require(primary, "No primary timing groups")
    families = defaultdict(list)
    for g in primary:
        families[family_of(g)].append(g)
    draws = joint_bootstrap(rows, resamples, random.Random(seed))
    tail = (1 - CONFIDENCE) / 2
    adjusted_tail = tail / len(groups)
    n = len(rows)
    t95, t_adjusted = t_quantile(tail, n - 1), t_quantile(adjusted_tail, n - 1)

    def average(members):
        series = sorted(sum(draws[g][i] for g in members) / len(members) for i in range(resamples))
        point = sum(sum(row[g] for g in members) / len(members) for row in rows) / n
        low, high = math.exp(quantile(series, tail)), math.exp(quantile(series, 1 - tail))
        return dict(groups=len(members), ratio=math.exp(point), ci95_low=low, ci95_high=high,
                    limit=AVERAGE_LIMIT, verdict=verdict(low, high, AVERAGE_LIMIT))

    overall = average(primary)
    result_families = [dict(fixture=f[0], metric=f[1], **average(members)) for f, members in sorted(families.items())]
    result_groups = []
    for g in groups:
        values = [row[g] for row in rows]
        mean, error = statistics.mean(values), statistics.stdev(values) / math.sqrt(n)
        limit = group_limit(medians[g])
        high, adjusted_low = math.exp(mean + t95 * error), math.exp(mean - t_adjusted * error)
        ratio = math.exp(mean)
        group_verdict = "fail" if adjusted_low > limit else "pass" if ratio <= limit else "inconclusive"
        result_groups.append(dict(group=list(g), role="primary" if g in primary else "diagnostic", control_median_ms=medians[g],
                                  ratio=ratio, ci95_low=math.exp(mean - t95 * error), ci95_high=high,
                                  adjusted_low=adjusted_low, limit=limit, verdict=group_verdict))
    verdicts = [overall["verdict"]] + [f["verdict"] for f in result_families] + [g["verdict"] for g in result_groups]
    timing = "fail" if "fail" in verdicts else "pass" if all(v == "pass" for v in verdicts) else "inconclusive"
    return dict(version=VERSION, limits=LIMITS, process_pairs=n, adjusted_tail=adjusted_tail, timing_verdict=timing,
                overall=overall, families=result_families, groups=result_groups)


def oriented_draw(rows, n, rng):
    """Draw n identical-build pairs with replacement; their A/B labels are exchangeable."""
    out = []
    for _ in range(n):
        row = rows[rng.randrange(len(rows))]
        sign = -1 if rng.random() < .5 else 1
        out.append({g: sign * v for g, v in row.items()})
    return out


_POOL_DATA = None


def _init_pool(rows, medians):
    global _POOL_DATA
    _POOL_DATA = rows, medians


def _draw_statistics(task):
    n, seed = task
    rows, medians = _POOL_DATA
    rng = random.Random(seed)
    sample = oriented_draw(rows, n, rng)
    result = evaluate(sample, medians, PLAN_RESAMPLES, rng.randrange(1 << 30))
    return dict(timing=result["timing_verdict"], overall=result["overall"]["verdict"],
                families=all(f["verdict"] == "pass" for f in result["families"]),
                groups=all(g["verdict"] == "pass" for g in result["groups"]))


def _simulate(rows, medians, n, draws, seed, workers):
    rng = random.Random(seed)
    tasks = [(n, rng.randrange(1 << 30)) for _ in range(draws)]
    if workers <= 1:
        _init_pool(rows, medians)
        return [_draw_statistics(t) for t in tasks]
    from multiprocessing import Pool
    with Pool(workers, initializer=_init_pool, initargs=(rows, medians)) as pool:
        return pool.map(_draw_statistics, tasks, chunksize=max(1, draws // (4 * workers)))


def plan(rows, medians, sizes=PLAN_SIZES, draws=PLAN_DRAWS, seed=PLAN_SEED, workers=1):
    """Choose the process-pair count from identical-build (A/A) pairs.

    For each N, draws N pairs with replacement and random orientation and applies the full gate.
    The plan selects the smallest N with joint pass >= JOINT_PASS_TARGET and false-fail <=
    FALSE_FAIL_LIMIT, and requires that the observed A/A data do not fail. Otherwise the plan is
    not runnable and the candidate stage must not run.
    """
    require(len(rows) >= 2, "At least two A/A pairs are required")
    rng = random.Random(seed)
    candidates = []
    for n in sizes:
        outcomes = _simulate(rows, medians, n, draws, rng.randrange(1 << 30), workers)
        rate = lambda condition: sum(condition(o) for o in outcomes) / draws
        candidates.append(dict(process_pairs=n, joint_pass_rate=rate(lambda o: o["timing"] == "pass"),
                               false_fail_rate=rate(lambda o: o["timing"] == "fail"),
                               overall_pass_rate=rate(lambda o: o["overall"] == "pass"),
                               all_families_pass_rate=rate(lambda o: o["families"]),
                               all_groups_pass_rate=rate(lambda o: o["groups"])))
    chosen = next((c for c in candidates if c["joint_pass_rate"] >= JOINT_PASS_TARGET and c["false_fail_rate"] <= FALSE_FAIL_LIMIT), None)
    observed = evaluate(rows, medians)
    runnable = chosen is not None and observed["timing_verdict"] != "fail"
    return dict(version=VERSION, limits=LIMITS, aa_process_pairs=len(rows), draws=draws, plan_resamples=PLAN_RESAMPLES,
                seed=seed, candidates=candidates, selected=chosen, observed_aa=summary(observed),
                control_medians=[dict(group=list(g), median_ms=m) for g, m in sorted(medians.items())], runnable=runnable)


def summary(result):
    return dict(timing_verdict=result["timing_verdict"], overall=result["overall"], families=result["families"],
                group_counts={v: sum(g["verdict"] == v for g in result["groups"]) for v in ("pass", "inconclusive", "fail")},
                failed_groups=[g["group"] for g in result["groups"] if g["verdict"] == "fail"])


def require_complete_matrix(groups):
    """Every measured cell must contribute all seven metrics, so no group or Bonferroni term disappears."""
    cells = {g[:-1] for g in groups}
    metrics = {g[-1] for g in groups}
    require(metrics == set(PRIMARY_METRICS + DIAGNOSTIC_METRICS) and len(groups) == len(cells) * len(metrics),
            "Timing groups do not form a complete cell x metric matrix")


def process_settings(directory):
    env = dict(line.split("=", 1) for line in (directory / "environment.txt").read_text().splitlines() if "=" in line)
    require(env.get("wide_preview_api") == "present", f"{directory.name} lacks the wide preview API")
    return env.get("wide_preview_settings")


def load_campaign(directory, settings):
    """Reads a completed campaign of pair-NN-A / pair-NN-B process directories into matched samples.

    settings maps each label to the wide_preview_settings value its processes must have recorded.
    """
    from summarize_baseline_input import validate
    require((directory / "RUN_COMPLETE").exists(), f"{directory} is not complete")
    count = len(list(directory.glob("pair-*-A-result.json")))
    require(count >= 2 and len(list(directory.glob("pair-*-B-result.json"))) == count, "Unbalanced process pairs")
    pairs = []
    for p in range(count):
        processes = {}
        for label in "AB":
            record = json.loads((directory / f"pair-{p:02d}-{label}-result.json").read_text())
            require(record["result"]["returncode"] == 0, f"pair {p} {label} failed")
            process = directory / f"pair-{p:02d}-{label}"
            processes[label] = validate(process)
            require(process_settings(process) == settings[label],
                    f"pair {p} {label} recorded settings other than {settings[label]}")
        pair = defaultdict(lambda: ([], []))
        a_rows, b_rows = processes["A"], processes["B"]
        require(len(a_rows) == len(b_rows), f"pair {p} row count differs")
        for x, y in zip(a_rows, b_rows):
            require(all(x[k] == y[k] for k in MATCH_FIELDS), f"pair {p} rows are not matched")
            require((x["sample_hash"], x["argb_hash"]) == (y["sample_hash"], y["argb_hash"]), f"pair {p} fingerprint mismatch")
            if x["phase"] != "sample":
                continue
            for metric in PRIMARY_METRICS + DIAGNOSTIC_METRICS:
                av, bv = float(x[metric]), float(y[metric])
                if av < 0 or bv < 0:
                    require(av == bv, f"pair {p} {metric} is present in only one build")
                    continue
                group = tuple(x[k] for k in GROUP_FIELDS) + (metric,)
                pair[group][0].append(av)
                pair[group][1].append(bv)
        pairs.append(dict(pair))
        require(set(pairs[-1]) == set(pairs[0]), f"pair {p} has a different group set")
    require_complete_matrix(set(pairs[0]))
    return pairs


def write_new(path, data):
    with path.open("x") as f:
        json.dump(data, f, indent=2, default=list)
        f.write("\n")


def plan_campaign(aa_directory, workers=1):
    pairs = load_campaign(aa_directory, AA_SETTINGS)
    return plan(pair_log_ratios(pairs), control_medians(pairs), workers=workers)


def analyze_campaign(directory, frozen):
    """Applies a frozen plan; refuses plans that differ from this analyzer or the campaign."""
    require(frozen["version"] == VERSION and frozen["runnable"], "Plan is not a runnable timing-gate-v2 plan")
    require(frozen["limits"] == LIMITS, "Plan limits differ from this analyzer")
    pairs = load_campaign(directory, CANDIDATE_SETTINGS)
    require(len(pairs) == frozen["selected"]["process_pairs"], "Campaign size differs from the plan")
    require({tuple(m["group"]) for m in frozen["control_medians"]} == set(pairs[0]), "Campaign groups differ from the plan")
    return evaluate(pair_log_ratios(pairs), control_medians(pairs))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan", help="choose N from an A/A campaign")
    p.add_argument("aa", type=Path)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--workers", type=int, default=1, help="simulation processes; results do not depend on it")
    a = sub.add_parser("analyze", help="apply a frozen plan to a candidate campaign")
    a.add_argument("campaign", type=Path)
    a.add_argument("--plan", type=Path, required=True)
    a.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "plan":
        result = plan_campaign(args.aa, args.workers)
        write_new(args.output, result)
        print("runnable" if result["runnable"] else "not runnable", json.dumps(result["selected"] and result["selected"]["process_pairs"]))
        return
    result = analyze_campaign(args.campaign, json.loads(args.plan.read_text()))
    write_new(args.output, result)
    print(result["timing_verdict"], json.dumps(summary(result)["group_counts"]))


if __name__ == "__main__":
    main()
