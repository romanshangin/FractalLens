# Benchmark protocols and selected evidence

Selected historical protocols/evidence preserve measurements/decisions. Most raw
observations/logs/exploration stay private; not every analysis replays publicly.
[Provenance/replay limits](../docs/development/PUBLICATION.md).

## Protocols for fresh measurements

- [Canonical workload matrix and headless protocol](BASELINE_BENCHMARK.md),
  [fixture definitions](BASELINE_FIXTURES.csv) and [initial validation](BASELINE_VALIDATION.md).
- [JavaFX publication](BASELINE_FX_BENCHMARK.md),
  [input/navigation](BASELINE_INPUT_BENCHMARK.md) and
  [scheduling](BASELINE_SCHEDULING_BENCHMARK.md).
- [Paired decision gates](BASELINE_PAIRS_BENCHMARK.md) and
  [allocation/sustained memory](BASELINE_MEMORY_BENCHMARK.md).
- [Formula benchmarks](FORMULA_BENCHMARK.md) and [basic benchmark entry point](BENCHMARK.md).
- [Prism pipeline comparison (ES2 and Metal)](PRISM_PIPELINE_BENCHMARK.md);
  [results](PRISM_PIPELINE_RESULTS.md),
  [sample/GC correlation diagnosis](PRISM_PIPELINE_RESULTS.md#desktop-sample-to-gc-correlation-diagnostic-2026-10-29),
  [fresh-frame decomposition](PRISM_PIPELINE_RESULTS.md#fresh-frame-planning-decomposition-diagnostic-2026-10-29),
  [rejected worker preparation experiment](PRISM_PIPELINE_RESULTS.md#bounded-worker-preparation-experiment-2026-10-29) and
  [Spaces presentation validation](SPACE_SWITCH_VALIDATION.md).

Fresh outputs/current `scripts/benchmarks/` drivers:
[scripts/validation](../scripts/README.md). Recorded paths/hashes stay historical.
Select `policies/` intentionally: rejected candidates coexist with final gates;
none implies accepted optimization. Required raw analysis output is not automatically
publishable.

## Graphics startup for new runs

`palette-benchmark`, `interaction-latency`, `gpu-render-benchmark` and cold startup
share production pre-toolkit bootstrap: macOS absent `prism.order` →`mtl,es2,sw`;
explicit/empty overrides win, other OSs keep defaults. Resident GPU is offscreen,
no FX. Current cold probe subclasses `FractalFxApplication`: both frozen builds
must contain it. Across old `FractalApplication` split use compatible probe/new
campaign, never current bytecode against incompatible build.

New `definition.json` records relative `ColdStartupBenchmark.class`/
`ColdStartupBenchmark$*.class` SHA-256 map `probe_files`.
`probe_hash_scope=class-family-v1`; `probe_sha256` hashes UTF-8 sorted-key JSON
(`json.dumps(probe_files, sort_keys=True)`). Check complete map before summary;
reject changed/added/deleted classes. Historical single-class hashes are never
relabeled/recomputed.

Historical ES2 comparisons require `-Dprism.order=es2`; paired Prism explicitly
selects both. `prism.verbose` proves actual initialization; requests/CSV do not.
Direct-FX tests/probes also select explicitly. Startup change reran/reclassified
no measurements. [Policy](../docs/platform/MACOS_RUN.md#javafx-startup-and-prism-selection).

## Final decisions and selected campaigns

| Decision | Report | Selected machine-readable evidence |
| --- | --- | --- |
| P0.3 bounded validity-mask candidate rejected | [Decision](VALIDITY_MASK_OPTIMIZATION.md) | [Headless analysis](baseline-p0-3-validity-headless-20260921/analysis.json), [JavaFX analysis](baseline-p0-3-validity-fx-desktop-20260921/analysis.json) |
| P1.2 on-demand snapshot preparation rejected | [Decision](RETAINED_SNAPSHOT_PREPARATION.md) | [Matched samples](RETAINED_SNAPSHOT_PREPARATION_SAMPLES.csv) |
| P1.3 queued publication rejected | [Decision](FX_PUBLICATION_P1_3.md) | [Analysis](p1-3-publication-20260924/coalescing-pairs/analysis.json), [probe 1](p1-3-publication-20260924/probe-1.csv), [probe 2](p1-3-publication-20260924/probe-2.csv), [probe 3](p1-3-publication-20260924/probe-3.csv) |
| P1.4 allocation attribution; no optimization promoted | [Profile](ALLOCATION_PROFILE_9_4.md) | [Summary](p1-4-allocation-profile-20260924/summary.json), [allocation groups](p1-4-allocation-profile-20260924/allocations.json) |
| P1.5 retained CPU AA accepted with recorded first-tile exception | [Assessment](P1_5_PROMOTION_VALIDATION.md) | [Sustained summary](p1-5-aa-sustained-20260924/summary.json), [headless](p1-5-retained-vs-pre-headless-20260927/analysis.json), [JavaFX](p1-5-retained-vs-pre-fx-retry-20260927/analysis.json), [base confirmation](p1-5-retained-vs-pre-fx-base-confirm-20260928/analysis.json), [AC cold startup](p1-5-retained-vs-pre-cold-ac-20260928/summary.json) |

Selected campaigns contain retained summaries/provenance only; omitted-input
references do not imply availability. Historical failures/rejected candidates/
incomplete platform gates remain recorded.

## Other retained numerical evidence

- [FP32 precision results](GPU_FP32_PRECISION_RESULTS.csv) and
  [native FP32 results](GPU_FP32_NATIVE_RESULTS.csv).
- Interaction [pan summary](INTERACTION_LATENCY_PAN_CONTROL_SUMMARY.csv),
  [screen summary](INTERACTION_LATENCY_SCREEN_SUMMARY.csv),
  [zoom summary](INTERACTION_LATENCY_ZOOM_CONTROL_SUMMARY.csv) and
  [validity-scan measurements](INTERACTION_LATENCY_VALIDITY_SCAN.csv).
- [Canonical executed manifest](baseline-9-1-validation/manifest.csv),
  [baseline summary](baseline-9-1-validation/summary.csv) and
  [fixed JavaFX summary](baseline-9-1-fx-fixed-20260907/summary.csv).

## Retained file inventory

The inventory lists the 121 selected evidence/protocol files, excluding this index. No
raw log file is included.

- [ALLOCATION_PROFILE_9_4.md](ALLOCATION_PROFILE_9_4.md)
- [BASELINE_BENCHMARK.md](BASELINE_BENCHMARK.md)
- [BASELINE_FIXTURES.csv](BASELINE_FIXTURES.csv)
- [BASELINE_FX_BENCHMARK.md](BASELINE_FX_BENCHMARK.md)
- [BASELINE_FX_VALIDATION.md](BASELINE_FX_VALIDATION.md)
- [BASELINE_INPUT_BENCHMARK.md](BASELINE_INPUT_BENCHMARK.md)
- [BASELINE_INPUT_VALIDATION.md](BASELINE_INPUT_VALIDATION.md)
- [BASELINE_MEMORY_BENCHMARK.md](BASELINE_MEMORY_BENCHMARK.md)
- [BASELINE_MEMORY_VALIDATION.md](BASELINE_MEMORY_VALIDATION.md)
- [BASELINE_PAIRS_BENCHMARK.md](BASELINE_PAIRS_BENCHMARK.md)
- [BASELINE_PAIRS_VALIDATION.md](BASELINE_PAIRS_VALIDATION.md)
- [BASELINE_SCHEDULING_BENCHMARK.md](BASELINE_SCHEDULING_BENCHMARK.md)
- [BASELINE_SCHEDULING_VALIDATION.md](BASELINE_SCHEDULING_VALIDATION.md)
- [BASELINE_VALIDATION.md](BASELINE_VALIDATION.md)
- [BENCHMARK.md](BENCHMARK.md)
- [BENCHMARK_RESULTS.md](BENCHMARK_RESULTS.md)
- [CPU_AA_OPTIMIZATION_RESULTS.md](CPU_AA_OPTIMIZATION_RESULTS.md)
- [CPU_GPU_OPTIMIZATION_ANALYSIS.md](CPU_GPU_OPTIMIZATION_ANALYSIS.md)
- [FORMULA_BENCHMARK.md](FORMULA_BENCHMARK.md)
- [FORMULA_BENCHMARK_RESULTS.md](FORMULA_BENCHMARK_RESULTS.md)
- [FX_PUBLICATION_P1_3.md](FX_PUBLICATION_P1_3.md)
- [GPU_FP32_NATIVE.md](GPU_FP32_NATIVE.md)
- [GPU_FP32_NATIVE_RESULTS.csv](GPU_FP32_NATIVE_RESULTS.csv)
- [GPU_FP32_PRECISION.md](GPU_FP32_PRECISION.md)
- [GPU_FP32_PRECISION_RESULTS.csv](GPU_FP32_PRECISION_RESULTS.csv)
- [GPU_RENDER_BENCHMARK_8_5_RESULTS.md](GPU_RENDER_BENCHMARK_8_5_RESULTS.md)
- [GPU_RENDER_BENCHMARK_RESULTS.md](GPU_RENDER_BENCHMARK_RESULTS.md)
- [GPU_RESIDENCY_8_6_DECISION.md](GPU_RESIDENCY_8_6_DECISION.md)
- [GPU_RESIDENT_SPIKE_RESULTS.md](GPU_RESIDENT_SPIKE_RESULTS.md)
- [INTERACTION_LATENCY.md](INTERACTION_LATENCY.md)
- [INTERACTION_LATENCY_PAN_CONTROL_SUMMARY.csv](INTERACTION_LATENCY_PAN_CONTROL_SUMMARY.csv)
- [INTERACTION_LATENCY_RESULTS.md](INTERACTION_LATENCY_RESULTS.md)
- [INTERACTION_LATENCY_SCREEN_SUMMARY.csv](INTERACTION_LATENCY_SCREEN_SUMMARY.csv)
- [INTERACTION_LATENCY_VALIDITY_SCAN.csv](INTERACTION_LATENCY_VALIDITY_SCAN.csv)
- [INTERACTION_LATENCY_ZOOM_CONTROL_SUMMARY.csv](INTERACTION_LATENCY_ZOOM_CONTROL_SUMMARY.csv)
- [P1_5_PROMOTION_VALIDATION.md](P1_5_PROMOTION_VALIDATION.md)
- [PALETTE_BENCHMARK_RESULTS.md](PALETTE_BENCHMARK_RESULTS.md)
- [PRISM_PIPELINE_BENCHMARK.md](PRISM_PIPELINE_BENCHMARK.md)
- [PRISM_PIPELINE_RESULTS.md](PRISM_PIPELINE_RESULTS.md)
- [RETAINED_SNAPSHOT_PREPARATION.md](RETAINED_SNAPSHOT_PREPARATION.md)
- [RETAINED_SNAPSHOT_PREPARATION_SAMPLES.csv](RETAINED_SNAPSHOT_PREPARATION_SAMPLES.csv)
- [SERIES_APPROXIMATION_RESULTS.md](SERIES_APPROXIMATION_RESULTS.md)
- [SPACE_SWITCH_VALIDATION.md](SPACE_SWITCH_VALIDATION.md)
- [VALIDITY_MASK_OPTIMIZATION.md](VALIDITY_MASK_OPTIMIZATION.md)
- [baseline-9-1-fx-fixed-20260907/summary.csv](baseline-9-1-fx-fixed-20260907/summary.csv)
- [baseline-9-1-validation/manifest.csv](baseline-9-1-validation/manifest.csv)
- [baseline-9-1-validation/summary.csv](baseline-9-1-validation/summary.csv)
- [baseline-p0-3-validity-fx-desktop-20260921/analysis.json](baseline-p0-3-validity-fx-desktop-20260921/analysis.json)
- [baseline-p0-3-validity-fx-desktop-20260921/build-A.json](baseline-p0-3-validity-fx-desktop-20260921/build-A.json)
- [baseline-p0-3-validity-fx-desktop-20260921/build-B.json](baseline-p0-3-validity-fx-desktop-20260921/build-B.json)
- [baseline-p0-3-validity-fx-desktop-20260921/environment.json](baseline-p0-3-validity-fx-desktop-20260921/environment.json)
- [baseline-p0-3-validity-fx-desktop-20260921/policy.json](baseline-p0-3-validity-fx-desktop-20260921/policy.json)
- [baseline-p0-3-validity-headless-20260921/analysis.json](baseline-p0-3-validity-headless-20260921/analysis.json)
- [baseline-p0-3-validity-headless-20260921/build-A.json](baseline-p0-3-validity-headless-20260921/build-A.json)
- [baseline-p0-3-validity-headless-20260921/build-B.json](baseline-p0-3-validity-headless-20260921/build-B.json)
- [baseline-p0-3-validity-headless-20260921/environment.json](baseline-p0-3-validity-headless-20260921/environment.json)
- [baseline-p0-3-validity-headless-20260921/policy.json](baseline-p0-3-validity-headless-20260921/policy.json)
- [p1-3-publication-20260924/coalescing-pairs/analysis.json](p1-3-publication-20260924/coalescing-pairs/analysis.json)
- [p1-3-publication-20260924/coalescing-pairs/build-A.json](p1-3-publication-20260924/coalescing-pairs/build-A.json)
- [p1-3-publication-20260924/coalescing-pairs/build-B.json](p1-3-publication-20260924/coalescing-pairs/build-B.json)
- [p1-3-publication-20260924/coalescing-pairs/environment.json](p1-3-publication-20260924/coalescing-pairs/environment.json)
- [p1-3-publication-20260924/coalescing-pairs/policy.json](p1-3-publication-20260924/coalescing-pairs/policy.json)
- [p1-3-publication-20260924/probe-1.csv](p1-3-publication-20260924/probe-1.csv)
- [p1-3-publication-20260924/probe-2.csv](p1-3-publication-20260924/probe-2.csv)
- [p1-3-publication-20260924/probe-3.csv](p1-3-publication-20260924/probe-3.csv)
- [p1-4-allocation-profile-20260924/allocations.json](p1-4-allocation-profile-20260924/allocations.json)
- [p1-4-allocation-profile-20260924/environment.json](p1-4-allocation-profile-20260924/environment.json)
- [p1-4-allocation-profile-20260924/source-sha256.json](p1-4-allocation-profile-20260924/source-sha256.json)
- [p1-4-allocation-profile-20260924/summary.json](p1-4-allocation-profile-20260924/summary.json)
- [p1-5-aa-sustained-20260924/environment.json](p1-5-aa-sustained-20260924/environment.json)
- [p1-5-aa-sustained-20260924/source-sha256.json](p1-5-aa-sustained-20260924/source-sha256.json)
- [p1-5-aa-sustained-20260924/summary.json](p1-5-aa-sustained-20260924/summary.json)
- [p1-5-retained-vs-pre-cold-ac-20260928/definition.json](p1-5-retained-vs-pre-cold-ac-20260928/definition.json)
- [p1-5-retained-vs-pre-cold-ac-20260928/summary.json](p1-5-retained-vs-pre-cold-ac-20260928/summary.json)
- [p1-5-retained-vs-pre-fx-base-confirm-20260928/analysis.json](p1-5-retained-vs-pre-fx-base-confirm-20260928/analysis.json)
- [p1-5-retained-vs-pre-fx-base-confirm-20260928/build-A.json](p1-5-retained-vs-pre-fx-base-confirm-20260928/build-A.json)
- [p1-5-retained-vs-pre-fx-base-confirm-20260928/build-B.json](p1-5-retained-vs-pre-fx-base-confirm-20260928/build-B.json)
- [p1-5-retained-vs-pre-fx-base-confirm-20260928/environment.json](p1-5-retained-vs-pre-fx-base-confirm-20260928/environment.json)
- [p1-5-retained-vs-pre-fx-base-confirm-20260928/policy.json](p1-5-retained-vs-pre-fx-base-confirm-20260928/policy.json)
- [p1-5-retained-vs-pre-fx-retry-20260927/analysis.json](p1-5-retained-vs-pre-fx-retry-20260927/analysis.json)
- [p1-5-retained-vs-pre-fx-retry-20260927/build-A.json](p1-5-retained-vs-pre-fx-retry-20260927/build-A.json)
- [p1-5-retained-vs-pre-fx-retry-20260927/build-B.json](p1-5-retained-vs-pre-fx-retry-20260927/build-B.json)
- [p1-5-retained-vs-pre-fx-retry-20260927/environment.json](p1-5-retained-vs-pre-fx-retry-20260927/environment.json)
- [p1-5-retained-vs-pre-fx-retry-20260927/policy.json](p1-5-retained-vs-pre-fx-retry-20260927/policy.json)
- [p1-5-retained-vs-pre-headless-20260927/analysis.json](p1-5-retained-vs-pre-headless-20260927/analysis.json)
- [p1-5-retained-vs-pre-headless-20260927/build-A.json](p1-5-retained-vs-pre-headless-20260927/build-A.json)
- [p1-5-retained-vs-pre-headless-20260927/build-B.json](p1-5-retained-vs-pre-headless-20260927/build-B.json)
- [p1-5-retained-vs-pre-headless-20260927/environment.json](p1-5-retained-vs-pre-headless-20260927/environment.json)
- [p1-5-retained-vs-pre-headless-20260927/policy.json](p1-5-retained-vs-pre-headless-20260927/policy.json)
- [policies/9-1-pairs-fx-calibration.json](policies/9-1-pairs-fx-calibration.json)
- [policies/9-1-validity-headless-calibration.json](policies/9-1-validity-headless-calibration.json)
- [policies/9-3-publication-coalescing-fx.json](policies/9-3-publication-coalescing-fx.json)
- [policies/9-3-validity-fx-candidate.json](policies/9-3-validity-fx-candidate.json)
- [policies/9-3-validity-fx-p0-3.json](policies/9-3-validity-fx-p0-3.json)
- [policies/9-3-validity-headless-candidate.json](policies/9-3-validity-headless-candidate.json)
- [policies/9-3-validity-headless-p0-3.json](policies/9-3-validity-headless-p0-3.json)
- [policies/p1-5-aa-retained-b2-fx.json](policies/p1-5-aa-retained-b2-fx.json)
- [policies/p1-5-aa-retained-b2-headless.json](policies/p1-5-aa-retained-b2-headless.json)
- [policies/p1-5-aa-retained-c-fx.json](policies/p1-5-aa-retained-c-fx.json)
- [policies/p1-5-aa-retained-c-headless.json](policies/p1-5-aa-retained-c-headless.json)
- [policies/p1-5-aa-retained-d-confirm-fx.json](policies/p1-5-aa-retained-d-confirm-fx.json)
- [policies/p1-5-aa-retained-d-fx.json](policies/p1-5-aa-retained-d-fx.json)
- [policies/p1-5-aa-retained-d-headless.json](policies/p1-5-aa-retained-d-headless.json)
- [policies/p1-5-aa-retained-e-fx-confirm.json](policies/p1-5-aa-retained-e-fx-confirm.json)
- [policies/p1-5-aa-retained-e-fx-screen.json](policies/p1-5-aa-retained-e-fx-screen.json)
- [policies/p1-5-aa-retained-e-headless.json](policies/p1-5-aa-retained-e-headless.json)
- [policies/p1-5-aa-retained-f-fx-confirm.json](policies/p1-5-aa-retained-f-fx-confirm.json)
- [policies/p1-5-aa-retained-f-fx-screen.json](policies/p1-5-aa-retained-f-fx-screen.json)
- [policies/p1-5-aa-retained-f-headless.json](policies/p1-5-aa-retained-f-headless.json)
- [policies/p1-5-aa-retained-fx.json](policies/p1-5-aa-retained-fx.json)
- [policies/p1-5-aa-retained-g-fx-confirm.json](policies/p1-5-aa-retained-g-fx-confirm.json)
- [policies/p1-5-aa-retained-g-fx-screen.json](policies/p1-5-aa-retained-g-fx-screen.json)
- [policies/p1-5-aa-retained-g-headless.json](policies/p1-5-aa-retained-g-headless.json)
- [policies/p1-5-aa-retained-headless.json](policies/p1-5-aa-retained-headless.json)
- [policies/p1-5-retained-vs-pre-fx-base-confirm.json](policies/p1-5-retained-vs-pre-fx-base-confirm.json)
- [policies/p1-5-retained-vs-pre-fx.json](policies/p1-5-retained-vs-pre-fx.json)
- [policies/p1-5-retained-vs-pre-headless.json](policies/p1-5-retained-vs-pre-headless.json)
- [policies/p17-fresh-frame-preparation.json](policies/p17-fresh-frame-preparation.json)
- [policies/prism-pipeline-fx-confirm.json](policies/prism-pipeline-fx-confirm.json)
- [policies/prism-pipeline-fx.json](policies/prism-pipeline-fx.json)
- [policies/prism-pipeline-latency-confirm.json](policies/prism-pipeline-latency-confirm.json)
- [policies/prism-pipeline-latency.json](policies/prism-pipeline-latency.json)
