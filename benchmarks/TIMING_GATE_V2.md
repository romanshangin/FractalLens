# Timing gate v2 (A/A-calibrated)

**Status: limits set by the owner on 2026-11-05. The analyzer, driver switch
and this protocol passed independent review and verification. With its A/A
calibration it was applied to the wide preview in
[TG2-1](#first-application-tg2-1) (plan not runnable), TG2-2 (inconclusive), TG2-3 (aborted in
its A/A stage) and TG2-4 (retain). Without the A/A
calibration stage, and with fixed pair counts, it was applied in the
[spare frame storage campaigns SF1/SF2](SPARE_FRAME_STORAGE_RESULTS.md).** The gate becomes
binding for a campaign only when its protocol is declared and hashed before
that campaign's first process. All earlier decisions stand, and TE1 stays
**inconclusive**. The TG2-4 retain decision, not this protocol, enabled
`WidePreviewSettings.DEFAULT`.

Owner decision: **an individual regression of up to 25% is accepted, and an
average must not exceed 13%.**

Analyzer: [`scripts/benchmarks/timing_gate_v2.py`](../scripts/benchmarks/timing_gate_v2.py)
(`plan` for an A/A campaign, `analyze` for a candidate campaign with a frozen
plan). Its unit tests are in `test_timing_gate_v2.py`.

## Why the per-group rule is replaced

Earlier wide-preview gates required **every** one of 56 timing groups (fixture x
size x mode x 7 metrics) to have an upper paired 95% B/A bound <= 1.05.
[TE1](WIDE_PREVIEW_VALIDATION.md#follow-up-timing-extension-te1) showed this is
not achievable, even for identical builds:

- **Noise.** Within one process, early-publication latencies vary by a log
  standard deviation of 0.2–0.5. Frame pulses quantize post-layout times. For
  identical builds, a single primary group's per-pair log ratio has SD
  0.03–0.39 (median 0.22). For 1512x982 FAST pan preview, it is 0.35. Detecting
  5% equivalence with 80% power would need about **400 process pairs for that
  group alone**.
- **Absolute scale.** The five TE1 groups with medians of 0.26–0.42 ms had 5%
  limits of tens of microseconds. These handler timings come before layout.
- **Multiplicity.** "All 56 pass" is an intersection of 56 underpowered tests.
  In the planning simulation, identical builds passed all groups with probability
  about 0 at 24 pairs. Meanwhile, the fail side had no multiplicity control: identical builds
  failed at least one group in 9% of simulated draws.

More pairs cannot fix this. The rule asks each noisy cell for a precision that
the harness cannot deliver.

## Rule

Everything other than the timing rule is unchanged from the v2 wide stage:
frozen builds, command lines, matrix, three warmups and five samples, AB/BA
alternation, exact sample/ARGB controls, complete-matrix validation, reserve
availability >= 95%, memory gate v2, and thermal observations.

**Unit.** For each process pair p and group g:
`d_pg = ln(median of B's measured samples / median of A's)`. Process medians
make one outlier sample harmless.

**Primary metrics** describe visible milestones:
`first_input_to_preview_post_layout_ms`,
`last_input_to_first_target_post_layout_ms`, `last_input_to_aa_ms` and
`last_input_to_post_layout_ms` (32 groups). **Diagnostic metrics** are the
pre-layout handler and completion timestamps:
`first_input_to_preview_ms`, `last_input_to_first_target_ms` and
`last_input_to_complete_ms` (24 groups). Diagnostic metrics do not enter the
averages, but each diagnostic group is checked individually.

| Component | Statistic | Pass | Fail |
| --- | --- | --- | --- |
| Overall average | geometric-mean B/A over the 32 primary groups | upper 95% <= 1.13 | lower 95% > 1.13 |
| Family averages (8) | geometric-mean B/A over one fixture x primary metric (2 sizes x 2 modes) | upper 95% <= 1.13 | lower 95% > 1.13 |
| Each group (56) | geometric-mean B/A of `d_pg` | estimate <= tolerance | Bonferroni Student-t lower bound (one-sided 0.025/56) > tolerance |

The group tolerance is max(1.25, 1 + 1 ms / control median). Average intervals
are seeded (910930) 5000-resample percentile bootstraps that resample whole
process pairs jointly across groups. A component that neither passes nor fails
is inconclusive.

**Decision:** timing **fails** if any component fails. It **passes** only if both
kinds of average and every group pass. Otherwise it is **inconclusive**. The
candidate is retained only if timing, memory v2, availability, exact controls
and thermal state all pass. Any fail is a reject.

How the owner's rule is implemented:

- **Averages are proven.** The upper confidence bound must stay within 13%.
  The pass therefore requires evidence, not just a favourable estimate.
- **An individual group is accepted on its estimate.** A group estimated above
  25% blocks retain and makes the result inconclusive; a statistically
  confirmed excess rejects. Proving every one of 56 groups <= 25% would need
  about 40 or more pairs. In planning, identical builds passed that stricter
  version only 0.37 of the time at 24 pairs and 0.79 at 36. This rule does not
  prove that no group exceeds 25%.
- **Group bounds use Student t, not the bootstrap.** A percentile bootstrap
  cannot reach the Bonferroni tail with few pairs: its lower bound never falls
  below the smallest pair. With six synthetic A/A pairs, a bootstrap version
  falsely failed 0.33 of the time.
- **The 1 ms floor** exempts sub-millisecond handler changes. One millisecond
  is an eighth of a 120 Hz frame.

## Calibration (declared per campaign, before any candidate process)

1. Freeze the build. Run a **fresh A/A stage of 12 pairs** with the control
   setting under both labels and the same command lines. Fingerprints must match.
2. `timing_gate_v2.py plan <aa-dir>` draws N pairs with replacement and random
   orientation (1000 draws, 1000 resamples, seed 20261105) for N in
   {12, 18, 24, 30, 36} and applies the full rule.
3. The plan selects the smallest N with joint pass >= 0.80 and false fail
   <= 0.05. The observed A/A data must not fail. Otherwise the plan is **not
   runnable**: the candidate stage does not run, and the owner decides what
   to do next.
4. Hash and freeze the plan before the first candidate process. Run exactly N
   fresh pairs with no early stop, then run `analyze` with the frozen plan.

Calibration chooses only N. The limits are fixed by the owner's decision.

**Campaign input.** Both commands read a directory with `RUN_COMPLETE` and, for
each pair `NN` and label `A`/`B`:

- `pair-NN-L-result.json`, whose `result.returncode` must be 0;
- `pair-NN-L/`, the `WidePreviewInputBenchmark` output that
  `summarize_baseline_input.py` validates.

Matched rows must have identical sample/ARGB fingerprints, and every cell must
contribute all seven metrics. Each process must record
`wide_preview_api=present` and the declared settings:

- `plan`: `DEFAULT` for both labels;
- `analyze`: `DEFAULT` for A and `CANDIDATE` for B.

`analyze` refuses a plan that is not runnable or has different limits, a
different pair count or a different group set.

## Planning estimate (existing data, not a declared calibration)

There is no fresh A/A stage yet. The planning pool uses the 36 identical-build
pairs available on this machine:

- the 6 v2 `cal-wide` A/A pairs;
- 15 control-control pairs;
- 15 candidate-candidate pairs.

The last two groups come from consecutive same-label processes of TE1 and the v2
wide stage. These pseudo-pairs span more time than an adjacent pair, so their
noise estimate is conservative.

Aggregation reduces the per-pair SD from a median of 0.22 for a single group to
0.088 for the overall statistic. Family SDs range from 0.022 for zoom-out
completion to 0.19 for zoom-out preview.

| N pairs | Joint pass (A/A) | Overall pass | All families pass | All groups pass | False fail |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 12 | 0.569 | 0.996 | 0.577 | 0.919 | 0.000 |
| 18 | 0.737 | 1.000 | 0.740 | 0.983 | 0.000 |
| **24** | **0.870** | **1.000** | **0.874** | **0.990** | **0.000** |
| 30 | 0.925 | 1.000 | 0.926 | 0.996 | 0.000 |
| 36 | 0.960 | 1.000 | 0.960 | 1.000 | 0.000 |

The planned N is **24**, about 3.5 hours of measurement. The observed pool
passes all components. The family averages limit power; the noisiest family is
zoom-out preview after layout. A fresh 12-pair A/A stage replaces this estimate.

### What the gate detects at N = 24

These estimates come from 400 simulated draws per scenario from the same pool,
with the effects injected into B:

| True effect | Pass | Inconclusive | Fail |
| --- | ---: | ---: | ---: |
| None (identical builds) | 0.82 | 0.18 | 0.00 |
| All groups +5% | 0.35 | 0.65 | 0.00 |
| All groups +10% | 0.03 | 0.96 | 0.01 |
| All groups +13% | 0.00 | 0.85 | 0.15 |
| All groups +16% | 0.00 | 0.00 | 1.00 |
| Zoom-out early publication +10% (12 groups) | 0.10 | 0.89 | 0.01 |
| Zoom-out early publication +20% (12 groups) | 0.00 | 0.30 | 0.70 |
| Zoom-out early publication +30% (12 groups) | 0.00 | 0.00 | 1.00 |
| One noisy cell +20% (3024 FAST zoom-out first target, both variants) | 0.55 | 0.45 | 0.00 |
| One noisy cell +35% (same cell) | 0.07 | 0.91 | 0.02 |
| One noisy cell +50% (same cell) | 0.00 | 0.78 | 0.21 |
| One quiet cell +20% (3024 REFINED zoom-out first AA) | 0.82 | 0.18 | 0.00 |
| One quiet cell +30% (same cell) | 0.01 | 0.90 | 0.10 |
| One quiet cell +35% (same cell) | 0.00 | 0.30 | 0.70 |

The gate passes identical builds and accepted individual regressions most of
the time. An average regression at or above about 10% rarely passes, and one
above 13% is rejected. A single cell regressing by 35% or more almost never
passes, but in a noisy cell this is usually inconclusive rather than a reject.

## Retrospective application to TE1 (descriptive only)

TE1 cannot be scored formally. It has not been declared under this gate, and it
measured the stale 128 MiB AA-cache build against the historical control. As a
description only, all its components would pass. The overall primary B/A is
0.873 (0.847–0.899). Zoom-out preview is 0.999 (0.940–1.063), and zoom-out
first target after layout is 0.980 (0.935–1.026). The largest group estimate is
1.055, for 1512x982 FAST pan first input to preview before layout.

This does **not** change TE1's inconclusive decision. Its memory v2 G2 tail was
also inconclusive.

## Next campaign for the wide preview

This plan was written while `DEFAULT` was disabled. Since TG2-4 `DEFAULT` equals
`CANDIDATE`, and the driver's off control is `DISABLED`.

- Measure the current tree: the AA cache default is now 512 MiB.
- Use one frozen build of the current tree as its own control, rather than the
  historical main+driver control. Label A uses
  `-Dwide.preview.settings=DEFAULT`, and label B uses `CANDIDATE`. The A/A stage
  uses `DEFAULT` for both labels. The driver records the value in
  `environment.txt` and stops if `DEFAULT` leaves any reserve snapshot. A one-size
  smoke run of both values passed exact controls with identical fingerprints.
  `DEFAULT` showed no reserve, and `CANDIDATE` had a complete reserve before every
  gesture.
- Run a fresh 12-pair A/A stage, then the planned N. The planning estimate puts
  N at 24 pairs. Memory gate v2 must pass both of its gates on the same pairs.

Not covered: long sessions, GPU, Windows/Linux, Intel/AMD Macs and native
fullscreen. Planning scripts, the pool definition and the simulation outputs are
kept in the private archive.

## First application: TG2-1

[TG2-1](WIDE_PREVIEW_VALIDATION.md#follow-up-timing-gate-v2-campaign-tg2-1)
followed this plan. Its fresh 12-pair A/A stage passed all 56 groups, with an
overall ratio of 0.999 (0.978–1.022). The timing plan selected 18 pairs at 0.915
joint power with no false fails, fewer than the 24-pair planning estimate above.
The memory v2 simulation at 18 pairs reached only 0.671 power, so the declared
plan was not runnable and no candidate process ran. Choosing N is now limited by
memory power, not timing.

[TG2-2](WIDE_PREVIEW_VALIDATION.md#follow-up-campaign-tg2-2-36-pairs) then ran
36 candidate pairs on the TG2-1 plan with the owner-chosen N. Timing was
inconclusive: 55 groups passed and none failed. The zoom-out first-preview family
had an upper bound of 1.135, above 1.13. The 3024x1964 REFINED group was 1.381
(1.216–1.569), but its Bonferroni-adjusted lower bound of 1.100 did not confirm a
fail.

[TG2-4](WIDE_PREVIEW_VALIDATION.md#follow-up-campaigns-tg2-3-aborted-and-tg2-4-retain)
measured a tree with spare frame storage. A preceding campaign, TG2-3, had stopped in
its A/A stage on external pointer input. Its own fresh 12-pair A/A stage planned
N = 12 jointly with memory v2: timing 0.861 joint power, memory 1.000 pass, no false
fails. All 56 groups and all 8 families passed, with an overall ratio of 0.973
(0.946–0.997). The family that was inconclusive in TG2-2 was 0.920 (0.859–0.983).
The decision was retain. The analyzer's `DEFAULT` control label describes builds
where that constant is disabled. An off/on campaign on a later build needs a declared
`DISABLED` control label.
