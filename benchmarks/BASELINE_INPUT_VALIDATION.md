# Roadmap 9.1 production input validation

> Historical evidence: results and “current source” describe the dated experiment, not validation of this checkout. Only [selected evidence](README.md) is included; “private archive” entries are unavailable here. Original SHAs/commands may require private revisions or inputs. See [provenance and replay limits](../docs/development/PUBLICATION.md).

Validation spans 2026-09-07–08 local; dataset names use the UTC run date 2026-09-08.
Base commit `a8ecdabb`, branch `codex/roadmap-9-1-input-matrix`. Saved environments
identify a working-tree run; the final dataset includes source SHA-256 hashes. Saved log
trailing whitespace and summary line endings are normalized for Git; numeric data are
unchanged. These are scope/conformance checks, with a first sequence and one measured
repetition per case, no warmups. They do not prove an optimization gain or pass the
30-pair performance gate.

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

Final 480x270 dataset (private archive: `baseline-9-1-input-final-20260908/samples.csv`),
raw events (private archive: `baseline-9-1-input-final-20260908/events.csv`),
actual jobs (private archive: `baseline-9-1-input-final-20260908/actual-manifest.csv`),
summary (private archive: `baseline-9-1-input-final-20260908/summary.csv`),
environment (private archive: `baseline-9-1-input-final-20260908/environment.txt`),
launch log (private archive: `baseline-9-1-input-final-20260908/launch.log`).

All 28 fixtures pass: **444 trials**, including 222 first sequences and 222
measured sequences. There are **364 completed navigation renders** and **80
no-change trials**, where a pan is clamped and the prior frame remains valid.
The scenario counts are 80 each for wheel, wheel burst, pinch, trackpad and drag;
36 navigation steps; and eight input-driven replacements. Both requested
Fast/Refined modes are covered. The SUCCESS marker (private archive: `baseline-9-1-input-final-20260908/SUCCESS`)
requires every seed and every trial control to pass. The strict summarizer
accepts all 444 trials and checks their timings against the raw events.

No-change trials have absent render/completion metrics, not zero latency.
Every reported target is checked sample by sample and pixel by pixel. Source
samples/pixels are verified before they can be used for retained navigation.
The control repeats the same retention contract and independently calculates
missing work, without relaxing numerical or ARGB equality.

## Target-size follow-up

1512x982 / 3024x1964 dataset (private archive: `baseline-9-1-input-retina-20260908/samples.csv`),
summary (private archive: `baseline-9-1-input-retina-20260908/summary.csv`),
actual jobs (private archive: `baseline-9-1-input-retina-20260908/actual-manifest.csv`),
environment (private archive: `baseline-9-1-input-retina-20260908/environment.txt`),
launch log (private archive: `baseline-9-1-input-retina-20260908/launch.log`),
SUCCESS (private archive: `baseline-9-1-input-retina-20260908/SUCCESS`).

All **80 trials** pass, 40 at each size, all with completed target renders.
This follow-up selects `seahorse-fixed`, `julia-aa-regular`, `pan-25`,
`resize-then-drag`, `cancel-direct` and `direct-deep-reverse`. Ordinary scenes
use wheel/pinch; the other fixtures keep their navigation/replacement scripts.
Both requested modes and first/measured sequences are included. Resize targets
are respectively 1640x1054 and 3152x2036 physical pixels.

The strict summarizer accepts all 80 trials. The small run's 37 canonical manifest rows
and this run's 22 rows exactly match entries in the pinned `BASELINE_FIXTURES.csv`;
actual jobs remain separately recorded. Together the final runs contain **524 validated
trials**: 444 completed navigation renders and 80 explicitly unchanged views. Large-size
coverage is the six selected fixtures, not evidence that all 28 fixtures were timed at
both larger sizes. There is one measured repetition per case, so the summary's
median/p95/max are single observations and do not prove statistical tails or an
optimization win.

## Failure found through the real handlers

At `(2,0)`/`1e-400`, drag/trackpad home clamping displaces pixels beyond `int`/
`long`. `Viewport.snapToPixelGrid` rounding plus `intValueExact` raised
`ArithmeticException: Overflow`; component-only requests missed it.

Regression reproduced pre-fix failure. Keep rounded displacement in `BigDecimal`;
reuse planner still decides bounded overlap. Tests cover ±beyond-`long`, ordinary
integer-shift coordinates and actual-job trackpad/drag completion.

Delayed trackpad error was logged by default FX handler while old frame remained,
allowing false no-change classification. Final driver captures async failures and
requires idle frame/camera agreement; injected timer-error regression must reject.

Two partial development datasets remain in the private archive and were
excluded from the accepted summaries:

- Initial control-contract failure (private archive: `baseline-9-1-input-20260908/FAILED.md`):
  a fresh-reference recomputation of retained deep samples was not a bit-exact
  navigation control. The final control preserves previously verified samples.
- Scaled-exponent failure (private archive: `baseline-9-1-input-verified-20260908/FAILED.md`),
  launch evidence (private archive: `baseline-9-1-input-verified-20260908/launch.log`),
  failing regression (private archive: `baseline-9-1-input-verified-20260908/grid-snap-before.log`).
  Despite its early directory name, this run has no `SUCCESS` marker.

## Regression checks

- `mvn clean test`: **386 tests**, no failures/errors, 37 opt-in skips. Full log
  (private archive: `baseline-9-1-input-final-20260908/clean-test.log`).
- `BaselineInputBenchmarkTest` with graphical access: **16 tests**, no failures
  or skips. Summary (private archive: `baseline-9-1-input-final-20260908/fx-regressions.txt`),
  log (private archive: `baseline-9-1-input-final-20260908/fx-regressions.log`). The
  `injected timer failure` message is intentional: the test requires rejection.
- Existing `FractalResizeFxTest`: **14 passed**, two separately gated skips.
  Summary (private archive: `baseline-9-1-input-final-20260908/existing-fx-regressions.txt`).
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

Measures installed-handler entry → approximate preview/target/full/post-layout
with generation IDs. No device/OS delivery, compositor/GPU completion, panel scanout/
photons. Large unmanaged views may extend outside window.

Actual jobs can differ: double pinch misses exact reverse reuse; direct-to-deep
keeps transient deep AA off; direct base-only seeds get UI AA. Names/requested
Refined alone cannot prove equal algorithms/workloads.

9.1 allocation/scheduling/additional cancel tails/sustained native-memory/thermal
and alternating 30-pair/multi-start decisions remain open; driver/grid fix passes
none of those gates.

## 2026-09-21 test-contract correction

`resizeThenDragKeepsPhysicalGridAndIterationBudget` failed pristine `506ac1f`
after `Viewport.withCalculatedValues` retained bounded context across gestures.
Old assertion compared a fresh-context canonical fixture with extra guard digits:
scale/size/cap matched, centers differed far below a pixel. Require exact scale,
center within `10^-25` pixel step per axis; independent sample/pixel checks stay.
Fixture CSV/history unchanged. Isolated regression and 43-test graphical resize/
publication/input suite pass; two separately gated fullscreen skips remain.
