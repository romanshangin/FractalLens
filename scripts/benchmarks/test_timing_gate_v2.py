import csv
import json
import math
from pathlib import Path
import random
import tempfile
import unittest
from unittest.mock import patch

import timing_gate_v2 as gate

FIXTURES = ("pan-25", "wide-zoom-out")
CELLS = [(f, "1", w, h, m) for f in FIXTURES for w, h in (("1512", "982"), ("3024", "1964")) for m in ("FAST", "REFINED")]
METRICS = gate.PRIMARY_METRICS + gate.DIAGNOSTIC_METRICS


def base_ms(metric):
    return .3 if metric == "first_input_to_preview_ms" else 50.0


def synthetic(pairs, noise, effect=None, seed=1, samples=5):
    """Matched samples per pair with multiplicative noise; effect maps group -> B/A factor."""
    rng = random.Random(seed)
    effect = effect or {}
    out = []
    for _ in range(pairs):
        pair = {}
        for cell in CELLS:
            for metric in METRICS:
                group = cell + (metric,)
                a = [base_ms(metric) * math.exp(rng.gauss(0, noise)) for _ in range(samples)]
                b = [base_ms(metric) * effect.get(group, 1) * math.exp(rng.gauss(0, noise)) for _ in range(samples)]
                pair[group] = (a, b)
        out.append(pair)
    return out


def run(pairs, resamples=1000):
    return gate.evaluate(gate.pair_log_ratios(pairs), gate.control_medians(pairs), resamples)


def group(result, key):
    return next(g for g in result["groups"] if tuple(g["group"]) == key)


class StatisticsTest(unittest.TestCase):
    def test_t_quantiles_match_reference_tables(self):
        self.assertAlmostEqual(gate.t_quantile(.025, 10), 2.228139, places=5)
        self.assertAlmostEqual(gate.t_quantile(.005, 23), 2.807336, places=5)
        self.assertAlmostEqual(gate.t_quantile(.0005, 29), 3.659405, places=5)
        self.assertAlmostEqual(gate.t_quantile(.025, 1), 12.706205, places=4)

    def test_pair_log_ratio_uses_process_medians(self):
        rows = gate.pair_log_ratios([{("f", "m"): ([1, 2, 30], [2, 4, 5])}])
        self.assertAlmostEqual(rows[0][("f", "m")], math.log(2))

    def test_rejects_unmatched_or_invalid_samples(self):
        with self.assertRaisesRegex(ValueError, "matched"):
            gate.pair_log_ratios([{("f", "m"): ([1, 2], [1])}])
        with self.assertRaisesRegex(ValueError, "invalid"):
            gate.pair_log_ratios([{("f", "m"): ([1, 0], [1, 1])}])
        with self.assertRaisesRegex(ValueError, "group set"):
            gate.pair_log_ratios([{("f", "m"): ([1], [1])}, {("g", "m"): ([1], [1])}])

    def test_owner_limits(self):
        self.assertEqual(gate.LIMITS, dict(average=1.13, group=1.25, group_floor_ms=1.0))
        self.assertEqual(gate.group_limit(50), 1.25)
        self.assertAlmostEqual(gate.group_limit(.5), 3.0)

    def test_identical_low_noise_builds_pass(self):
        result = run(synthetic(12, .01))
        self.assertEqual(result["timing_verdict"], "pass")
        self.assertEqual(result["overall"]["groups"], 32)
        self.assertEqual(len(result["families"]), 8)
        self.assertEqual(len(result["groups"]), 56)

    def test_average_regression_above_13_percent_fails(self):
        result = run(synthetic(12, .01, {c + (m,): 1.16 for c in CELLS for m in METRICS}))
        self.assertEqual(result["overall"]["verdict"], "fail")
        self.assertEqual(result["timing_verdict"], "fail")

    def test_average_regression_below_13_percent_passes(self):
        result = run(synthetic(12, .01, {c + (m,): 1.10 for c in CELLS for m in METRICS}))
        self.assertEqual(result["overall"]["verdict"], "pass")
        self.assertEqual(result["timing_verdict"], "pass")

    def test_family_average_is_checked_separately_from_overall(self):
        effect = {c + ("last_input_to_aa_ms",): 1.20 for c in CELLS if c[0] == "pan-25"}
        result = run(synthetic(12, .01, effect))
        self.assertEqual(result["overall"]["verdict"], "pass")
        family = next(f for f in result["families"] if (f["fixture"], f["metric"]) == ("pan-25", "last_input_to_aa_ms"))
        self.assertEqual(family["verdict"], "fail")
        self.assertTrue(all(g["verdict"] == "pass" for g in result["groups"]))
        self.assertEqual(result["timing_verdict"], "fail")

    def test_single_group_regression_up_to_25_percent_is_accepted(self):
        key = CELLS[5] + ("last_input_to_aa_ms",)
        result = run(synthetic(12, .01, {key: 1.20}))
        self.assertEqual(group(result, key)["verdict"], "pass")
        self.assertEqual(result["timing_verdict"], "pass")

    def test_group_estimate_above_tolerance_without_confirmation_is_inconclusive(self):
        key = CELLS[5] + ("last_input_to_aa_ms",)
        result = run(synthetic(6, .3, {key: 1.6}, seed=3))
        row = group(result, key)
        self.assertGreater(row["ratio"], 1.25)
        self.assertLessEqual(row["adjusted_low"], 1.25)
        self.assertEqual(row["verdict"], "inconclusive")
        self.assertNotEqual(result["timing_verdict"], "pass")

    def test_noisy_group_within_tolerance_passes_on_its_estimate(self):
        key = CELLS[5] + ("last_input_to_aa_ms",)
        result = run(synthetic(12, .01, {key: 1.20}))
        row = group(result, key)
        self.assertLessEqual(row["ratio"], 1.25)
        self.assertEqual(row["verdict"], "pass")

    def test_single_group_regression_above_25_percent_fails(self):
        key = CELLS[5] + ("last_input_to_aa_ms",)
        result = run(synthetic(12, .01, {key: 1.35}))
        self.assertEqual(group(result, key)["verdict"], "fail")
        self.assertEqual(result["overall"]["verdict"], "pass")
        self.assertEqual(result["timing_verdict"], "fail")

    def test_diagnostic_metric_is_group_checked_but_not_averaged(self):
        key = CELLS[0] + ("last_input_to_complete_ms",)
        result = run(synthetic(12, .01, {key: 1.5}))
        self.assertAlmostEqual(result["overall"]["ratio"], 1, delta=.01)
        self.assertEqual(group(result, key)["role"], "diagnostic")
        self.assertEqual(group(result, key)["verdict"], "fail")

    def test_sub_millisecond_change_below_absolute_floor_passes(self):
        key = CELLS[0] + ("first_input_to_preview_ms",)
        result = run(synthetic(12, .01, {key: 2.0}))
        row = group(result, key)
        self.assertGreater(row["adjusted_low"], 1.5)
        self.assertAlmostEqual(row["limit"], 1 + 1 / .3, delta=.1)
        self.assertEqual(row["verdict"], "pass")
        self.assertEqual(result["timing_verdict"], "pass")

    def test_noisy_data_are_inconclusive_not_failures(self):
        result = run(synthetic(4, .5))
        self.assertEqual(result["timing_verdict"], "inconclusive")

    def test_evaluation_is_deterministic_for_a_seed(self):
        pairs = synthetic(6, .2)
        self.assertEqual(run(pairs), run(pairs))


class PlanTest(unittest.TestCase):
    def plan(self, noise, sizes):
        pairs = synthetic(10, noise, seed=7)
        with patch.object(gate, "PLAN_RESAMPLES", 200):
            return gate.plan(gate.pair_log_ratios(pairs), gate.control_medians(pairs), sizes=sizes, draws=40)

    def test_quiet_aa_data_select_the_smallest_size(self):
        result = self.plan(.01, (6, 12))
        self.assertTrue(result["runnable"])
        self.assertEqual(result["selected"]["process_pairs"], 6)
        self.assertEqual(result["limits"], gate.LIMITS)
        self.assertEqual(result["observed_aa"]["timing_verdict"], "pass")

    def test_noisy_aa_data_are_not_runnable(self):
        result = self.plan(1.0, (6,))
        self.assertFalse(result["runnable"])
        self.assertIsNone(result["selected"])

    def test_identical_builds_rarely_fail_in_simulation(self):
        result = self.plan(.3, (6,))
        self.assertLessEqual(result["candidates"][0]["false_fail_rate"], gate.FALSE_FAIL_LIMIT)


class CampaignTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = {}

    def row(self, cell, run, value, sample_hash="1"):
        row = dict(zip(gate.GROUP_FIELDS, cell), phase="sample", run=str(run), status="complete", sample_hash=sample_hash, argb_hash="2")
        row.update({m: str(value) for m in METRICS})
        return row

    def write_campaign(self, pairs, settings, factor=1.0, api="present"):
        (self.root / "RUN_COMPLETE").write_text("complete\n")
        rng = random.Random(5)
        for p in range(pairs):
            for label in "AB":
                name = f"pair-{p:02d}-{label}"
                (self.root / (name + "-result.json")).write_text(json.dumps(dict(result=dict(returncode=0))))
                (self.root / name).mkdir()
                (self.root / name / "environment.txt").write_text(
                    f"input_matrix=9.1-input-v1\nwide_preview_settings={settings[label]}\nwide_preview_api={api}\n")
                scale = factor if label == "B" else 1.0
                self.data[name] = [self.row(cell, run, 10 * scale * (1 + .01 * rng.random())) for cell in CELLS for run in range(2)]

    def load(self, settings):
        with patch("summarize_baseline_input.validate", side_effect=lambda d: self.data[d.name]):
            return gate.load_campaign(self.root, settings)

    def analyze(self, frozen):
        with patch("summarize_baseline_input.validate", side_effect=lambda d: self.data[d.name]):
            return gate.analyze_campaign(self.root, frozen)

    def frozen_plan(self, pairs=3, **changes):
        groups = sorted(c + (m,) for c in CELLS for m in METRICS)
        plan = dict(version=gate.VERSION, limits=dict(gate.LIMITS), runnable=True, selected=dict(process_pairs=pairs),
                    control_medians=[dict(group=list(g), median_ms=10.0) for g in groups])
        plan.update(changes)
        return plan

    def test_loads_matched_samples_and_rejects_fingerprint_mismatch(self):
        self.write_campaign(2, gate.AA_SETTINGS)
        pairs = self.load(gate.AA_SETTINGS)
        self.assertEqual(len(pairs), 2)
        self.assertEqual(len(pairs[0]), 56)
        self.assertEqual(len(pairs[0][CELLS[0] + ("last_input_to_aa_ms",)][0]), 2)
        self.data["pair-01-B"][0]["sample_hash"] = "other"
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            self.load(gate.AA_SETTINGS)

    def test_requires_completed_campaign(self):
        with self.assertRaisesRegex(ValueError, "not complete"):
            gate.load_campaign(self.root, gate.AA_SETTINGS)

    def test_rejects_labels_recorded_with_other_settings(self):
        self.write_campaign(2, gate.CANDIDATE_SETTINGS)
        with self.assertRaisesRegex(ValueError, "pair 0 B recorded settings other than DEFAULT"):
            self.load(gate.AA_SETTINGS)
        self.assertEqual(len(self.load(gate.CANDIDATE_SETTINGS)), 2)

    def test_rejects_builds_without_the_preview_api(self):
        self.write_campaign(2, gate.CANDIDATE_SETTINGS, api="absent")
        with self.assertRaisesRegex(ValueError, "lacks the wide preview API"):
            self.load(gate.CANDIDATE_SETTINGS)

    def test_rejects_a_metric_missing_from_both_builds(self):
        self.write_campaign(2, gate.AA_SETTINGS)
        for rows in self.data.values():
            for row in rows:
                if row["fixture"] == "wide-zoom-out":
                    row["last_input_to_aa_ms"] = "-1"
        with self.assertRaisesRegex(ValueError, "complete cell x metric matrix"):
            self.load(gate.AA_SETTINGS)

    def test_analyze_applies_a_matching_plan(self):
        self.write_campaign(3, gate.CANDIDATE_SETTINGS)
        self.assertEqual(self.analyze(self.frozen_plan())["timing_verdict"], "pass")

    def test_analyze_detects_a_regression_against_the_plan(self):
        self.write_campaign(3, gate.CANDIDATE_SETTINGS, factor=1.4)
        self.assertEqual(self.analyze(self.frozen_plan())["timing_verdict"], "fail")

    def test_analyze_refuses_plans_that_do_not_match(self):
        self.write_campaign(3, gate.CANDIDATE_SETTINGS)
        cases = [(self.frozen_plan(limits=dict(gate.LIMITS, average=1.20)), "limits differ"),
                 (self.frozen_plan(pairs=4), "size differs"),
                 (self.frozen_plan(runnable=False), "not a runnable"),
                 (self.frozen_plan(version="timing-gate-v1"), "not a runnable"),
                 (self.frozen_plan(control_medians=self.frozen_plan()["control_medians"][1:]), "groups differ")]
        for frozen, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    self.analyze(frozen)

    def test_plan_is_not_runnable_when_observed_aa_fails(self):
        pairs = synthetic(10, .01, {c + (m,): 1.5 for c in CELLS for m in METRICS})
        passing = dict(timing="pass", overall="pass", families=True, groups=True)
        with patch.object(gate, "_simulate", side_effect=lambda rows, medians, n, draws, seed, workers: [passing] * draws):
            result = gate.plan(gate.pair_log_ratios(pairs), gate.control_medians(pairs), sizes=(6,), draws=10)
        self.assertEqual(result["selected"]["process_pairs"], 6)
        self.assertEqual(result["observed_aa"]["timing_verdict"], "fail")
        self.assertFalse(result["runnable"])


if __name__ == "__main__":
    unittest.main()
