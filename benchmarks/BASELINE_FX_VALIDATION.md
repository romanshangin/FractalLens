# Roadmap 9.1 JavaFX baseline validation

> Historical evidence: results and “current source” below refer to the dated
> experiment, not a new validation of this checkout. Only the
> [selected evidence](README.md) is included; entries marked “private archive”
> are unavailable in this repository. Original SHA values and recorded commands
> describe the original run and may require private revisions or inputs. See
> [provenance and replay limits](../PUBLICATION.md).

Date: 2026-09-07. Work starts from `0c83aa7` on
`codex/roadmap-9-1-fx-baselines`. Saved runs identify their working-tree state
explicitly. These are fixture/scope validation runs with three measured
repetitions, not a 30-pair optimization decision.

## Hardware, runtime and inputs

Apple M3 Pro, 18 GiB RAM (19,327,352,832 bytes from `sysctl`), macOS 26.6.2,
aarch64, 12 reported processors, 11 workers per production pool. Both saved
runners use the same Homebrew OpenJDK 26.0.2 executable:
`/opt/homebrew/Cellar/openjdk/26.0.2/libexec/openjdk.jdk/Contents/Home/bin/java`.
Both use `-Xmx4g` and the modular launch documented in
[the JavaFX benchmark](BASELINE_FX_BENCHMARK.md). JavaFX is 26.0.2 at 2x output
scale. The graphics log reports the native `com.sun.prism.es2.ES2Pipeline`.
Calculation remains CPU-only; an accelerated JavaFX pipeline does not imply
GPU fractal calculation.

Both runners use every `9.1-v1` fixture at 480x270, 1512x982 and 3024x1964.
Their manifests are checked against the exact canonical file, with no fitted
viewport or substituted iteration cap. Each fixture has a cold sequence,
one warmup and three measured sequences. JavaFX additionally runs each requested
Fast/Refined mode and records the effective presentation mode.

The headless run completed first; JavaFX runs started only after it exited.
No JFR/native profiler or AA stage instrumentation was enabled. JavaFX startup
diagnostics (`prism.verbose`) were enabled to establish the actual pipeline;
these validation timings are not an uninstrumented performance gate. Background
system activity and power/thermal state were not controlled or measured.

## Failure found by the long run

The first JavaFX run exhausted a 4 GiB heap after writing 450 step rows, during
same-grid control allocation for `seahorse-adaptive` / requested Refined at
1512x982. The runner repeatedly replaces surfaces in one window. Inspection
showed that removed `FractalSurface` objects stayed strongly referenced by their
old `Scene.windowProperty` and `Window.outputScaleX/YProperty` listeners. Each
surface could therefore retain its render frames and image buffers.

Two targeted regressions reproduced the cause before the fix:

- A detached surface still received a callback when its old scene was moved
  between windows (expected zero callbacks, observed one).
- A weak reference to a detached surface could not clear after pulse settlement
  and explicit collection while the original window remained alive.

The fix retains listener identities and removes both scene and window
subscriptions on detach/window change. Reattachment restores one active
subscription. The renderer's calculation, AA and presentation policies are
unchanged. Both regressions pass with the fix.

The failed run remains private diagnostic evidence, including
its environment (private archive: `baseline-9-1-fx-20260907/environment.txt`),
partial rows (private archive: `baseline-9-1-fx-20260907/samples.csv`),
OOM log (private archive: `baseline-9-1-fx-20260907/launch.log`) and
failing regression output (private archive: `baseline-9-1-fx-20260907/listener-regressions-before.log`).
It has no `SUCCESS` marker and must not enter baseline summaries or decisions.
The repeat completed the whole matrix using the same 4 GiB limit in a new output
directory. Its after-step used-heap observations range from about 0.016 to
2.729 GiB; these are not peak allocation, live-retained-size or RSS measurements.

## Completed datasets and measured scopes

- Headless raw data (private archive: `baseline-9-1-headless-20260907/samples.csv`),
  summary (private archive: `baseline-9-1-headless-20260907/summary.csv`),
  environment (private archive: `baseline-9-1-headless-20260907/environment.txt`):
  555 rows, including 525 completed frames and 30 cancellation cases.
- JavaFX raw data after the fix (private archive: `baseline-9-1-fx-fixed-20260907/samples.csv`),
  [summary](baseline-9-1-fx-fixed-20260907/summary.csv),
  environment (private archive: `baseline-9-1-fx-fixed-20260907/environment.txt`),
  graphics/launch log (private archive: `baseline-9-1-fx-fixed-20260907/launch.log`):
  1,110 rows, including 1,050 completed frames and 60 cancellation cases.
- Conformance marker (private archive: `baseline-9-1-fx-fixed-20260907/SUCCESS`):
  all 210 non-cancelled final-sequence steps passed per-sample and per-ARGB-pixel
  comparison against fresh same-grid CPU controls. All 1,050 completed FX rows
  also match the corresponding headless sample/ARGB fingerprints across modes
  and repetitions. Both manifests match the canonical 111-row manifest exactly.

The headless data remain valid after the listener fix: that process does not
instantiate `FractalSurface`, and its calculation/coloring/AA implementations
were not changed. These sequential datasets are not alternating build pairs.

Illustrative 3024x1964 sample medians in milliseconds, **n=3 per row**:

| Fixture / requested mode | Backend call | First visible software publication | Full software publication |
| --- | ---: | ---: | ---: |
| Mandelbrot overview / Fast | 42.66 | 2.93 | 73.93 |
| Julia regular AA / Fast | 88.98 | 19.13 | 946.15 |
| Julia regular AA / Refined | 83.21 | 140.19 | 894.17 |
| Deep glitch AA / Fast | 7,421.08 | 15.58 | 18,817.56 |
| Direct/deep/reverse, return step / Fast | 4,831.80 | 51.83 | 4,925.78 |

The columns overlap and have different start/end boundaries. First publication
can precede backend completion because samples are delivered progressively.
Refined Julia's base-publication fields are absent, not zero; its first visible
update is an AA tile. The three-sample tails and differences between modes are
not an optimization gate, and none of these values is a display/scanout time.

The fully reused return is an actionable non-arithmetic control: all 5,939,136
samples are retained, with no new backend region, yet the backend takes about
4.86 s headlessly and 4.83 s in the FX run. The current direct renderer still
builds missing spans for every tile. `ValidityMask.missingRowSpans` calls
unbounded `BitSet.nextClearBit` from each row start; a fully ready mask makes
those calls scan into the remaining frame. This is consistent with the prior
[validity-scan diagnosis](INTERACTION_LATENCY_RESULTS.md). No new JFR/native
attribution was captured here, and no mask optimization is included in this
change. The fixture supplies a repeatable control for that follow-up; controller
shortcuts can bypass some fully cached requests, so it is not a latency claim
about every real navigation gesture.

## Verification

`mvn clean test`: 374 tests, zero failures/errors, 27 opt-in skips. A separate
graphical run passes all eight new JavaFX baseline/lifetime tests and 14 existing
resize/input tests (two further explicitly gated cases remain skipped).
Baseline regression summary (private archive: `baseline-9-1-fx-fixed-20260907/fx-regressions.txt`),
resize regression summary (private archive: `baseline-9-1-fx-fixed-20260907/resize-regressions.txt`).

Coverage includes hidden Refined base publication, complete retained-frame return
with no new region, cancelled-generation suppression, same-frame AA preparation,
histogram output, resize/drag reuse, scene/window detachment and reattachment,
and collection of removed surfaces. Pure tests verify missing-time sentinels
and that sample conformance examines smooth and orbit-trap values as well as
iterations/escape flags.

The FX summary validator was checked against complete input, a duplicate request,
a missing repetition and a missing conformance marker. Invalid inputs are
rejected. Old result files are not overwritten.

## Remaining boundaries

This work supplies scope-matched JavaFX base/AA publication and software
post-layout observations. It does not time production input handlers on the
matrix, hardware/OS input delivery, compositor capture or physical scanout.
The existing [interaction measurements](INTERACTION_LATENCY_RESULTS.md) remain
a separate dataset. Allocation stacks, task scheduling internals, other
cancellation phases and sustained native-memory/thermal behavior remain open
in 9.1. The listener regression and the repeated matrix are evidence for this
specific ownership fix, not a general memory bound or thermal conclusion.
