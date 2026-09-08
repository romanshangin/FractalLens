# Roadmap 9.1 production input validation

Validation spans 2026-09-07–08 local; dataset names use the UTC run date 2026-09-08. Base commit `a8ecdabb`, branch
`codex/roadmap-9-1-input-matrix`. Saved environments identify a working-tree run;
the final dataset includes source SHA-256 hashes. Saved log trailing whitespace
and summary line endings are normalized for Git; numeric data are unchanged. These are scope/conformance
checks, with a first sequence and one measured repetition per case, no warmups.
They do not establish an optimization gain or pass the 30-pair performance gate.

## Runtime and reproducible inputs

Apple M3 Pro, macOS 26.6.2, aarch64, 12 reported processors. Both final runners
use Homebrew OpenJDK 26.0.2:
`/opt/homebrew/Cellar/openjdk/26.0.2/libexec/openjdk.jdk/Contents/Home/bin/java`,
`-Xmx4g`, JavaFX 26.0.2+3 and a 2x output scale. Launch logs identify the native
`com.sun.prism.es2.ES2Pipeline`, Apple M3 Pro renderer and vsync. GPU fractal
calculation and certified-FP32 requests are explicitly disabled.

The runs are sequential. No competing rendering/timing suite or JFR/native profiler
ran during them. Startup pipeline diagnostics and the opt-in event recorder
were enabled. Background activity, thermal state and physical presentation were
not controlled or measured. Source preparation and CPU controls occur outside
the handler-to-publication timers and can warm subsequent work.

The canonical matrix remains `9.1-v1`; its viewport values and caps were not
modified. Input scenarios are `9.1-input-v1`. Actual source/target manifests
record how camera fitting, pinch rounding, adaptive iterations and production
AA/cache policy affect the requests. See [the contract and commands](BASELINE_INPUT_BENCHMARK.md).

## Full matrix

[Final 480x270 dataset](benchmarks/baseline-9-1-input-final-20260908/samples.csv),
[raw events](benchmarks/baseline-9-1-input-final-20260908/events.csv),
[actual jobs](benchmarks/baseline-9-1-input-final-20260908/actual-manifest.csv),
[summary](benchmarks/baseline-9-1-input-final-20260908/summary.csv),
[environment](benchmarks/baseline-9-1-input-final-20260908/environment.txt),
[launch log](benchmarks/baseline-9-1-input-final-20260908/launch.log).

All 28 fixtures pass: **444 trials**, including 222 first sequences and 222
measured sequences. There are **364 completed navigation renders** and **80
no-change trials**, where a pan is clamped and the prior frame remains valid.
The scenario counts are 80 each for wheel, wheel burst, pinch, trackpad and drag;
36 navigation steps; and eight input-driven replacements. Both requested
Fast/Refined modes are covered. The [SUCCESS marker](benchmarks/baseline-9-1-input-final-20260908/SUCCESS)
requires every seed and every trial control to pass. The strict summarizer
accepts all 444 trials and checks their timings against the raw events.

No-change trials have absent render/completion metrics, not zero latency.
Every reported target is checked sample by sample and pixel by pixel. Source
samples/pixels are verified before they can be used for retained navigation.
The control repeats the same retention contract and independently calculates
missing work, without relaxing numerical or ARGB equality.

## Target-size follow-up

[1512x982 / 3024x1964 dataset](benchmarks/baseline-9-1-input-retina-20260908/samples.csv),
[summary](benchmarks/baseline-9-1-input-retina-20260908/summary.csv),
[actual jobs](benchmarks/baseline-9-1-input-retina-20260908/actual-manifest.csv),
[environment](benchmarks/baseline-9-1-input-retina-20260908/environment.txt),
[launch log](benchmarks/baseline-9-1-input-retina-20260908/launch.log),
[SUCCESS](benchmarks/baseline-9-1-input-retina-20260908/SUCCESS).

All **80 trials** pass, 40 at each size, all with completed target renders.
This follow-up selects `seahorse-fixed`, `julia-aa-regular`, `pan-25`,
`resize-then-drag`, `cancel-direct` and `direct-deep-reverse`. Ordinary scenes
use wheel/pinch; the other fixtures keep their navigation/replacement scripts.
Both requested modes and first/measured sequences are included. Resize targets
are respectively 1640x1054 and 3152x2036 physical pixels.

The strict summarizer accepts all 80 trials. The small run's 37 canonical
manifest rows and this run's 22 rows exactly match entries in the pinned
`BASELINE_FIXTURES.csv`; actual jobs remain separately recorded. Together the
final runs contain **524 validated trials**: 444 completed navigation renders
and 80 explicitly unchanged views. Large-size coverage is the six selected
fixtures, not a claim that all 28 fixtures were timed at both larger sizes.
There is one measured repetition per case, so the summary's median/p95/max are
single observations and do not establish statistical tails or an optimization win.

## Failure found through the real handlers

The scaled-exponent fixture starts at `(2,0)` with scale `1e-400`. A trackpad or
drag pan clamps the camera into its fitted home bounds, producing a pixel-grid
displacement far larger than `int` or `long`. `Viewport.snapToPixelGrid` rounded
the displacement and called `intValueExact`, raising `ArithmeticException:
Overflow`. Component-only render requests never exercised this path.

The new standalone regression reproduced the exception before the fix. Grid
alignment now retains the rounded integer displacement in `BigDecimal`; the
reuse planner still decides whether a bounded overlap exists. Regressions check
positive/negative shifts beyond `long`, preservation of ordinary integer-shift
coordinates, and successful trackpad/drag completion at the actual camera job.

A delayed trackpad exception also exposed a harness issue: JavaFX's default
exception handler logged the error while the old completed frame remained
available. The early runner could mistake it for a no-change gesture. The final
runner captures asynchronous FX failures and requires the idle frame to agree
with the camera. Its regression injects a timer failure and requires rejection.

Two partial development datasets are preserved and excluded from summaries:

- [Initial control-contract failure](benchmarks/baseline-9-1-input-20260908/FAILED.md):
  a fresh-reference recomputation of retained deep samples was not a bit-exact
  navigation control. The final control preserves previously verified samples.
- [Scaled-exponent failure](benchmarks/baseline-9-1-input-verified-20260908/FAILED.md),
  [launch evidence](benchmarks/baseline-9-1-input-verified-20260908/launch.log),
  [failing regression](benchmarks/baseline-9-1-input-verified-20260908/grid-snap-before.log).
  Despite its early directory name, this run has no `SUCCESS` marker.

## Regression checks

- `mvn clean test`: **386 tests**, zero failures/errors, 37 opt-in skips.
  [Full log](benchmarks/baseline-9-1-input-final-20260908/clean-test.log).
- `BaselineInputBenchmarkTest` with graphical access: **16 tests**, no failures
  or skips. [Summary](benchmarks/baseline-9-1-input-final-20260908/fx-regressions.txt),
  [log](benchmarks/baseline-9-1-input-final-20260908/fx-regressions.log). The
  `injected timer failure` message is intentional: the test requires rejection.
- Existing `FractalResizeFxTest`: **14 passed**, two separately gated skips.
  [Summary](benchmarks/baseline-9-1-input-final-20260908/existing-fx-regressions.txt).
- `python3 -m unittest discover -s scripts -p 'test_summarize_baseline_input.py'`:
  **seven passed**, including missing/duplicate trials, wrong-generation pulses,
  missing success, inconsistent raw timings and unstable endpoints. Existing
  summary files cannot be overwritten.
- Both final manifests match their canonical entries, both CSV validators pass,
  and `git diff --check` passes.

The graphics tests cover all five gestures, fixed/adaptive iteration behavior,
Refined hidden base publication, deterministic-jitter output, resize/drag grid
retention, reverse navigation, input replacement, the transient deep-AA toggle,
clamped no-change input, deep sample reuse, scaled-exponent pan and asynchronous
FX failures. A deliberately corrupted ARGB pixel is rejected by the control.

## Interpretation and remaining work

This step measures entry into installed production handlers through approximate
preview, target publication, full completion and generation-bound software
post-layout observations. It does not measure device/OS input delivery,
compositor capture, GPU completion, panel scanout or photons. For large buffers,
part of the unmanaged view can be outside the visible window.

Canonical and actual jobs can differ. In particular, a double pinch factor can
miss the canonical exact reverse-cache hit; entering deep from a direct seed
leaves the transient deep-AA toggle off; and direct base-only component seeds
receive the UI's normal AA. Neither fixture names nor requested Refined labels
alone establish equivalent algorithms or workloads.

Allocation attribution, internal task scheduling, worker cancellation tails at
additional phases, sustained native-memory/thermal behavior and alternating
30-pair/multiple-start performance decisions remain open in roadmap 9.1. The
new runner and grid-alignment fix are not claims that those gates have passed.
