# Roadmap 9.1 scheduling validation — 2026-09-08

The new opt-in diagnostics identify a coordinator-side cancellation bottleneck:
a fully reused 3024x1964 direct frame spends about five seconds scanning its
validity mask while creating **zero worker tasks**. Cancelling during that
planning scan takes about five seconds to drain. This step measures and locates
the problem; it does not change the mask algorithm or claim a speedup.

Branch: `codex/roadmap-9-1-scheduling-diagnostics`, based on `7f3b0e2`.
All accepted datasets describe that base plus the scheduling working tree and
include source SHA-256 hashes. Raw CSVs are preserved; saved log trailing
whitespace is normalized for Git. See [the measurement contract](BASELINE_SCHEDULING_BENCHMARK.md).

## Runtime and limits

Apple M3 Pro, macOS 26.6.2, aarch64, 12 reported processors, 11 workers per
backend pool, `-Xmx4g`. The three dataset JVMs used `/usr/bin/java`, resolving to
Oracle Java **26.0.1+8-34** at
`/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home`.
Maven 3.9.16 compilation and JUnit runs used Homebrew OpenJDK **26.0.2**.
This differs from the earlier 26.0.2 input baselines: these datasets must not
be treated as paired timing comparisons with them. Reproduction should choose
an explicit Java executable and record it with the run.

The input run used JavaFX 26.0.2+3, 2x output scale, native
`com.sun.prism.es2.ES2Pipeline`, Apple M3 Pro renderer and vsync, verified in its
[launch log](benchmarks/baseline-9-1-scheduling-input-20260908/launch.log).
GPU fractal calculation was disabled. Suites ran sequentially, without a
concurrent rendering benchmark or JFR/native profiler. Background activity,
thermal state and OS/physical display latency were not measured. Diagnostic
clocks, locks and task wrappers were enabled, so these are attribution and
conformance runs, not 30-pair optimization gates.

## Canonical CPU base matrix

[480x270 data](benchmarks/baseline-9-1-scheduling-final-20260908/samples.csv),
[raw diagnostics](benchmarks/baseline-9-1-scheduling-final-20260908/diagnostics.csv),
[derived intervals](benchmarks/baseline-9-1-scheduling-final-20260908/scheduling-summary.csv),
[environment](benchmarks/baseline-9-1-scheduling-final-20260908/environment.txt).

Six fixtures cover seahorse, direct/deep/reverse, 25% pan, resize/drag and direct
and deep cancellation. Their 11 steps produce **44 validated requests**: one
first sequence and three samples, no warmups. Every ready sample, including
partial cancelled frames, passes an exact CPU control after worker drain.
This runner measures base calculation only; it does not run fixture AA.

Three-sample diagnostic medians for selected cases:

| Case | Planning ms | Backend ms | Mask scan ms | Cancel → drain ms |
|---|---:|---:|---:|---:|
| seahorse-fixed | 0.131 | 9.315 | absent | absent |
| fully reused reverse | 2.540 | 2.584 | 2.382 | absent |
| pan-25 target | 0.107 | 0.458 | 0.026 | absent |
| cancel-direct, first backend region | 0.066 | 0.683 | absent | 0.233 |
| cancel-deep, first backend region | 0.085 | 1.490 | absent | 0.103 |

These are overlapping scopes and a small sample, not established distribution
tails. In particular, first-region cancellation excludes time spent preparing
that first region. It cannot diagnose cancellation during a long planning scan.

## Fully reused Retina frame and cancellation during planning

[3024x1964 data](benchmarks/baseline-9-1-scheduling-retina-final-20260908/samples.csv),
[raw diagnostics](benchmarks/baseline-9-1-scheduling-retina-final-20260908/diagnostics.csv),
[derived intervals](benchmarks/baseline-9-1-scheduling-retina-final-20260908/scheduling-summary.csv).

One first sequence and one sample run the canonical direct/deep/reverse fixture.
After each fully reused reverse, an additional request is cancelled after
planning starts. All **eight requests** pass sample controls and strict drain
validation. Reverse and planning-cancel requests create zero worker tasks.

| Request | Phase | Planning ms | Mask scan ms | Cancel → drain ms |
|---|---|---:|---:|---:|
| reverse | first sequence | 5520.573 | 5511.436 | absent |
| reverse | sample | 5018.881 | 5014.988 | absent |
| same frame, cancel during planning | first sequence | 5387.384 | 5379.481 | 5385.815 |
| same frame, cancel during planning | sample | 5048.955 | 5045.269 | 5047.843 |

More than 99.8% of the normal reverse planning interval is inside the accumulated
`missingRowSpans` calls. That method calls `BitSet.nextClearBit` without a row
bound; repeated searches on a completely ready frame walk beyond each requested
row/region. Task construction does not check cancellation inside its tile loop.
The resulting delay is in the coordinator, before task submission. Changing
worker count or waiting only for cancelled Futures would not explain it.

This is the canonical exact reverse-cache case. Actual pinch navigation can
round its target or bypass base submission, as recorded by the input manifest.
The result therefore does not mean every real zoom or pan incurs five seconds.

## Real input and generation identity

[Input data](benchmarks/baseline-9-1-scheduling-input-20260908/samples.csv),
[raw trace](benchmarks/baseline-9-1-scheduling-input-20260908/events.csv),
[actual jobs](benchmarks/baseline-9-1-scheduling-input-20260908/actual-manifest.csv),
[input summary](benchmarks/baseline-9-1-scheduling-input-20260908/summary.csv),
[scheduling summary](benchmarks/baseline-9-1-scheduling-input-20260908/scheduling-summary.csv).

Five fixtures cover wheel/pinch, pan-25, resize/drag and direct/deep replacement,
in both requested Fast/Refined modes. One first sequence and one sample give
**28 completed trials and 36 drained base requests**. Every seed and final frame
passes the existing exact sample/ARGB controls. Both strict validators pass;
each submitted render has its original epoch/input/render diagnostic snapshot.

Sample replacement cancel → drain observations are 0.203/0.264 ms for direct
Fast/Refined and 0.013/0.009 ms for deep Fast/Refined. The replaced deep requests
exit before task planning. These single observations describe that early-input
trigger, not cancellation after heavy deep work or AA worker drain. The final
input still completes and publishes independently of the cancelled generation.

Together the accepted datasets contain **80 trials and 88 drained requests**,
excluding untimed seed/control work. Canonical manifests still match `9.1-v1`.

## Regression and validation evidence

- Final `mvn clean test`: **394 tests, zero failures/errors, 37 skipped**.
  [Saved log](benchmarks/baseline-9-1-scheduling-final-20260908/clean-test.log).
- Opt-in graphical `BaselineInputBenchmarkTest`: **16 tests passed**, including
  replacement identity and exact real-handler output. Its deliberately injected
  asynchronous timer failure is expected regression output.
  [Saved log](benchmarks/baseline-9-1-scheduling-input-20260908/fx-tests.log).
- Python validators: **11 tests passed**. All three final datasets also pass
  the scheduling validator; the input dataset passes the full input validator.
- New deterministic regressions hold an interrupted worker alive after backend
  exit; cancel a queued worker/coordinator; keep superseded generations separate;
  verify the disabled path and successful real backend accounting; reject
  external completion and duplicate task runs; and check that completion
  callbacks run outside the diagnostic monitor.
- Recorder regressions reject premature stop and late events from a prior epoch.
  Strict interval validation rejects incomplete accounting and reversed clocks.

Development probes in `baseline-9-1-scheduling-20260908` and
`baseline-9-1-scheduling-retina-20260908` are explicitly failed/superseded and have
no `SUCCESS` marker. The first probe exposed cancellation observation preceding
its request timestamp because generation invalidation happened first. The mark
now precedes invalidation, and all accepted runs were repeated after that fix.

The next implementation target is recorded at the start of 9.3: bounded mask
scans and cancellation checks during preparation, with exact reuse/mask tests
and an uninstrumented paired gate. Remaining 9.1 work is allocation attribution
and sustained memory/thermal measurement; this diagnostic step does not close
those items or establish an optimization gain.
