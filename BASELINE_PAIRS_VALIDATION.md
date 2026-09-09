# Roadmap 9.1 paired protocol calibration — 2026-09-09

This step adds a reproducible comparison mechanism, not a renderer optimization.
Both A and B use the same compiled snapshot of
`1a33e6f5f2e19213a4a980d4ede61a406293b83e`, identity
`03204a7d9dbcbbf4754756c0e4746ce171404d328e05499435602bdc43352a75`.
The [protocol](BASELINE_PAIRS_BENCHMARK.md) keeps policies, build manifests, raw
matching samples, process order and uncertainty together. Numerical production
code is unchanged in this step.

## Environment and design

Apple M3 Pro, 12 processors, macOS 26.6.2, OpenJDK 26.0.2,
JavaFX 26.0.2+3, 4 GiB maximum heap, 11 workers per pool. Builds were prepared
before the campaigns. Each campaign uses AB, BA, AB fresh process pairs with
10 measured sequences per side per process, plus three warmups and one cold
sequence. There are 30 matched samples for every declared metric group.

JFR, native-memory tracking, AA/scheduling instrumentation and GPU computation
are disabled. The campaigns ran sequentially; this task ran no tests or builds
during timing. Power
and thermal observations bracket each JVM; they are not a continuous thermal
measurement. Raw heap/GC data and per-process peak RSS are retained. Driver
source copies are saved in each result directory; launcher and analyzer hashes
match the recorded controller hashes.

## JavaFX calibration

Raw data: [FX campaign](benchmarks/baseline-9-1-pairs-fx-calibration-20260909/analysis.json),
[declared policy](benchmarks/policies/9-1-pairs-fx-calibration.json).
Two fixtures at 480x270 render pixels, both FAST and REFINED: eight metric
groups and 240 metric pairs. All six JVMs completed their exact-control
validation and wrote `SUCCESS`; the campaign validator passed. ES2 selected
the Apple M3 Pro renderer. Output scale was 1.0x1.0.

| Full publication | A median ms | B median ms | Median paired B/A | Bootstrap interval |
| --- | ---: | ---: | ---: | ---: |
| Seahorse FAST | 6.306 | 5.144 | 0.936 | 0.603–1.178 |
| Seahorse REFINED | 4.196 | 4.602 | 0.999 | 0.888–1.275 |
| Julia AA FAST | 26.641 | 26.728 | 0.972 | 0.909–1.117 |
| Julia AA REFINED | 25.514 | 27.366 | 1.067 | 0.999–1.100 |

Short publication intervals vary substantially across fresh processes on the
same binary. For example, Seahorse FAST per-process median ratios are 0.644,
0.966 and 1.093. The overall A and B medians alone would give a misleading
impression of improvement. Neither declared target establishes a 10% gain.
First-publication timings, p95 values and all per-process ratios are retained
in the analysis. These small fixtures validate the FX path; they do not qualify
as the eventual large-frame ValidityMask publication gate.

## Large-frame headless calibration

Raw data: [headless campaign](benchmarks/baseline-9-1-pairs-validity-calibration-20260909/analysis.json),
[declared policy](benchmarks/policies/9-1-validity-headless-calibration.json).
Five sequences / eight steps at 3024x1964 render pixels: 11 metric groups and
330 metric pairs. Six JVMs produced 672 cold/warmup/measured rows; coverage,
runtime, workload and non-cancelled fingerprint checks all passed.
The combined JVM wall time was 1,634.44 seconds (27.24 minutes).

| Scope | A median ms | B median ms | Median paired B/A | Bootstrap interval |
| --- | ---: | ---: | ---: | ---: |
| Fully reused reverse: backend | 6495.942 | 6500.762 | 1.001 | 1.0001–1.0022 |
| Fully reused reverse: returned ARGB | 6552.422 | 6580.517 | 1.003 | 0.999–1.012 |
| Direct frame: returned ARGB | 2917.188 | 2894.930 | 1.005 | 0.980–1.017 |
| Deep frame: returned ARGB | 7720.146 | 7893.229 | 1.014 | 0.988–1.023 |
| Overview: returned ARGB | 62.207 | 62.766 | 1.011 | 0.975–1.044 |
| Overview: first new region | 5.422 | 4.985 | 0.909 | 0.766–1.196 |
| Julia AA: operation | 991.653 | 1095.985 | 1.082 | 1.017–1.198 |
| Julia AA: first AA tile | 12.721 | 19.983 | 1.487 | 0.737–2.397 |
| Pan source: returned ARGB | 219.302 | 220.071 | 1.009 | 0.937–1.046 |
| Pan: returned ARGB | 47.276 | 50.857 | 1.110 | 0.850–1.295 |
| Cancellation after first region: tail | 0.360 | 0.359 | 1.097 | 0.830–3.064 |

The retained reverse frame reuses all 5,939,136 pixels and creates zero new
backend regions. Its roughly 6.5-second backend cost is stable enough here to
serve as the principal slow target. This run has no scheduling instrumentation;
the attribution to mask scanning comes from the separate
[scheduling diagnostic](BASELINE_SCHEDULING_VALIDATION.md).

Several controls remain noisy. Julia AA operation differs by 8.2% in the paired
estimate even though the binaries are identical, and short first-tile/cancel
timings have broad intervals. This calibration therefore does **not** establish
5% control equivalence. A candidate with inconclusive controls needs a new,
larger predeclared campaign, especially more fresh process pairs; it must not
drop these controls or relax their thresholds after observing results.

Both campaigns ran on battery: 82% throughout the short FX campaign and 82% to
74% across the large-frame campaign. All 24 before/after thermal observations
were `nominal`. Peak process RSS was 864,043,008 bytes for FX and 4,038,737,920
bytes for headless. These peaks include each entire JVM and cannot be assigned
to a single timer scope. No claim of continuous thermal stability or improved
memory use follows from these observations.

## Verification and decision boundary

`python3 -m unittest discover -s scripts -p 'test_summarize_baseline*.py'`
passed all 38 tests, including 16 paired/snapshot tests. They cover missing
pairs/phases, changed launch order, altered policy, mismatched JDK/heap,
instrumented or failed JVMs, non-finite/missing timings, changed fingerprints,
mutated compiled files, process-cluster uncertainty and the distinction between
a timing pass and production acceptance. Both live campaign validators passed;
the six FX JVMs also passed their exact-control validation.

A live competing-process lock attempt was rejected while the headless campaign
held its lease. This validates the parent campaign lock independently of the
per-JVM private lock. Programs using unrelated lock paths remain outside that
coordination.

Both campaign policies are explicitly `calibration`. Their result must remain
`calibration_only`, with `production_promotion=not_evaluated`. The final roadmap
9.1 decision item remains open until a real candidate is compared and passes its
correctness and scope-matched performance checks.

Next implementation: bound `ValidityMask.missingRowSpans` scanning and make
planning cancellation responsive. Preserve exact reuse semantics and test the
reported complete-mask/cancellation cases. Compare the committed candidate with
the retained baseline through a new, predeclared candidate policy. The existing
post-first-region cancellation timing is a control, not a measurement of
cancellation during planning. Input/publication and sustained-memory evidence
must accompany any claim about the full application.
