# Roadmap 9.1 paired protocol calibration — 2026-09-09

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Comparison infrastructure, not renderer optimization: A/B share compiled
`1a33e6f5f2e19213a4a980d4ede61a406293b83e`, identity
`03204a7d9dbcbbf4754756c0e4746ce171404d328e05499435602bdc43352a75`.
[Protocol](BASELINE_PAIRS_BENCHMARK.md) retains policies/manifests/matched samples/
order/uncertainty. Numerical production code unchanged.

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
measurement. Raw heap/GC data, per-process peak RSS and driver source copies
remain in the private campaign archive; launcher and analyzer hashes
match the recorded controller hashes.

## JavaFX calibration

Raw data: FX campaign (private archive: `baseline-9-1-pairs-fx-calibration-20260909/analysis.json`),
[declared policy](policies/9-1-pairs-fx-calibration.json).
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

Same-binary fresh JVMs vary: Seahorse FAST process median ratios 0.644, 0.966,
1.093; aggregate medians alone mislead. Neither target proves 10% gain. Analysis
retains first-publication/p95/process ratios. Small cases validate FX, not eventual
large ValidityMask publication gate.

## Large-frame headless calibration

Raw data: headless campaign (private archive: `baseline-9-1-pairs-validity-calibration-20260909/analysis.json`),
[declared policy](policies/9-1-validity-headless-calibration.json).
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

Reverse retains 5,939,136 pixels/zero new regions; stable ~6.5-second backend
is main slow target. No scheduling instrumentation here; attribution comes from
[separate diagnostic](BASELINE_SCHEDULING_VALIDATION.md).

Identical-binary Julia AA differs 8.2%; short first-tile/cancel intervals are wide:
**not** 5% equivalence. Inconclusive controls need a larger predeclared campaign,
especially fresh JVMs; never drop controls/relax limits after results.

Battery: FX 82%; large campaign 82% → 74%. All 24 bracketing thermal readings
`nominal`. Full-JVM peak RSS: FX 864,043,008 bytes, headless 4,038,737,920;
not per-scope, continuous thermal or memory-improvement evidence.

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

Both policies are `calibration`; keep `calibration_only`,
`production_promotion=not_evaluated`. 9.1 stays open until a real candidate passes
correctness/matched performance.

Next: bound `ValidityMask.missingRowSpans`/responsive planning cancellation,
preserve exact reuse and test full-mask/cancel. Use new predeclared candidate
policy against retained baseline. Post-first-region cancel is a control, not
planning latency. Full-app claims need input/publication/sustained-memory evidence.
