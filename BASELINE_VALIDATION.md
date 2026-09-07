# Roadmap 9.1 fixture validation

Recorded 2026-09-07 UTC (2026-09-06 local). Source: branch
`codex/roadmap-9-1-baseline-fixtures`, working-tree implementation on parent
`0c1b5a49fa1d9bf490cee9ed7085946ba9b0a57e`. The saved output explicitly labels
that working-tree state. This is validation of the measurement harness, not
an optimization comparison or a completed Retina performance baseline.

## Inputs and environment

The [canonical matrix](BASELINE_FIXTURES.csv) pins 28 sequences / 37 steps at
480x270, 1512x982 and 3024x1964 (111 manifest rows). Tests validate the full
manifest, iteration caps and supported backends without rendering the two
large matrices. The executed validation uses all 28 sequences at 480x270;
resize targets 608x342.

Hardware read through `sysctl`: Apple M3 Pro, 19,327,352,832 bytes RAM (18 GiB).
macOS 26.6.2, aarch64, 12 reported processors, 11 workers per production pool,
32-pixel tiles. The saved direct-JVM run uses Oracle/HotSpot Java 26.0.1+8-34
with a 4.5 GiB maximum heap, a 128 MiB AA cache and a 32 MiB reference cache.
AA instrumentation is off; no JFR/native profiler was attached. Maven tests
used OpenJDK 26.0.2. These different launch runtimes must not be treated as
matching performance controls; each launch records its actual VM.

Artifacts are preserved together:

- [Executed exact manifest](benchmarks/baseline-9-1-validation/manifest.csv)
- [Runtime, arguments and cache metadata](benchmarks/baseline-9-1-validation/environment.txt)
- [Raw samples](benchmarks/baseline-9-1-validation/samples.csv)
- [Per-scope summary](benchmarks/baseline-9-1-validation/summary.csv)

## Observed checks

One fresh JVM executes an initial cold-fixture sequence, one warmup and three
measured sequences per fixture. There are 185 step records: 37 cold, 37 warmup
and 111 measured. Of these, 175 completed normally and 10 exercised cancellation.
All 35 non-cancellation steps produced stable sample and ARGB hashes across
all five repetitions. Hash equality is repeatability evidence, not a proof of
conformance against a different implementation.

| Check | Observed result |
| --- | --- |
| Pan 5%, 25%, 90% | 123,120 / 97,200 / 12,960 pixels reused, matching 95% / 75% / 10% of 129,600 pixels |
| Direct → deep → reverse | Selected direct/deep/direct, with recalculated caps; every reverse recovered all 129,600 samples from the retained frame |
| Resize then drag | Resize reused all 129,600 original samples; drag reused 195,621 pixels from the completed resized frame |
| Cancellation | All ten requests reached the first-region trigger, returned incomplete sample frames and no ARGB result; cancellation tails were recorded separately |
| Scaled exponent | Production rendering completed at 1e-400; regression confirms c=2 and its immediate positive neighbor retain different escape iterations |
| Retained AA | Regression compares every output pixel with an empty-cache Julia refinement; preparation is excluded from the measured second-AA interval |

`mvn clean test` passed: 364 tests, zero failures/errors, 20 expected skips
(14 opt-in JavaFX tests and six native GPU tests). After adding the retained
reverse-navigation case, the final targeted `BaselineBenchmarkTest` run passed
all six tests. No production rendering code changed.

The summary script was exercised against the completed run, a duplicate-row
input and an incomplete-run input; the malformed inputs were rejected. A new
manifest run targeting the saved directory failed with `FileAlreadyExistsException`,
confirming that historical artifacts cannot be overwritten through the runner.

## What remains open

The matrix task in 9.1 is complete. Headless calculation, returned ARGB, AA and
post-first-region cancellation now share exact inputs and distinct scopes.
The remaining 9.1 work is to bring JavaFX base/AA publication and input tracing
onto matching inputs, extend allocation/task scheduling and cancellation-phase
profiles, and collect uninstrumented alternating controls with at least 30
measured pairs across multiple process starts plus sustained memory/thermal
evidence. Physical scanout remains unmeasured.

The three measured repetitions here are too few for a performance decision or
a useful tail estimate. See [the scope definitions and decision protocol](BASELINE_BENCHMARK.md).
